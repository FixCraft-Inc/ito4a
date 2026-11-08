package com.fixcraft.ito.call

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fixcraft.ito.R
import com.fixcraft.ito.ui.outcomeText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable

/** The real JNI clock refusal, with isolated snapshots and caller-supplied times. */
@RunWith(AndroidJUnit4::class)
class CallClockTest {
    @Test fun initiatorRollbackPreservesFreshAndReloadedIdentity() = withFixture(null) { fixture ->
        fixture.createIdentityAndContact()
        val own = CallNative.identity(fixture.store)!!
        fixture.createAndDestroyInitiator(ISSUED + 400)
        val saved = fixture.disk.copy()
        try {
            fixture.disk.nextResult = CallNative.PERSIST_NOT_COMMITTED
            refused(-15) { fixture.createAndDestroyInitiator(ISSUED + 200) }
            fixture.disk.assertSame(saved)
            repeat(2) {
                if (it != 0) fixture.reopen()
                val code = refused(CallNative.CLOCK_REGRESSED) {
                    fixture.createAndDestroyInitiator(ISSUED + 200)
                }
                val view = CallView(phase = CallPhase.Ended,
                    startFailure = CallStartFailure.Refused, failureCode = code)
                assertEquals(CallOutcome.ClockRegressed, CallOutcomes.of(view))
                assertEquals(R.string.outcome_clock_regressed, outcomeText(CallOutcomes.of(view)))
                assertArrayEquals(own, CallNative.identity(fixture.store))
                fixture.assertContact()
                fixture.disk.assertUncertainClockPreserves(saved)
                // Reaching the previous floor does not prove recovery of
                // the wall clock, including after the store was reloaded.
                val latched = fixture.disk.copy()
                try {
                    refused(CallNative.CLOCK_REGRESSED) {
                        fixture.createAndDestroyInitiator(ISSUED + 400)
                    }
                    fixture.disk.assertSame(latched)
                } finally { latched.fill(0) }
            }
        } finally { saved.fill(0) }
    }

    @Test fun expiredUnspentInvitationCannotReviveAfterRollbackOrReopen() = withFixture { fixture ->
        fixture.createAndDestroyInitiator(ISSUED + 400)
        val saved = fixture.disk.copy()
        val invitation = invitation()
        try {
            repeat(2) {
                if (it != 0) fixture.reopen()
                refused(CallNative.CLOCK_REGRESSED) {
                    val opened = fixture.open(invitation, ISSUED + 200)
                    CallNative.invitationDestroy(opened)
                }
                fixture.disk.assertUncertainClockPreserves(saved)
                fixture.assertContact()
            }
        } finally { saved.fill(0); invitation.fill(0) }
    }

    @Test fun responderRollbackLeavesTheInvitationWithItsCaller() = withFixture { fixture ->
        val invitation = invitation()
        var opened = fixture.open(invitation, ISSUED + 110)
        invitation.fill(0)
        val saved = fixture.disk.copy()
        try {
            refused(CallNative.CLOCK_REGRESSED) {
                val unexpected = CallNative.callCreateResponder(fixture.store, opened, relay(), SERVICE, ISSUED + 109)
                opened = 0L
                CallNative.callDestroy(unexpected)
            }
            assertArrayEquals(peer(), CallNative.invitationInitiator(opened))
            fixture.disk.assertUncertainClockPreserves(saved)
            refused(CallNative.CLOCK_REGRESSED) {
                val unexpected = CallNative.callCreateResponder(fixture.store, opened, relay(), SERVICE, ISSUED + 111)
                opened = 0L
                CallNative.callDestroy(unexpected)
            }
            assertArrayEquals(peer(), CallNative.invitationInitiator(opened))
            fixture.assertContact()
        } finally {
            saved.fill(0)
            if (opened != 0L) CallNative.invitationDestroy(opened)
        }
    }

    @Test fun equalAndForwardTimeAllowFreshAttempts() = withFixture { fixture ->
        // The independent fixture's persisted high-water mark is ISSUED + 100.
        fixture.createAndDestroyInitiator(ISSUED + 100)
        fixture.createAndDestroyInitiator(ISSUED + 101)
        fixture.assertContact()
    }

