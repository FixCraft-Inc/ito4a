package com.fixcraft.ito.transport.store

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class SnapshotCodecTest {
    @Test fun rejectsUnknownVersionAndNegativeOrOversizedCounts() {
        for ((version, count) in listOf(2 to 0, 1 to -1, 1 to 9)) {
            val encoded = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use {
                    it.writeInt(version)
                    it.writeInt(count)
                }
            }.toByteArray()
            expectCorrupt(encoded)
        }
    }

    @Test fun rejectsTruncationTrailingDataAndUnboundSelection() {
        CatalogSnapshot().use { snapshot ->
            val encoded = SnapshotCodec.encode(snapshot)
            for (length in encoded.indices) expectCorrupt(encoded.copyOf(length))
            expectCorrupt(encoded + byteArrayOf(1))
            snapshot.selectedId = "a".repeat(32)
            expectCorrupt(SnapshotCodec.encode(snapshot))
        }
    }

    @Test fun decodingCopiesMaterialAndClearsItAtOwnerClose() {
        val original = "fixture-secret".toByteArray()
        CatalogSnapshot().use { snapshot ->
            snapshot.entries += MaterialEntry(
                ConfigurationMetadata("1".repeat(32), "Manual"),
                mapOf("yume.json" to original),
            )
            val encoded = SnapshotCodec.encode(snapshot)
            val decoded = SnapshotCodec.decode(encoded)
            val owned = decoded.entries.single().files.getValue("yume.json")
            assertArrayEquals(original, owned)
            assertNotSame(original, owned)
            encoded.fill(0)
            assertArrayEquals(original, owned)
            decoded.close()
            assertTrue(owned.all { it == 0.toByte() })
        }
    }

    private fun expectCorrupt(bytes: ByteArray) {
        try {
            SnapshotCodec.decode(bytes).close()
            fail("Expected corrupt snapshot")
        } catch (failure: StoreException) {
            assertEquals(StoreFailure.Corrupt, failure.reason)
        }
    }
}
