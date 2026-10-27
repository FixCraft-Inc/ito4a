package com.fixcraft.ito.transport

import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.MainActivity
import com.fixcraft.ito.R
import com.fixcraft.ito.transport.nativebridge.ItoNative
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** The ordinary instrumentation suite excludes this host-owned fixture gate. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresI1Fixture

/** UI/document/data acceptance. A host independently correlates every payload receipt. */
@RequiresI1Fixture
@RunWith(AndroidJUnit4::class)
class I1LiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val failureCapture = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (handoff.isDirectory) {
                stateEvidence().put("stage", stage).put("test", description.methodName)
                    .also { write("failure.json", it) }
                device.takeScreenshot(File(handoff, "failure.png"))
            }
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val controller get() = (context.applicationContext as ItoApplication).connections
    private val state get() = controller.state.value
    private val handoff = File(context.noBackupFilesDir, "i1-fixtures")
    private val config by lazy {
        check(InstrumentationRegistry.getArguments().getString("i1Live") == "true") {
            "I1 fixture tests require explicit host selection"
        }
        val file = File(handoff, "config.json")
        check(file.isFile && file.length() in 1..16_384) { "Missing bounded I1 fixture metadata" }
        JSONObject(file.readText()).also {
            check(it.getString("expected_peer").matches(Regex("[0-9a-f]{64}")))
            check(it.getString("host") == "127.0.0.1")
            check(it.getInt("tcp_port") in 1..65535 && it.getInt("udp_port") in 1..65535)
        }
    }
    private val receiptEvidence = JSONArray()
    private val checks = JSONArray()
    private var stage = "entry"
    private var selectedNetwork = 0L
    private var selectedWifi = false

    @Test fun manualFlow() {
        config
        mark("clean-profile")
        awaitIdle()
        assertTrue("Host must clear the Ito qualification profile", state.catalog.entries.isEmpty())
        assertNull(state.catalog.selectedId)
        assertStoppedOwners()
        assertIndependentApplication()
        navigation()

        mark("wrong-opening-code")
        val code = config.getString("code")
        val wrongCode = (if (code.first() == '0') "1" else "0") + code.drop(1)
        importDocument(MANUAL_FILE, "Rejected opening code", wrongCode, succeeds = false)
        assertEquals(ConnectionFailure.Native, state.failure)
        assertEquals(-8, state.nativeCode)
        assertTrue(state.catalog.entries.isEmpty())
        assertStoppedOwners()
        checks.put("wrong opening code refuses publication")
        stop()

        mark("manual-document-import")
        val manual = importDocument(MANUAL_FILE, MANUAL_LABEL, code)
        assertNull("Import must not choose a configuration", state.catalog.selectedId)
        select(MANUAL_LABEL, manual)
        chooseNetwork()
        connect(manual)
        testData("manual-initial")
        checks.put("actual document picker, explicit selection, authenticated stream and packet data")
        rotationAndBackground(manual)

        mark("stop-and-reconnect")
        val firstGeneration = state.generation
        stop()
        assertEquals(manual, state.catalog.selectedId)
        connect(manual)
        assertTrue(state.generation > firstGeneration)
        testData("manual-reconnect")
        networkLossControl(manual)
        stop()
        checks.put("stop and explicit reconnect release old owners")

        mark("stale-selected-network")
        instrumentation.runOnMainSync { controller.selectNetwork(Long.MAX_VALUE) }
        await { state.phase == ConnectionPhase.Idle && state.selectedNetwork == Long.MAX_VALUE }
        val staleGeneration = state.generation
        val staleReceipts = state.receipts.toList()
        click(text(R.string.connect_transport))
        await { state.phase == ConnectionPhase.Failed }
        assertEquals(ConnectionFailure.NetworkUnavailable, state.failure)
        assertEquals(Long.MAX_VALUE, state.selectedNetwork)
        assertEquals(staleGeneration, state.generation)
        assertEquals(staleReceipts, state.receipts)
        assertEquals(manual, state.catalog.selectedId)
        assertStoppedOwners()
        write("stale-network.json", stateEvidence().put("no_fallback", true)
            .put("control_boundary", "controller explicit unavailable Network handle"))
        stop()
        chooseNetwork()
        checks.put("unavailable explicit Network refuses instead of using current Network")

        for ((key, label) in listOf("bad_psk" to "Invalid credentials I1", "wrong_peer" to "Identity mismatch I1")) {
            mark(key)
            val fixture = config.getJSONObject(key)
            val id = importDocument(fixture.getString("file"), label, fixture.getString("code"))
            select(label, id)
            val attemptedGeneration = state.generation
            click(text(R.string.connect_transport))
            await { state.phase == ConnectionPhase.Failed }
            assertEquals(ConnectionFailure.Native, state.failure)
            assertTrue("A refusal must retain a typed native failure", state.nativeCode < 0)
            assertTrue("The selected Network must have bound the attempted socket", state.boundSockets > 0)
            assertTrue(state.generation > attemptedGeneration)
            assertEquals(id, state.catalog.selectedId)
            assertTrue(state.receipts.isEmpty())
            assertStoppedOwners()
            write("$key.json", stateEvidence().put("no_fallback", true))
            stop()
            checks.put("$key refuses without data or fallback")
        }

        mark("unauthorized-destination")
        select(MANUAL_LABEL, manual)
        connect(manual)
        val before = state.receipts.size
        setField(R.string.test_destination, config.getString("blocked_host"))
        setField(R.string.test_port, config.optInt("blocked_port", config.getInt("tcp_port")).toString())
        setField(R.string.stream_service, config.optString("stream_service", "tcp"))
        click(text(R.string.test_stream))
        await { state.phase == ConnectionPhase.Failed }
        assertEquals(ConnectionFailure.Native, state.failure)
        assertTrue(state.nativeCode < 0)
        assertEquals(before, state.receipts.size)
        assertEquals(manual, state.catalog.selectedId)
        assertStoppedOwners()
        write("blocked-destination.json", stateEvidence().put("no_fallback", true))
        stop()
        checks.put("server unauthorized destination refuses without data or direct fallback")

        mark("cancel-starting")
        val stall = config.getJSONObject("stall")
        val stalled = importDocument(stall.getString("file"), "Cancellation I1", stall.getString("code"))
        select("Cancellation I1", stalled)
        click(text(R.string.connect_transport))
        await { state.phase == ConnectionPhase.Starting && ItoNative.liveHandles()[2] == 1L }
        val cancellationStart = SystemClock.elapsedRealtime()
        click(text(R.string.stop_transport))
        await(10_000) { state.phase == ConnectionPhase.Idle }
        val cancellationElapsed = SystemClock.elapsedRealtime() - cancellationStart
        assertEquals(ConnectionFailure.Cancelled, state.failure)
        assertEquals(ItoNative.CANCELLED, state.nativeCode)
        assertTrue(cancellationElapsed < 10_000)
        assertEquals(stalled, state.catalog.selectedId)
        assertTrue(state.receipts.isEmpty())
        assertStoppedOwners()
        write("cancellation.json", stateEvidence().put("elapsed_ms", cancellationElapsed))
        checks.put("starting cancellation has typed result and bounded owner cleanup")

        mark("manual-remains-independent")
        select(MANUAL_LABEL, manual)
        chooseNetwork()
        connect(manual)
        testData("manual-after-refusals")
        storageRecoveryControls(manual)
        stop()
        assertIndependentApplication()
        write("manual-result.json", stateEvidence().put("passed", true).put("checks", checks)
            .put("receipts", receiptEvidence).put("manual_id", manual))
    }

    /** Expected to be killed by the host after crash-ready.json, never counted as a pass. */
    @Test fun crashPhase() {
        config
        mark("crash-phase-start")
        awaitIdle()
        assertStoppedOwners()
        val previous = JSONObject(File(handoff, "manual-result.json").readText())
        assertTrue(previous.getBoolean("passed"))
        val id = previous.getString("manual_id")
        assertEquals(id, state.catalog.selectedId)
        navigation()
        chooseNetwork()
        connect(id)
        testData("before-process-death")
        write("crash-ready.json", stateEvidence().put("ready", true).put("receipts", receiptEvidence))
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        throw AssertionError("Host did not kill the prepared Ito process before its bounded deadline")
    }

    @Test fun afterProcessDeath() {
        config
        mark("after-process-death")
        val predecessor = JSONObject(File(handoff, "crash-ready.json").readText())
        assertTrue(predecessor.getBoolean("ready"))
        assertNotEquals(predecessor.getInt("pid"), Process.myPid())
        awaitIdle()
        assertStoppedOwners()
        assertTrue(state.receipts.isEmpty())
        val id = predecessor.getString("selected")
        assertEquals(id, state.catalog.selectedId)
        assertEquals(MANUAL_LABEL, state.catalog.entries.single { it.id == id }.label)
        navigation()
        chooseNetwork()
        connect(id)
        testData("after-process-death")
        stop()
        assertIndependentApplication()
        write("restart-result.json", stateEvidence().put("passed", true)
            .put("previous_pid", predecessor.getInt("pid")).put("receipts", receiptEvidence))
    }

    private fun navigation() {
        compose.onNode(hasText(text(R.string.connections)) and isSelectable()).performClick()
        compose.waitForIdle()
    }

    private fun importDocument(file: String, label: String, code: String, succeeds: Boolean = true): String {
        check(file.matches(Regex("ito-i1-[A-Za-z0-9-]+\\.kit"))) { "Unscoped fixture document" }
        val count = state.catalog.entries.size
        click(text(R.string.import_configuration))
        val documentPackage = "com.google.android.documentsui"
        val pickerVisible = device.wait(Until.hasObject(By.pkg(documentPackage)), 10_000) ||
            device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 1000)
        check(pickerVisible) { "System document picker did not open" }
        var document = device.wait(Until.findObject(By.text(file)), 1500)
        if (document == null) {
            val roots = device.findObject(By.desc("Show roots"))
                ?: device.findObject(By.desc("Open navigation drawer"))
                ?: throw AssertionError("System picker roots control unavailable")
            roots.click()
            val downloads = device.wait(Until.findObject(By.text("Downloads")), 5000)
                ?: throw AssertionError("System Downloads root unavailable")
            downloads.click()
            document = device.wait(Until.findObject(By.text(file)), 10_000)
        }
        checkNotNull(document) { "Fresh fixture absent from document picker" }.click()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text(R.string.configuration_name)) and hasSetTextAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        setField(R.string.configuration_name, label)
        // Do not print semantics or propagate an action error containing the
        // secret opening code into instrumentation output.
        try {
            compose.onNode(hasText(text(R.string.opening_code)) and hasSetTextAction())
                .performTextReplacement(code)
        } catch (_: AssertionError) {
            throw AssertionError("Opening-code entry failed")
        }
        click(text(R.string.import_action))
        if (!succeeds) {
            await { state.phase == ConnectionPhase.Failed }
            assertEquals(count, state.catalog.entries.size)
            return ""
        }
        await { state.catalog.entries.size == count + 1 && state.phase == ConnectionPhase.Idle }
        return state.catalog.entries.single { it.label == label }.id
    }

    private fun select(label: String, id: String) {
        click(label)
        await { state.catalog.selectedId == id && state.phase == ConnectionPhase.Idle }
        assertStoppedOwners()
    }

    private fun chooseNetwork() {
        val choice = if (config.has("network")) state.networks.single { it.handle == config.getLong("network") }
        else state.networks.firstOrNull { it.wifi } ?: throw AssertionError("Qualification requires an explicit non-VPN Wi-Fi Network")
        val label = context.getString(R.string.network_choice, choice.handle,
            text(if (choice.wifi) R.string.network_wifi else R.string.network_other),
            text(if (choice.metered) R.string.network_metered else R.string.network_unmetered))
        click(label)
        await { state.selectedNetwork == choice.handle && state.phase == ConnectionPhase.Idle }
        selectedNetwork = choice.handle
        selectedWifi = choice.wifi
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.allNetworks.single { it.networkHandle == selectedNetwork }
        val capabilities = requireNotNull(manager.getNetworkCapabilities(network))
        assertTrue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertFalse(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }

    private fun connect(id: String) {
        click(text(R.string.connect_transport))
        await { state.phase == ConnectionPhase.Connected || state.phase == ConnectionPhase.Failed }
        assertEquals("Authenticated connection required", ConnectionPhase.Connected, state.phase)
        assertEquals(ConnectionFailure.None, state.failure)
        assertEquals(0, state.nativeCode)
        assertEquals(id, state.catalog.selectedId)
        assertEquals(selectedNetwork, requireNotNull(state.selectedNetwork))
        assertEquals(config.getString("expected_peer"), state.peer)
        assertTrue(state.boundSockets > 0)
        if (config.has("expected_local")) assertEquals(config.getString("expected_local"), state.localIdentity)
        assertArrayEquals(longArrayOf(1, 1, 1, 0, 1, 0, 0), ItoNative.liveHandles())
    }

    private fun testData(label: String) {
        val id = state.catalog.selectedId
        val generation = state.generation
        for ((kind, size, port, button) in listOf(
            ProbeCase("tcp", 65_536, config.getInt("tcp_port"), R.string.test_stream),
            ProbeCase("udp", 1024, config.getInt("udp_port"), R.string.test_packet),
        )) {
            mark("$label-$kind")
            val count = state.receipts.size
            setField(R.string.test_destination, config.getString("host"))
            setField(R.string.test_port, port.toString())
            setField(R.string.stream_service, config.optString("stream_service", "tcp"))
            setField(R.string.packet_service, config.optString("packet_service", "udp"))
            click(text(button))
            await { state.receipts.size == count + 1 || state.phase == ConnectionPhase.Failed }
            assertEquals(ConnectionPhase.Connected, state.phase)
            assertEquals(count + 1, state.receipts.size)
            val receipt = state.receipts.last()
            assertEquals(kind, receipt.kind)
            assertEquals(size, receipt.bytes)
            assertTrue(receipt.nativeBytesSent >= size)
            assertTrue(receipt.nativeBytesReceived >= size)
            assertTrue(receipt.sha256.matches(Regex("[0-9a-f]{64}")))
            assertEquals(config.getString("expected_peer"), receipt.peer)
            assertEquals(generation, receipt.generation)
            assertEquals(selectedNetwork, receipt.network)
            assertEquals(id, state.catalog.selectedId)
            assertArrayEquals(longArrayOf(1, 1, 1, 0, 1, 0, 0), ItoNative.liveHandles())
            receiptEvidence.put(JSONObject().put("label", label).put("kind", kind).put("bytes", size)
                .put("sha256", receipt.sha256).put("peer", receipt.peer).put("local", state.localIdentity)
                .put("generation", generation).put("network", receipt.network).put("selected", id)
                .put("bound_sockets", state.boundSockets).put("native_bytes_sent", receipt.nativeBytesSent)
                .put("native_bytes_received", receipt.nativeBytesReceived))
            write("traffic-receipts.json", JSONObject().put("receipts", receiptEvidence))
        }
    }

    private fun stop() {
        if (state.phase in setOf(ConnectionPhase.Starting, ConnectionPhase.Connected, ConnectionPhase.Failed)) {
            click(text(R.string.stop_transport))
        }
        awaitIdle()
        assertStoppedOwners()
    }

    private fun rotationAndBackground(id: String) {
        mark("rotation-retains-run")
        val originalController = controller
        val originalGeneration = state.generation
        val originalReceipts = state.receipts.toList()
        assertEquals(Configuration.ORIENTATION_PORTRAIT, context.resources.configuration.orientation)
        try {
            device.setOrientationLeft()
            await { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            assertSame(originalController, controller)
            assertEquals(ConnectionPhase.Connected, state.phase)
            assertEquals(originalGeneration, state.generation)
            assertEquals(id, state.catalog.selectedId)
            assertEquals(originalReceipts, state.receipts)
            assertArrayEquals(longArrayOf(1, 1, 1, 0, 1, 0, 0), ItoNative.liveHandles())
        } finally {
            device.setOrientationNatural()
        }
        await { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.waitForIdle()
        assertSame(originalController, controller)
        assertEquals(ConnectionPhase.Connected, state.phase)
        assertEquals(originalGeneration, state.generation)
        navigation()
        testData("manual-after-rotation")
        val rotation = stateEvidence().put("controller_retained", true)
            .put("landscape_and_portrait", true).put("generation_unchanged", true)
        checks.put("actual landscape and portrait rotation retain exact process controller and run")

        mark("background-stops-run")
        assertTrue(device.pressHome())
        awaitIdle()
        assertStoppedOwners()
        assertEquals(id, state.catalog.selectedId)
        assertEquals(originalGeneration, state.generation)
        val stopped = stateEvidence()
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text(R.string.connections)) and isSelectable())
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertSame(originalController, controller)
        assertEquals(ConnectionPhase.Idle, state.phase)
        assertEquals(originalGeneration, state.generation)
        assertStoppedOwners()
        navigation()
        connect(id)
        assertTrue(state.generation > originalGeneration)
        testData("manual-after-background")
        write("lifecycle.json", JSONObject().put("passed", true).put("rotation", rotation)
            .put("background_stopped", stopped).put("resume_did_not_auto_connect", true)
            .put("explicit_reconnect", stateEvidence()))
        checks.put("Home stops native owners and resume waits for explicit reconnect")
    }

    private fun storageRecoveryControls(id: String) {
        mark("completed-run-storage-recovery")
        val snapshot = File(context.noBackupFilesDir, "ito-transport/snapshot.bin")
        val encryptedBefore = snapshot.readBytes()
        val oldRunObstruction = obstructSessionCleanup()
        try {
            // The server refusal ends execute(), whose real session cleanup
            // must override the transport failure and latch Storage.
            setField(R.string.test_destination, config.getString("blocked_host"))
            setField(R.string.test_port, config.optInt("blocked_port", config.getInt("tcp_port")).toString())
            setField(R.string.stream_service, config.optString("stream_service", "tcp"))
            click(text(R.string.test_stream))
            awaitStorageFailure()
            write("completed-run-cleanup-failure.json", stateEvidence().put("passed", true))
        } finally {
            assertTrue(oldRunObstruction.delete())
        }
        click(text(R.string.refresh_networks))
        awaitIdle()
        assertStoppedOwners()
        assertEquals(id, state.catalog.selectedId)
        assertArrayEquals(encryptedBefore, snapshot.readBytes())
        val recovered = stateEvidence().put("single_explicit_refresh", true)

        mark("fresh-stop-storage-failure")
        connect(id)
        val freshObstruction = obstructSessionCleanup()
        try {
            lateinit var refresh: Job
            instrumentation.runOnMainSync { refresh = controller.refresh() }
            await { refresh.isCompleted }
            awaitStorageFailure()
            assertArrayEquals(encryptedBefore, snapshot.readBytes())
            write("fresh-stop-cleanup-failure.json", stateEvidence().put("passed", true))
        } finally {
            assertTrue(freshObstruction.delete())
        }
        click(text(R.string.refresh_networks))
        awaitIdle()
        assertStoppedOwners()
        assertEquals(id, state.catalog.selectedId)
        assertArrayEquals(encryptedBefore, snapshot.readBytes())
        write("storage-recovery.json", JSONObject().put("passed", true)
            .put("completed_run_recovered", recovered).put("fresh_failure_requires_new_action", true)
            .put("final", stateEvidence()))
        checks.put("old cleanup failure recovers in one explicit refresh; a fresh stop cleanup error stays failed")
    }

    private fun obstructSessionCleanup(): File {
        val sessions = File(context.noBackupFilesDir, "ito-transport/sessions").listFiles().orEmpty()
        assertEquals(1, sessions.size)
        val obstruction = File(sessions.single(), "i1-owned-cleanup-bound-test")
        assertTrue(obstruction.createNewFile())
        RandomAccessFile(obstruction, "rw").use { it.setLength(1024L * 1024 + 1) }
        return obstruction
    }

    private fun awaitStorageFailure() {
        await {
            state.phase == ConnectionPhase.Failed && state.failure == ConnectionFailure.Storage &&
                ItoNative.liveHandles().all { it == 0L }
        }
        assertEquals(0, state.nativeCode)
        assertTrue(File(context.noBackupFilesDir, "ito-transport/sessions").listFiles().orEmpty().isNotEmpty())
    }

    private fun networkLossControl(id: String) {
        if (!selectedWifi) {
            write("network-loss.json", JSONObject().put("status", "unrun")
                .put("reason", "Selected task Network is not Wi-Fi. No unsupported radio mutation requested."))
            return
        }
        mark("drop-selected-network")
        val oldNetwork = selectedNetwork
        host("drop-selected-network")
        await { state.phase == ConnectionPhase.Failed && state.failure == ConnectionFailure.NetworkLost }
        assertEquals(oldNetwork, requireNotNull(state.selectedNetwork))
        assertEquals(id, state.catalog.selectedId)
        assertStoppedOwners()
        write("network-loss.json", stateEvidence().put("status", "passed").put("no_fallback", true))
        host("restore-selected-network")
        stop()
        click(text(R.string.refresh_networks))
        await { state.phase == ConnectionPhase.Idle && state.networks.any { it.wifi } }
        chooseNetwork()
        connect(id)
        testData("manual-after-network-return")
        checks.put("emulator selected Wi-Fi loss stops exact run without fallback")
    }

    private fun host(action: String) {
        write("$action.request.json", stateEvidence().put("action", action))
        val reply = File(handoff, "$action.ready")
        val deadline = SystemClock.elapsedRealtime() + 35_000
        while (!reply.isFile && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(reply.isFile && reply.length() == 5L && reply.readText() == "ready") { "Host action did not complete" }
        check(reply.delete())
    }

    private fun assertStoppedOwners() {
        await(10_000) {
            ItoNative.liveHandles().all { it == 0L } &&
                File(context.noBackupFilesDir, "ito-transport/sessions").listFiles().orEmpty().isEmpty()
        }
        assertArrayEquals(LongArray(7), ItoNative.liveHandles())
    }

    private fun assertIndependentApplication() {
        assertEquals("", device.executeShellCommand("pm list packages com.fixcraft.yume").trim())
        @Suppress("DEPRECATION")
        val application = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        assertFalse(application.requestedPermissions.orEmpty().contains("android.permission.BIND_VPN_SERVICE"))
        assertTrue(application.services.orEmpty().none { it.permission == "android.permission.BIND_VPN_SERVICE" })
        assertFalse(device.hasObject(By.text("Connection request")))
    }

    private fun click(value: String) = visible(hasText(value) and hasClickAction()).performClick()

    private fun setField(resource: Int, value: String) {
        visible((hasText(text(resource)) or hasContentDescription(text(resource))) and hasSetTextAction())
            .performTextReplacement(value)
    }

    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val target = compose.onNode(matcher)
        val inScroll = matcher and hasAnyAncestor(hasScrollAction())
        if (compose.onAllNodes(inScroll).fetchSemanticsNodes().isNotEmpty()) {
            target.performScrollTo()
            val scroll = compose.onNode(hasScrollAction())
            repeat(12) {
                val viewport = scroll.fetchSemanticsNode().boundsInRoot
                val node = target.fetchSemanticsNode()
                val top = node.positionInRoot.y
                val bottom = top + node.size.height
                if (target.isDisplayed() && top >= viewport.top && bottom <= viewport.bottom) return target
                val distance = if (top < viewport.top) top - viewport.top else bottom - viewport.bottom
                val bounded = distance.coerceIn(-viewport.height * .75f, viewport.height * .75f)
                scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, bounded) }
            }
            throw AssertionError("Control did not become fully visible")
        }
        return target.assertIsDisplayed()
    }

    private fun awaitIdle() = await { state.phase == ConnectionPhase.Idle }

    private fun await(timeout: Long = 35_000, condition: () -> Boolean) {
        compose.waitUntil(timeout) { condition() }
    }

    private fun text(resource: Int) = context.getString(resource)

    private fun mark(value: String) {
        stage = value
        write("stage.json", JSONObject().put("stage", stage))
    }

    private fun stateEvidence(): JSONObject {
        val state = state
        return JSONObject().put("pid", Process.myPid()).put("phase", state.phase.name)
            .put("failure", state.failure.name).put("native_code", state.nativeCode)
            .put("selected", state.catalog.selectedId).put("peer", state.peer)
            .put("local", state.localIdentity).put("generation", state.generation)
            .put("network", state.selectedNetwork).put("bound_sockets", state.boundSockets)
            .put("receipt_count", state.receipts.size)
            .put("handles", JSONArray(ItoNative.liveHandles().toList()))
            .put("sessions", JSONArray(File(context.noBackupFilesDir, "ito-transport/sessions")
                .listFiles().orEmpty().map { it.name }))
    }

    private fun write(name: String, value: JSONObject) {
        check(handoff.isDirectory) { "Host handoff directory is absent" }
        File(handoff, name).writeText(value.toString(2))
    }

    private data class ProbeCase(val kind: String, val size: Int, val port: Int, val button: Int)

    private companion object {
        const val MANUAL_FILE = "ito-i1-manual.kit"
        const val MANUAL_LABEL = "Manual I1"
    }
}