    @Test fun checkpointFailuresKeepIdentityAndReplayAndRequireReopenAfterAmbiguity() = withFixture { fixture ->
        val own = CallNative.identity(fixture.store)!!
        val saved = fixture.disk.copy()
        try {
            fixture.disk.nextResult = CallNative.PERSIST_NOT_COMMITTED
            refused(-15) { fixture.createAndDestroyInitiator(ISSUED + 110) }
            fixture.disk.assertSame(saved)
            assertArrayEquals(own, CallNative.identity(fixture.store))
            fixture.assertContact()

            fixture.disk.nextResult = CallNative.PERSIST_AMBIGUOUS
            refused(CallNative.STORAGE_AMBIGUOUS) { fixture.createAndDestroyInitiator(ISSUED + 110) }
            refused(CallNative.STORAGE_AMBIGUOUS) { fixture.createAndDestroyInitiator(ISSUED + 111) }
            assertArrayEquals(own, CallNative.identity(fixture.store))
            fixture.assertContact()
            fixture.reopen()
            refused(CallNative.CLOCK_REGRESSED) { fixture.createAndDestroyInitiator(ISSUED + 109) }
            refused(CallNative.CLOCK_REGRESSED) { fixture.createAndDestroyInitiator(ISSUED + 111) }
            fixture.disk.assertUncertainClockPreserves(saved)
            assertArrayEquals(own, CallNative.identity(fixture.store))
            fixture.assertContact()
        } finally { saved.fill(0) }
    }

    @Test fun negativeSecondsAreRejectedWithoutTakingNativeOwnership() = withFixture { fixture ->
        val invitation = invitation()
        try {
            val before = fixture.disk.copy()
            try {
                refused(-1, "time") { fixture.createAndDestroyInitiator(-1) }
                refused(-1, "time") {
                    val unexpected = fixture.open(invitation, -1)
                    CallNative.invitationDestroy(unexpected)
                }
                fixture.disk.assertSame(before)
            } finally { before.fill(0) }
            var opened = fixture.open(invitation, ISSUED + 110)
            val saved = fixture.disk.copy()
            try {
                refused(-1, "time") {
                    val unexpected = CallNative.callCreateResponder(fixture.store, opened, relay(), SERVICE, -1)
                    opened = 0L
                    CallNative.callDestroy(unexpected)
                }
                assertArrayEquals(peer(), CallNative.invitationInitiator(opened))
                fixture.disk.assertSame(saved)
            } finally {
                saved.fill(0)
                if (opened != 0L) CallNative.invitationDestroy(opened)
            }
        } finally { invitation.fill(0) }
    }

    private fun withFixture(snapshot: ByteArray? = knownSnapshot(), block: (Fixture) -> Unit) {
        val before = CallNative.liveHandles()
        Fixture(snapshot).use(block)
        assertArrayEquals(before, CallNative.liveHandles())
    }

    private fun refused(code: Int, operation: String? = null, action: () -> Unit): Int {
        try { action(); fail("expected call refusal $code") }
        catch (failure: CallFailure) {
            assertEquals(code, failure.code)
            if (operation != null) assertEquals(operation, failure.operation)
            return failure.code
        }
        error("expected a typed refusal")
    }

    private class Fixture(snapshot: ByteArray?) : Closeable {
        private val context = CallNative.contextCreate()
        val disk = Disk(snapshot)
        var store = 0L
            private set

        init {
            try { store = CallNative.storeOpen(context, snapshot, disk) }
            catch (failure: Throwable) { CallNative.contextDestroy(context); disk.close(); throw failure }
            finally { snapshot?.fill(0) }
        }

        fun createIdentityAndContact() {
            CallNative.createIdentity(store, ISSUED)
            CallNative.addContact(store, peer(), "Alice".toByteArray(), ISSUED + 1)
            CallNative.verifyContact(store, peer(), CallNative.fingerprint(context, peer()), ISSUED + 2)
        }

        fun createAndDestroyInitiator(now: Long) {
            val call = CallNative.callCreateInitiator(store, peer(), relay(), SERVICE, 60, now)
            CallNative.callDestroy(call)
        }

