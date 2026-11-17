package com.fixcraft.ito.security

import java.io.File
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.Key
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Tests the access state and real AES wrapper format with injectable platform key authorization. */
class AppLockControllerTest {
    private lateinit var parent: File
    private lateinit var root: File
    private lateinit var keys: TestKeys
    private var retained = false

    @Before fun prepare() {
        parent = Files.createTempDirectory("ito-access-test-").toFile()
        root = File(parent, "access")
        keys = TestKeys()
    }
    @After fun cleanup() { parent.deleteRecursively() }
    private fun owner() = AppLockController(root, keys, { retained }, {})
    private fun key(owner: AppLockController) = owner.withContentKey { it.copyOf() }
    private fun complete(owner: AppLockController, action: LockAction) {
        val challenge = owner.prepare(action)
        owner.complete(challenge, challenge.cipher)
    }
    private fun locked(block: () -> Unit) {
        try { block(); fail("Access was allowed") } catch (_: AppLockedException) {}
    }
    private fun unavailable(block: () -> Unit) {
        try { block(); fail("Unavailable authentication was accepted") } catch (_: LockUnavailableException) {}
    }

    @Test fun retainedChatDirectoryRefusesReplacementContentKeyWithoutAWrapper() {
        val chats = File(parent, "ito-chats").apply { mkdir() }
        assertFalse(hasRetainedContent(parent))
        File(chats, "retained.history").writeBytes(byteArrayOf(1))
        val owner = AppLockController(root, keys, { hasRetainedContent(parent) }, {})
        assertEquals(LockState.Unavailable, owner.state.value)
        locked { key(owner) }
        assertFalse(File(root, "access.bin").exists())
        assertFalse(keys.hasExistingKey())
    }

    @Test fun enableLocksOnBackgroundAndRequiresNewCryptographicCompletion() {
        val owner = owner()
        val original = key(owner)
        complete(owner, LockAction.Enable)
        assertEquals(LockState.Unlocked, owner.state.value)
        owner.lock()
        assertEquals(LockState.Locked, owner.state.value)
        locked { owner.requireAccess() }
        locked { key(owner) }
        complete(owner, LockAction.Unlock)
        assertArrayEquals(original, key(owner))
    }

    @Test fun disabledAuthorizationAccessReturnsWithoutCreatingOrBorrowingAKey() = runBlocking {
        val owner = owner()
        owner.awaitAccess()
        assertEquals(LockState.Disabled, owner.state.value)
        assertFalse(File(root, "access.bin").exists())
    }

    @Test fun authorizationAccessWaitsForExplicitUnlockWithoutGrantingOrReplacingAccess() = runBlocking {
        val owner = owner()
        val original = key(owner)
        complete(owner, LockAction.Enable)
        owner.lock()
        val before = File(root, "access.bin").readBytes()
        var admitted = false
        val callback = async(start = CoroutineStart.UNDISPATCHED) { owner.awaitAccess(); admitted = true }
        try {
            assertFalse(callback.isCompleted)
            assertFalse(admitted)
            assertEquals(LockState.Locked, owner.state.value)
            locked { key(owner) }
            assertArrayEquals(before, File(root, "access.bin").readBytes())
            complete(owner, LockAction.Unlock)
            withTimeout(5000) { callback.await() }
            assertTrue(admitted)
            assertArrayEquals(original, key(owner))
        } finally { callback.cancelAndJoin() }
    }

