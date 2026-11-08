package com.fixcraft.ito.call

import android.view.WindowManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.MainActivity
import com.fixcraft.ito.R
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.nativebridge.ItoNative
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

annotation class RequiresI4bFixture

/**
 * One endpoint of a live profile-1 call, run by the host's I4b orchestrator.
 * The host provisions a sealed transport kit in Downloads, the relay service
 * and the other endpoint, and exchanges contact codes and the invitation
 * through this app's private handoff directory. The test imports the kit
 * through the system document picker, connects, verifies the peer's full
 * fingerprint and runs the call through CallController. Only hashes, counts
 * and statuses leave the device.
 */
@RequiresI4bFixture
@RunWith(AndroidJUnit4::class)
class CallLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = context.applicationContext as ItoApplication
    private val handoff = File(context.noBackupFilesDir, "i4b-fixtures")
    private val config by lazy {
        check(InstrumentationRegistry.getArguments().getString("i4bLive") == "true")
        JSONObject(File(handoff, "config.json").readText())
    }

    @Test fun endpoint() {
        val role = config.getString("role")
        check(role in setOf("initiator", "responder", "interrupts", "replay", "transport_loss"))
        val book = app.callBook
        book.open()
        val ready = book.state.value as BookState.Ready
        if (ready.identity == null) {
            // A replay runs after the process died, on the data it left.
            check(role != "replay") { "the identity did not survive the restart" }
            book.createIdentity()
        }
        val own = (book.state.value as BookState.Ready).identity!!
        write("identity.json", JSONObject().put("code", own.code).put("fingerprint", own.fingerprint))

        val peer = JSONObject(awaitFile("peer.json", 120_000).readText())
        val key = CallNative.contactCodeParse(peer.getString("code"))
        val known = (book.state.value as BookState.Ready).contacts.any { it.key.contentEquals(key) }
        check(known || role != "replay") { "the contact did not survive the restart" }
        if (!known) book.addContact(peer.getString("code"), "peer")
        if (!(book.state.value as BookState.Ready).contacts.single { it.key.contentEquals(key) }.verified) {
            book.verify(key, peer.getString("fingerprint"))
        }
        assertTrue((book.state.value as BookState.Ready).contacts.single { it.key.contentEquals(key) }.verified)

        importAndConnect()
        val calls = app.calls
        if (role == "interrupts") return interrupts(key)
        if (role == "replay") return replay()
        if (role == "transport_loss") return transportLoss(key)
        // The person's path: the Calls tab, the verified contact, Call. Only the
        // invitation crosses devices through the handoff, since emulators
        // cannot scan each other's screens.
        compose.onNode(hasText(text(R.string.calls)) and isSelectable()).performClick()
        compose.waitForIdle()
        if (role == "initiator") {
            check(config.optInt("lifetime", CallController.DEFAULT_LIFETIME) == CallController.DEFAULT_LIFETIME)
            press("peer")
            press(text(R.string.call_contact))
            await(60_000) { calls.view.value.invitation != null || calls.view.value.phase == CallPhase.Ended }
            val invitation = requireNotNull(calls.view.value.invitation) { "no invitation: ${calls.view.value}" }
            check(invitationScreenIsSecure()) { "the invitation screen is not secure" }
            File(handoff, "invitation.bin.part").writeBytes(invitation)
            File(handoff, "invitation.bin.part").renameTo(File(handoff, "invitation.bin"))
        } else {
            val invitation = awaitFile("invitation.bin", 120_000)
            val bytes = invitation.readBytes()
            invitation.delete()
            calls.answer(bytes)
            await(60_000) { calls.view.value.phase == CallPhase.Consent || calls.view.value.phase == CallPhase.Ended }
            // The consent screen names the authenticated contact.
            compose.waitUntil(10_000) { exists(hasText(context.getString(R.string.consent_title, "peer"))) }
            if (config.optBoolean("hold_at_consent", false)) {
                // Consent follows the durable spend and authenticated peer proof.
                // The host kills this live process here, before a clean teardown.
                write("spent.json", JSONObject().put("phase", calls.view.value.phase.name)
                    .put("pid", android.os.Process.myPid()).put("nonce", config.getString("nonce"))
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime()))
                awaitFile("release-consent", 120_000)
                error("process-death fixture was released without a kill")
            }
            press(text(if (config.optString("decision", "accept") == "decline") R.string.decline else R.string.accept))
        }
        await(90_000) { calls.view.value.phase == CallPhase.Media || calls.view.value.phase == CallPhase.Ended }
        if (calls.view.value.phase == CallPhase.Media) {
            check(config.optInt("payload_bytes", CallController.TEST_PAYLOAD_BYTES) == CallController.TEST_PAYLOAD_BYTES)
            compose.waitUntil(10_000) { exists(hasText(context.getString(R.string.call_connected, "peer"))) }
            press(text(R.string.send_test_payload))
            val expected = config.optLong("expect_bytes", CallController.TEST_PAYLOAD_BYTES.toLong())
            await(90_000) {
                val view = calls.view.value
                (view.receivedBytes >= expected && view.sentSha256.isNotEmpty()) || view.phase == CallPhase.Ended
            }
            if (role == "initiator") press(text(R.string.end_call))
        }
        await(90_000) { calls.view.value.phase == CallPhase.Ended && calls.view.value.receivedSha256.isNotEmpty() ||
            calls.view.value.phase == CallPhase.Ended && calls.view.value.receivedBytes == 0L }
        val view = calls.view.value
        write("result.json", JSONObject().put("role", role).put("result", view.result)
            .put("local_end", view.localEnd).put("remote_end", view.remoteEnd)
            .put("attributed", view.endAttributed).put("peer_authenticated", view.peerAuthenticated)
            .put("sent", view.sentBytes).put("received", view.receivedBytes)
            .put("sent_sha256", view.sentSha256).put("received_sha256", view.receivedSha256)
            .put("start_failure", view.startFailure?.name ?: JSONObject.NULL)
            .put("component", CallNative.componentVersion())
            .put("transport_peer", app.connections.state.value.peer))
        app.connections.stop()
        await(30_000) { app.connections.state.value.phase != ConnectionPhase.Connected }
    }

    /**
     * Ends attempts at each lifecycle point the controller owns, against the
     * live run: a cancel before and during the lane, Stop in the middle of a
     * call with the host CLI as the peer, and lanes refused after Stop. Each
     * step records the typed end and the native owner counts, which must
     * return to what they were before the step.
     */
    private fun interrupts(key: ByteArray) {
        val calls = app.calls
        val steps = JSONObject()
        // Every change of the connection's phase, failure and native code, so a
        // run that fails between steps is placed in time.
        val timeline = JSONArray()
        val started = System.currentTimeMillis()
        val watching = java.util.concurrent.atomic.AtomicBoolean(true)
        val watcher = Thread {
            var last = ""
            while (watching.get()) {
                val state = app.connections.state.value
                val now = "${state.phase}/${state.failure}/${state.nativeCode}/${calls.view.value.phase}"
                if (now != last) {
                    synchronized(timeline) {
                        timeline.put(JSONObject().put("ms", System.currentTimeMillis() - started).put("connection", state.phase.name)
                            .put("failure", state.failure.name).put("native_code", state.nativeCode).put("call", calls.view.value.phase.name))
                    }
                    last = now
                }
                Thread.sleep(10)
            }
        }.apply { isDaemon = true; start() }
        fun mark(name: String) = synchronized(timeline) {
            timeline.put(JSONObject().put("ms", System.currentTimeMillis() - started).put("step", name))
        }
        fun ended(timeout: Long = 30_000) = await(timeout) { calls.view.value.phase == CallPhase.Ended }
        // No owner count may stay above its value before the step. A run that
        // failed meanwhile releases its owners too, which is not a leak.
        fun record(name: String, before: LongArray, settled: LongArray = before) {
            val view = calls.view.value
            // Owners are released after the view shows Ended, so wait for them.
            val deadline = System.currentTimeMillis() + 10_000
            while (!released(settled) && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val connection = app.connections.state.value
            steps.put(name, JSONObject().put("phase", view.phase.name).put("result", view.result)
                .put("session_dropped", connection.phase == ConnectionPhase.Failed)
                .put("connection_failure", connection.failure.name).put("connection_native_code", connection.nativeCode)
                .put("start_failure", view.startFailure?.name ?: JSONObject.NULL).put("failure_code", view.failureCode)
                .put("local_end", view.localEnd).put("remote_end", view.remoteEnd).put("attributed", view.endAttributed)
                .put("invitation_cleared", view.invitation == null)
                .put("handles_before", JSONArray(before.toList())).put("handles_after", JSONArray(owners().toList()))
                .put("handles_restored", released(settled)))
            // Dismiss waits for the attempt to close its lane.
            calls.dismiss()
            await(10_000) { calls.view.value.phase == CallPhase.Idle }
        }

        // 1. Cancel at once: the attempt may not have reached its lane yet.
        mark("cancel_before_lane")
        var before = owners()
        calls.call(key)
        calls.cancel()
        ended()
        record("cancel_before_lane", before)

        // Each later step starts on a fresh run. A YUME session can end after a
        // canceled call stream closes (SESSION_DROP_BRIEF.md); the steps record
        // that separately instead of failing the next step over it.
        fun freshRun() {
            if (app.connections.state.value.phase == ConnectionPhase.Connected) {
                app.connections.stop()
                await(30_000) { app.connections.state.value.phase.let { it == ConnectionPhase.Idle || it == ConnectionPhase.Failed } }
            }
            reconnect()
        }

        // 2. Cancel while the lane runs and the invitation is on screen.
        freshRun()
        mark("cancel_during_lane")
        before = owners()
        calls.call(key)
        await(60_000) { calls.view.value.invitation != null || calls.view.value.phase == CallPhase.Ended }
        val inviting = calls.view.value.phase == CallPhase.Inviting
        val shown = calls.view.value.invitation
        calls.cancel()
        ended()
        record("cancel_during_lane", before)
        steps.getJSONObject("cancel_during_lane").put("was_inviting", inviting)
            .put("invitation_wiped", shown != null && shown.all { it == 0.toByte() })

        // 3. Stop in the middle of a call. The CLI answers the invitation.
        freshRun()
        mark("stop_mid_call")
        before = owners()
        calls.call(key, config.optInt("lifetime", 300))
        await(60_000) { calls.view.value.invitation != null || calls.view.value.phase == CallPhase.Ended }
        if (calls.view.value.invitation == null) {
            // The run refused the call's stream. Record what the app saw and
            // how long until a new stream opens, then end the scenario failed.
            val refusedAt = System.currentTimeMillis()
            val phase = app.connections.state.value.phase.name
            record("stream_refused", before)
            steps.getJSONObject("stream_refused").put("connection_phase", phase)
            val probes = JSONArray()
            while (System.currentTimeMillis() - refusedAt < 20_000) {
                Thread.sleep(1000)
                calls.call(key)
                await(30_000) { calls.view.value.invitation != null || calls.view.value.phase == CallPhase.Ended }
                val opened = calls.view.value.invitation != null
                probes.put(JSONObject().put("ms", System.currentTimeMillis() - refusedAt).put("opened", opened)
                    .put("failure_code", calls.view.value.failureCode)
                    .put("connection_phase", app.connections.state.value.phase.name))
                calls.cancel()
                ended()
                calls.dismiss()
                await(10_000) { calls.view.value.phase == CallPhase.Idle }
                if (opened) break
            }
            watching.set(false)
            watcher.join()
            write("result.json", JSONObject().put("role", "interrupts").put("steps", steps)
                .put("stream_refused_probes", probes).put("timeline", timeline).put("component", CallNative.componentVersion()))
            return
        }
        val invitation = requireNotNull(calls.view.value.invitation)
        val secureScreen = invitationScreenIsSecure()
        File(handoff, "invitation.bin.part").writeBytes(invitation)
        check(File(handoff, "invitation.bin.part").renameTo(File(handoff, "invitation.bin")))
        await(90_000) { calls.view.value.phase == CallPhase.Media || calls.view.value.phase == CallPhase.Ended }
        val reachedMedia = calls.view.value.phase == CallPhase.Media
        app.connections.stop()
        ended()
        await(30_000) { app.connections.state.value.phase != ConnectionPhase.Connected }
        // Stop also releases the run, so compare with the owners before Connect.
        record("stop_mid_call", before, idleBaseline)
        steps.getJSONObject("stop_mid_call").put("reached_media", reachedMedia)
            .put("invitation_wiped", invitation.all { it == 0.toByte() })

        // 4. A lane asked for after Stop is refused at once.
        before = owners()
        calls.call(key)
        ended(10_000)
        record("lane_after_stop", before)

        // 5. Stop racing a new attempt: the lane is refused or ended, never left waiting.
        freshRun()
        before = owners()
        calls.call(key)
        app.connections.stop()
        ended(15_000)
        await(30_000) { app.connections.state.value.phase != ConnectionPhase.Connected }
        record("stop_racing_lane", before, idleBaseline)

        watching.set(false)
        watcher.join()
        write("result.json", JSONObject().put("role", "interrupts").put("steps", steps).put("timeline", timeline)
            .put("invitation_screen_secure", secureScreen)
            .put("component", CallNative.componentVersion()))
    }

    private var idleBaseline = LongArray(0)

    /** Transport owners, then the call core's contexts, stores, invitations and calls. */
    private fun owners() = ItoNative.liveHandles() + CallNative.liveHandles()

    private fun released(settled: LongArray) =
        owners().withIndex().all { (index, count) -> count <= settled[index] }

    private fun awaitReleased(settled: LongArray): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (!released(settled) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return released(settled)
    }

    /**
     * After the process died, this installation is handed the invitation it
     * answered in its previous process. The persisted spent record refuses
     * it before anything is sent to the relay.
     */
    private fun replay() {
        val calls = app.calls
        val before = owners()
        val invitation = awaitFile("invitation.bin", 120_000)
        val bytes = invitation.readBytes()
        invitation.delete()
        calls.answer(bytes)
        await(60_000) { calls.view.value.phase == CallPhase.Ended }
        val view = calls.view.value
        val restored = awaitReleased(before)
        val connection = app.connections.state.value
        write("result.json", JSONObject().put("role", "replay").put("result", view.result)
            .put("start_failure", view.startFailure?.name ?: JSONObject.NULL).put("failure_code", view.failureCode)
            .put("invitation_wiped", bytes.all { it == 0.toByte() }).put("invitation_cleared", view.invitation == null)
            .put("handles_before", JSONArray(before.toList())).put("handles_after", JSONArray(owners().toList()))
            .put("handles_restored", restored).put("connection_phase", connection.phase.name)
            .put("component", CallNative.componentVersion()))
        calls.dismiss()
        await(10_000) { calls.view.value.phase == CallPhase.Idle }
        app.connections.stop()
        await(30_000) { app.connections.state.value.phase != ConnectionPhase.Connected }
    }

    /**
     * The host stops yumed in the middle of a call, so the transport goes
     * away under the run. Nothing here presses Stop before the call ends.
     */
    private fun transportLoss(key: ByteArray) {
        val calls = app.calls
        calls.call(key, config.optInt("lifetime", CallController.DEFAULT_LIFETIME))
        await(60_000) { calls.view.value.invitation != null || calls.view.value.phase == CallPhase.Ended }
        val invitation = requireNotNull(calls.view.value.invitation) { "no invitation: ${calls.view.value}" }
        File(handoff, "invitation.bin.part").writeBytes(invitation)
        check(File(handoff, "invitation.bin.part").renameTo(File(handoff, "invitation.bin")))
        await(90_000) { calls.view.value.phase == CallPhase.Media || calls.view.value.phase == CallPhase.Ended }
        val reachedMedia = calls.view.value.phase == CallPhase.Media
        write("media.json", JSONObject().put("reached_media", reachedMedia))
        await(90_000) { calls.view.value.phase == CallPhase.Ended }
        val view = calls.view.value
        val wiped = invitation.all { it == 0.toByte() }
        val stoppedBeforeEnd = app.connections.state.value.phase == ConnectionPhase.Idle
        val deadline = System.currentTimeMillis() + 30_000
        while (app.connections.state.value.phase == ConnectionPhase.Connected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        val connection = app.connections.state.value
        // The run ended with its transport, so its owners go too.
        val restored = awaitReleased(idleBaseline)
        write("result.json", JSONObject().put("role", "transport_loss").put("result", view.result)
            .put("local_end", view.localEnd).put("remote_end", view.remoteEnd).put("reached_media", reachedMedia)
            .put("stopped_before_end", stoppedBeforeEnd).put("invitation_wiped", wiped)
            .put("connection_phase", connection.phase.name).put("connection_failure", connection.failure.name)
            .put("connection_native_code", connection.nativeCode)
            .put("handles_after", JSONArray(owners().toList()))
            .put("idle_baseline", JSONArray(idleBaseline.toList())).put("handles_restored", restored)
            .put("component", CallNative.componentVersion()))
        if (app.connections.state.value.phase == ConnectionPhase.Connected) app.connections.stop()
    }

    /** The real Calls tab shows the invitation as a QR code with FLAG_SECURE on the window. */
    private fun invitationScreenIsSecure(): Boolean {
        compose.onNode(hasText(text(R.string.calls)) and isSelectable()).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(text(R.string.invitation_qr))).fetchSemanticsNodes().isNotEmpty()
        }
        var flags = 0
        instrumentation.runOnMainSync { flags = compose.activity.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun reconnect() {
        val controller = app.connections
        await(30_000) { controller.state.value.phase == ConnectionPhase.Idle || controller.state.value.phase == ConnectionPhase.Failed }
        compose.onNode(hasText(text(R.string.connections)) and isSelectable()).performClick()
        compose.waitForIdle()
        click(text(R.string.connect_transport))
        await(60_000) { controller.state.value.phase == ConnectionPhase.Connected || controller.state.value.phase == ConnectionPhase.Failed }
        assertEquals(ConnectionPhase.Connected, controller.state.value.phase)
    }

    private fun importAndConnect() {
        val controller = app.connections
        await(30_000) { controller.state.value.phase == ConnectionPhase.Idle }
        idleBaseline = owners()
        val file = config.getString("kit_file")
        check(file.matches(Regex("ito-i4b-[a-z0-9-]+\\.kit")))
        // The app opens on Calls. Import, selection and Connect are on the Service page.
        compose.onNode(hasText(text(R.string.connections)) and isSelectable()).performClick()
        compose.waitForIdle()
        // A restarted process keeps the configuration it imported before.
        if (controller.state.value.catalog.entries.none { it.label == "I4b call" }) importKit(file)
        click("I4b call")
        val choice = controller.state.value.networks.first { it.wifi }
        click(context.getString(R.string.network_choice, choice.handle,
            text(if (choice.wifi) R.string.network_wifi else R.string.network_other),
            text(if (choice.metered) R.string.network_metered else R.string.network_unmetered)))
        await(10_000) { controller.state.value.selectedNetwork == choice.handle }
        click(text(R.string.connect_transport))
        await(60_000) { controller.state.value.phase == ConnectionPhase.Connected || controller.state.value.phase == ConnectionPhase.Failed }
        assertEquals(ConnectionPhase.Connected, controller.state.value.phase)
        if (config.has("expected_peer")) assertEquals(config.getString("expected_peer"), controller.state.value.peer)
    }

    private fun importKit(file: String) {
        val controller = app.connections
        click(text(R.string.import_configuration))
        val documentPackage = "com.google.android.documentsui"
        check(device.wait(Until.hasObject(By.pkg(documentPackage)), 10_000) ||
            device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 1000)) { "picker" }
        if (device.wait(Until.findObject(By.text(file)), 1500) == null) {
            (device.findObject(By.desc("Show roots")) ?: device.findObject(By.desc("Open navigation drawer")))
                ?.click() ?: throw AssertionError("picker roots")
            clickPicker(By.text("Downloads"), "Downloads")
        }
        clickPicker(By.text(file), "fixture document")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text(R.string.configuration_name)) and hasSetTextAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(text(R.string.configuration_name)) and hasSetTextAction())
            .performTextReplacement("I4b call")
        try {
            compose.onNode(hasText(text(R.string.opening_code)) and hasSetTextAction())
                .performTextReplacement(config.getString("code"))
        } catch (_: AssertionError) { throw AssertionError("opening code entry") }
        click(text(R.string.import_action))
        await(30_000) { controller.state.value.catalog.entries.any { it.label == "I4b call" } }
    }

    private fun exists(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Activates a screen's button or row as TalkBack does, after scrolling to it. */
    private fun press(value: String) {
        val matcher = hasText(value) and hasClickAction()
        compose.waitUntil(10_000) { exists(matcher) }
        if (exists(hasScrollAction())) compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        compose.onNode(matcher).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun click(value: String) {
        val matcher = hasText(value) and hasClickAction()
        if (compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(matcher).performScrollTo()
        }
        compose.onNode(matcher).performClick()
    }

    private fun text(resource: Int) = context.getString(resource)

    /** The picker rebuilds its drawer and list while they settle, so a stale object is looked up again. */
    private fun clickPicker(selector: BySelector, what: String) {
        for (attempt in 1..3) {
            val target = device.wait(Until.findObject(selector), 10_000) ?: throw AssertionError(what)
            try {
                target.click()
                return
            } catch (stale: StaleObjectException) {
                if (attempt == 3) throw stale
                device.waitForIdle()
            }
        }
    }

    private fun awaitFile(name: String, timeout: Long): File {
        val file = File(handoff, name)
        await(timeout) { file.isFile }
        return file
    }

    private fun await(timeout: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(50)
        }
    }

    private fun write(name: String, value: JSONObject) {
        if (config.has("nonce")) value.put("nonce", config.getString("nonce"))
        val part = File(handoff, "$name.part")
        part.writeText(value.toString(2))
        check(part.renameTo(File(handoff, name)))
    }
}
