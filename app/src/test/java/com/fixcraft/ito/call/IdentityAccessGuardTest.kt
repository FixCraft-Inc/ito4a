package com.fixcraft.ito.call

import com.fixcraft.ito.security.AppLockedException
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IdentityAccessGuardTest {
    private lateinit var parent: File

    @Before fun prepare() { parent = Files.createTempDirectory("ito-identity-access-").toFile() }
    @After fun cleanup() { parent.deleteRecursively() }

    @Test fun lockedIdentityAccessPreservesCiphertextAndDoesNotBorrowTheKey() {
        val directory = File(parent, "identity")
        val key = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
        var denied = false
        var keyBorrows = 0
        val storage = IdentityStore(directory, IdentityKeys { keyBorrows++; key }, {},
            requireAccess = { if (denied) throw AppLockedException() })
        assertEquals(CallNative.PERSIST_COMMITTED, storage.persist(byteArrayOf(1, 2, 3)))
        val ciphertext = File(directory, "snapshot.bin").readBytes()
        val allowedBorrows = keyBorrows
        denied = true
        try { storage.load(); fail("Locked identity read was accepted") }
        catch (failure: IdentityStorageException) { assertEquals(IdentityFailure.KeyUnavailable, failure.reason) }
        val replacement = byteArrayOf(4, 5, 6)
        assertEquals(CallNative.PERSIST_NOT_COMMITTED, storage.persist(replacement))
        assertArrayEquals(byteArrayOf(0, 0, 0), replacement)
        assertEquals(allowedBorrows, keyBorrows)
        assertArrayEquals(ciphertext, File(directory, "snapshot.bin").readBytes())
        denied = false
        assertArrayEquals(byteArrayOf(1, 2, 3), storage.load())
    }
}
