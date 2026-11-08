package com.fixcraft.ito.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.content.LocalContentRepository
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual AndroidKeyStore content-key wrapping. Authentication prompts retain their separate device gate. */
@RunWith(AndroidJUnit4::class)
class AppLockStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val aliasPrefix = "ito.access.test.${UUID.randomUUID()}"
    private lateinit var parent: File
    private lateinit var access: File
    private lateinit var content: File
    private var retained = false

    @Before fun prepare() {
        parent = File(context.noBackupFilesDir, "access-test-${UUID.randomUUID()}").apply { mkdir() }
        access = File(parent, "access")
        content = File(parent, "content")
    }
    @After fun cleanup() {
        parent.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
            deleteEntry("$aliasPrefix.device.v1")
            deleteEntry("$aliasPrefix.auth.v1")
        }
    }
    private fun owner() = AppLockController(access, AndroidLockKeys(context, aliasPrefix), { retained }, {})
    private fun repository(lock: AppLockController) = LocalContentRepository(content,
        { operation -> lock.withContentKey(operation) }, lock::requireAccess, {}, { 1_800_000_000 })

    @Test fun androidKeystoreWrapsContentKeyAndReopensEncryptedMessages() {
        var lock = owner()
        var repository = repository(lock)
        repository.recordMessage(ByteArray(16) { 1 }, ByteArray(32) { 2 }, false, "local text".toByteArray(), 1_800_000_000)
        retained = true
        lock.lock()
        lock = owner()
        repository = repository(lock)
        assertEquals(LockState.Disabled, lock.state.value)
        repository.messages().single().use { assertEquals("local text", it.utf8.toString(Charsets.UTF_8)) }
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("$aliasPrefix.device.v1", null)
        assertNull(key.encoded)
    }

    @Test fun missingKeystoreKeyPreservesWrapperAndContentWithoutReplacement() {
        var lock = owner()
        var repository = repository(lock)
        repository.recordCall(ByteArray(32) { 3 }, false, 0, 1_800_000_000, 20)
        retained = true
        val wrapper = File(access, "access.bin").readBytes()
        val ciphertext = File(content, "snapshot.bin").readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$aliasPrefix.device.v1")
        lock = owner()
        repository = repository(lock)
        try { repository.history(); fail("Missing key was accepted") } catch (_: AppLockedException) {}
        assertEquals(LockState.Unavailable, lock.state.value)
        assertArrayEquals(wrapper, File(access, "access.bin").readBytes())
        assertArrayEquals(ciphertext, File(content, "snapshot.bin").readBytes())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("$aliasPrefix.device.v1"))
    }
}