    @Test fun unavailableAuthorizationAccessCanBeCanceledWithoutChangingCiphertext() = runBlocking {
        val owner = owner()
        key(owner)
        val file = File(root, "access.bin")
        val before = file.readBytes().also { it[0] = 0 }
        file.writeBytes(before)
        owner.reload()
        var admitted = false
        val callback = async(start = CoroutineStart.UNDISPATCHED) { owner.awaitAccess(); admitted = true }
        callback.cancelAndJoin()
        assertTrue(callback.isCancelled)
        assertFalse(admitted)
        assertEquals(LockState.Unavailable, owner.state.value)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun processDeathPreservesEnabledProtectionAndContentKey() {
        var owner = owner()
        val original = key(owner)
        complete(owner, LockAction.Enable)
        owner = owner()
        assertEquals(LockState.Locked, owner.state.value)
        locked { key(owner) }
        complete(owner, LockAction.Unlock)
        assertArrayEquals(original, key(owner))
    }

    @Test fun canceledEnableLeavesProtectionOffAndExistingKeyUnchanged() {
        val owner = owner()
        val original = key(owner)
        val before = File(root, "access.bin").readBytes()
        val challenge = owner.prepare(LockAction.Enable)
        owner.cancel(challenge)
        assertEquals(LockState.Disabled, owner.state.value)
        assertArrayEquals(before, File(root, "access.bin").readBytes())
        assertArrayEquals(original, key(owner))
        locked { owner.complete(challenge, challenge.cipher) }
    }

    @Test fun canceledUnlockAndStalePromptNeverGrantAccess() {
        val owner = owner()
        complete(owner, LockAction.Enable)
        owner.lock()
        val challenge = owner.prepare(LockAction.Unlock)
        owner.lock()
        locked { owner.complete(challenge, challenge.cipher) }
        assertEquals(LockState.Locked, owner.state.value)
        locked { owner.requireAccess() }
    }

    @Test fun substitutedCryptoObjectIsRefused() {
        val owner = owner()
        complete(owner, LockAction.Enable)
        owner.lock()
        val challenge = owner.prepare(LockAction.Unlock)
        val different = Cipher.getInstance("AES/GCM/NoPadding")
        locked { owner.complete(challenge, different) }
        assertEquals(LockState.Locked, owner.state.value)
        owner.cancel(challenge)
    }

    @Test fun missingAuthenticatedKeyPreservesCiphertextAndNeverCreatesReplacement() {
        val owner = owner()
        complete(owner, LockAction.Enable)
        owner.lock()
        val before = File(root, "access.bin").readBytes()
        keys.auth = null
        val created = keys.authCreates
        unavailable { owner.prepare(LockAction.Unlock) }
        assertEquals(created, keys.authCreates)
        assertArrayEquals(before, File(root, "access.bin").readBytes())
        assertEquals(LockState.Locked, owner.state.value)
    }

    @Test fun unavailableDeviceCannotSilentlyDisableProtection() {
        val owner = owner()
        complete(owner, LockAction.Enable)
        owner.lock()
        keys.available = false
        unavailable { owner.prepare(LockAction.Unlock) }
        unavailable { owner.prepare(LockAction.Disable) }
        owner.reload()
        assertEquals(LockState.Locked, owner.state.value)
    }

    @Test fun disableRequiresFreshPromptAndPreservesContentKey() {
        val owner = owner()
        val original = key(owner)
        complete(owner, LockAction.Enable)
        val disable = owner.prepare(LockAction.Disable)
        owner.cancel(disable)
        assertEquals(LockState.Unlocked, owner.state.value)
        complete(owner, LockAction.Disable)
        assertEquals(LockState.Disabled, owner.state.value)
        owner.lock()
        assertArrayEquals(original, key(owner()))
    }

    @Test fun corruptWrapperFailsClosedWithoutReplacingAnyBytes() {
        val owner = owner()
        key(owner)
        val file = File(root, "access.bin")
        val bytes = file.readBytes().also { it[0] = 0 }
        file.writeBytes(bytes)
        val reopened = owner()
        assertEquals(LockState.Unavailable, reopened.state.value)
        locked { key(reopened) }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun lostWrapperWithRetainedContentNeverCreatesNewContentKey() {
        val owner = owner()
        key(owner)
        retained = true
        File(root, "access.bin").delete()
        val reopened = owner()
        assertEquals(LockState.Unavailable, reopened.state.value)
        locked { key(reopened) }
        assertFalse(File(root, "access.bin").exists())
    }

    @Test fun authenticationFailurePreservesEnabledWrapper() {
        val owner = owner()
        complete(owner, LockAction.Enable)
        owner.lock()
        keys.auth = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
        val before = File(root, "access.bin").readBytes()
        val challenge = owner.prepare(LockAction.Unlock)
        unavailable { owner.complete(challenge, challenge.cipher) }
        assertEquals(LockState.Unavailable, owner.state.value)
        assertArrayEquals(before, File(root, "access.bin").readBytes())
        locked { owner.requireAccess() }
    }

    @Test fun missingWrapperCannotSilentlyDisableLockEvenWithoutContent() {
        val owner = owner()
        complete(owner, LockAction.Enable)
        assertTrue(File(root, "access.bin").delete())
        val reopened = owner()
        assertEquals(LockState.Unavailable, reopened.state.value)
        locked { reopened.requireAccess() }
        assertFalse(File(root, "access.bin").exists())
    }

    @Test fun unauthenticatedFlagChangeCannotUnlockLegacyStoreAccess() {
        val owner = owner()
        complete(owner, LockAction.Enable)
        val file = File(root, "access.bin")
        val changed = file.readBytes().also { it[5] = 0 }
        file.writeBytes(changed)
        val reopened = owner()
        assertEquals(LockState.Unavailable, reopened.state.value)
        locked { reopened.requireAccess() }
        assertArrayEquals(changed, file.readBytes())
    }

    @Test fun reloadNeverEmitsDisabledBeforeWrapperAuthenticates() {
        val owner = owner()
        key(owner)
        val file = File(root, "access.bin")
        val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        val observed = mutableListOf<LockState>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            // Unconfined collection observes each synchronous publication. A
            // queued/conflated observer could miss the brief unsafe state.
            scope.launch { owner.state.collect { observed.add(it) } }
            observed.clear()
            owner.reload()
            assertEquals(listOf(LockState.Unavailable), observed)
            locked { owner.requireAccess() }
            assertArrayEquals(corrupted, file.readBytes())
        } finally { scope.cancel() }
    }

    @Test fun enablingRetiresTheUnauthenticatedWrappingKey() {
        val owner = owner()
        key(owner)
        val old = File(root, "access.bin").readBytes()
        complete(owner, LockAction.Enable)
        assertFalse(keys.hasDeviceKey())
        File(root, "access.bin").writeBytes(old)
        val reopened = owner()
        locked { key(reopened) }
        assertEquals(LockState.Unavailable, reopened.state.value)
    }

    @Test fun failedKeyRetirementCannotPublishUnlockedAndCanRecoverByAuthentication() {
        val owner = owner()
        keys.failRetirement = true
        unavailable { complete(owner, LockAction.Enable) }
        assertEquals(LockState.Unavailable, owner.state.value)
        owner.reload()
        assertEquals(LockState.Locked, owner.state.value)
        keys.failRetirement = false
        complete(owner, LockAction.Unlock)
        assertEquals(LockState.Unlocked, owner.state.value)
        assertFalse(keys.hasDeviceKey())
    }

    @Test fun credentialActivityBackgroundLocksAccessAndDefersProofUntilForeground() {
        val owner = owner()
        complete(owner, LockAction.Enable)
        val challenge = owner.prepare(LockAction.Disable)
        owner.background(challenge)
        assertEquals(LockState.Locked, owner.state.value)
        locked { owner.requireAccess() }
        locked { owner.complete(challenge, challenge.cipher) }
        owner.foreground()
        owner.complete(challenge, challenge.cipher)
        assertEquals(LockState.Disabled, owner.state.value)
    }

    @Test fun unrelatedBackgroundInvalidatesPendingAuthentication() {
        val owner = owner()
        complete(owner, LockAction.Enable)
        owner.lock()
        val challenge = owner.prepare(LockAction.Unlock)
        owner.background()
        owner.foreground()
        locked { owner.complete(challenge, challenge.cipher) }
        assertEquals(LockState.Locked, owner.state.value)
    }

    @Test fun authenticatedAadWaitsForCryptoObjectAuthorizationForEveryAction() {
        val operations = mutableListOf<AuthenticatedCipher>()
        val owner = AppLockController(root, keys, { retained }, {}, {
            AuthenticatedCipher().also { operations += it }
        })
        val original = key(owner)
        fun authorize(action: LockAction) {
            val challenge = owner.prepare(action)
            val cipher = operations.last()
            assertSame(cipher, challenge.cipher)
            cipher.authorize()
            owner.complete(challenge, cipher)
            assertArrayEquals(original, key(owner))
        }
        authorize(LockAction.Enable)
        assertEquals(LockState.Unlocked, owner.state.value)
        owner.lock()
        authorize(LockAction.Unlock)
        assertEquals(LockState.Unlocked, owner.state.value)
        authorize(LockAction.Disable)
        assertEquals(LockState.Disabled, owner.state.value)
        assertEquals(3, operations.size)
        original.fill(0)
    }

    private class AuthenticatedCipher(private val operation: AuthenticatedCipherSpi = AuthenticatedCipherSpi()) :
        Cipher(operation, operation.delegate.provider, "AES/GCM/NoPadding") {
        fun authorize() { operation.authorized = true }
    }

    /** AndroidKeyStore caches an unauthenticated AAD error until doFinal. */
    private class AuthenticatedCipherSpi : CipherSpi() {
        val delegate: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var authorized = false
        private var rejectedAad = false
        override fun engineSetMode(mode: String) { require(mode.equals("GCM", ignoreCase = true)) }
        override fun engineSetPadding(padding: String) { require(padding.equals("NoPadding", ignoreCase = true)) }
        override fun engineGetBlockSize() = delegate.blockSize
        override fun engineGetOutputSize(inputLen: Int) = delegate.getOutputSize(inputLen)
        override fun engineGetIV(): ByteArray = delegate.iv
        override fun engineGetParameters(): AlgorithmParameters = delegate.parameters
        override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) { delegate.init(opmode, key, random) }
        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) {
            delegate.init(opmode, key, params, random)
        }
        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameters?, random: SecureRandom?) {
            delegate.init(opmode, key, params, random)
        }
        override fun engineUpdateAAD(input: ByteArray, inputOffset: Int, inputLen: Int) {
            if (!authorized) rejectedAad = true
            else if (!rejectedAad) delegate.updateAAD(input, inputOffset, inputLen)
        }
        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray =
            throw UnsupportedOperationException("Test uses one complete operation")
        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
            throw UnsupportedOperationException("Test uses one complete operation")
        override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray {
            if (!authorized || rejectedAad) throw IllegalBlockSizeException("Operation was not authorized before AAD")
            return delegate.doFinal(input, inputOffset, inputLen)
        }
        override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
            throw UnsupportedOperationException("Test uses owned output")
    }

    private class TestKeys : LockKeys {
        var auth: SecretKey? = null
        private var device: SecretKey? = null
        var available = true
        var authCreates = 0
        var failRetirement = false
        override fun authenticationAvailable() = available
        override fun hasExistingKey() = auth != null || device != null
        fun hasDeviceKey() = device != null
        override fun retireDeviceKey() {
            if (failRetirement) throw LockUnavailableException()
            device = null
        }
        override fun key(authenticated: Boolean, allowCreate: Boolean): SecretKey {
            val current = if (authenticated) auth else device
            if (current != null) return current
            if (!allowCreate) throw LockUnavailableException()
            val created = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
            if (authenticated) { auth = created; authCreates++ } else device = created
            return created
        }
    }
}