        fun open(invitation: ByteArray, now: Long) = CallNative.invitationOpen(store, invitation, relay(), SERVICE, now)

        fun assertContact() {
            val contact = CallNative.contacts(store).single()
            assertArrayEquals(peer(), contact.key)
            assertEquals(CallNative.TRUST_VERIFIED, contact.trust)
            assertEquals("Alice", contact.name)
        }

        fun reopen() {
            CallNative.storeDestroy(store)
            store = 0L
            val bytes = disk.copy()
            try { store = CallNative.storeOpen(context, bytes, disk) }
            finally { bytes.fill(0) }
        }

        override fun close() {
            try {
                if (store != 0L) CallNative.storeDestroy(store)
                CallNative.contextDestroy(context)
            } finally { disk.close() }
        }
    }

    private class Disk(snapshot: ByteArray?) : SnapshotPersistence, Closeable {
        private var bytes = snapshot?.copyOf() ?: ByteArray(0)
        var nextResult = CallNative.PERSIST_COMMITTED

        override fun persist(snapshot: ByteArray): Int {
            try {
                val result = nextResult
                nextResult = CallNative.PERSIST_COMMITTED
                if (result != CallNative.PERSIST_NOT_COMMITTED) {
                    val candidate = snapshot.copyOf()
                    bytes.fill(0)
                    bytes = candidate
                }
                return result
            } finally { snapshot.fill(0) }
        }

        fun copy() = bytes.copyOf()
        // Compare without putting a snapshot's seed into an assertion failure.
        fun assertSame(expected: ByteArray) = assertTrue("the stored snapshot changed", expected.contentEquals(bytes))
        fun assertUncertainClockPreserves(expected: ByteArray) {
            // identity-snapshot/1: domain(24), identity flag(1), seed(32),
            // public key(32), identity creation time(8), then clock(8).
            val clockOffset = 97
            val clockEnd = clockOffset + 8
            assertTrue("the snapshot shape changed", expected.size == bytes.size && bytes.size >= clockEnd)
            assertTrue("clock uncertainty was not retained", (clockOffset until clockEnd).all { bytes[it] == (-1).toByte() })
            assertTrue("identity, contact or replay state changed", bytes.indices.all {
                it in clockOffset until clockEnd || bytes[it] == expected[it]
            })
        }
        override fun close() { bytes.fill(0) }
    }

    private companion object {
        const val ISSUED = 1_700_000_000L
        const val SERVICE = "ito-call"
        fun relay() = ByteArray(32) { 3 }
        fun peer() = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")

        // Exact snapshot_1 from the ito repository's call/tests/vectors.txt. Its RFC 8032
        // test identity trusts peer() and retains one different invitation's
        // spent record. These are published test keys, never app credentials.
        fun knownSnapshot() = hex(
            "69746f2f6964656e746974792d736e617073686f742f3100014ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb" +
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c000000006553f100000000006553f16400000000000000000001" +
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a0200000000006553f101000000006553f10205416c6963650001" +
            "0101010101010101010101010101010101010101010101010101010101010101a67b1cc3cad8a84e47591523639ec7a61b79de73b3160d19b8b649f81a5810fd000000006553f22c02",
        )

        // The independent vector writer's invitation_1, with ID 0x09 repeated
        // and a new Ed25519 signature from its published RFC 8032 seed_1.
        // This ID is unspent in knownSnapshot(), and its expiry is ISSUED + 300.
        fun invitation() = hex(
            "69746f2f696e7669746174696f6e2f31000909090909090909090909090909090909090909090909090909090909090909" +
            "0202020202020202020202020202020202020202020202020202020202020202d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a" +
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c0303030303030303030303030303030303030303030303030303030303030303" +
            "000869746f2d63616c6c0404040404040404040404040404040404040404040404040404040404040404000000006553f100000000006553f22c0001" +
            "0505050505050505050505050505050505050505050505050505050505050505a29220560a4978e8349b57bf33862f0796b101a60e73d2f3b6e85fb3091ddab54" +
            "acc8b280d28a49be2267ca3b6efd633542446bb1c44d85bdee78839e3335804",
        )

        fun hex(value: String) = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
