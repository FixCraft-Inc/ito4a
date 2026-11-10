package com.fixcraft.ito.provider

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
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
import com.fixcraft.ito.data.PreferencesStore
import com.fixcraft.ito.transport.ConnectionFailure
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.RequiresI1Fixture
import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.yume.consumer.CodePrompt
import com.fixcraft.yume.consumer.ServiceRoute
import com.fixcraft.yume.consumer.ServiceSelection
import com.fixcraft.yume.provider.ProviderAppLinks
import com.fixcraft.yume.provider.ProviderBundleCodec
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.provider.ProviderRegistration
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** Explicit actual-browser and public-native-ABI flow; ordinary instrumentation excludes it. */
@RequiresI1Fixture
@RunWith(AndroidJUnit4::class)
class ProviderLiveIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val failureCapture = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (handoff.isDirectory) {
                write("failure.json", stateEvidence().put("stage", stage).put("test", description.methodName))
                device.takeScreenshot(File(handoff, "failure.png"))
            }
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val application get() = context.applicationContext as ItoApplication
    private val services get() = application.services
    private val controller get() = application.connections
    private val state get() = controller.state.value
    private val handoff = File(context.noBackupFilesDir, "i2-fixtures")
    private val config by lazy {
        check(InstrumentationRegistry.getArguments().getString("i2Live") == "true")
        val file = File(handoff, "config.json")
        check(file.isFile && file.length() in 1..16_384)
        JSONObject(file.readText()).also {
            check(it.getJSONObject("traffic").getString("host") == "127.0.0.1")
        }
    }
    private val alpha get() = config.getString("alpha")
    private val beta get() = config.getString("beta")
    private val traffic get() = config.getJSONObject("traffic")
    private val receipts = JSONArray()
    private val checks = JSONArray()
    private val gates = JSONObject()
    private var stage = "entry"
    private var network = 0L

    @Test fun presentationAndProvisioning() {
        config
        mark("clean-profile")
        await { services.state.value.loaded && state.phase == ConnectionPhase.Idle }
        assertTrue(services.state.value.entries.isEmpty())
        assertTrue(state.catalog.entries.isEmpty())
        independent()
        navigate()
        val preferences = runBlocking { PreferencesStore(context).preferences.first() }
        val manual = importManual()
        chooseNetwork()
        rawDirectControl()
        connect(null, manual)
        data("manual-initial", "manual")
        stop()
        val manualSnapshot = File(context.noBackupFilesDir, "ito-transport/snapshot.bin")
        val manualBefore = sha256(manualSnapshot.readBytes())

        addAndAuthorize(beta, config.getString("issuer_beta"))
        provision(beta)
        val betaInitial = selection(beta)
        host("server-controls", provider = beta, selected = betaInitial)
        assertEquals("relay", betaInitial.role)
        connect(betaInitial)
        data("beta-initial", beta)
        val betaCurrent = rotate(betaInitial)
        connect(betaCurrent)
        data("beta-rotated", beta)
        stop()

        addAndAuthorize(alpha, config.getString("issuer_alpha"))
        provision(alpha, wrongCode = true)
        assertNull(services.state.value.entries.single { it.provider == alpha }.selection)
        assertEquals(betaCurrent, selection(beta))
        provision(alpha)
        val alphaInitial = selection(alpha)
        host("server-controls", provider = alpha, selected = alphaInitial)
        connect(alphaInitial)
        data("alpha-initial", alpha)
        val originalRun = state.generation
        host("account-expired", provider = alpha, selected = alphaInitial, timeout = 100_000)
        data("alpha-after-api-expiry", alpha)
        assertEquals(originalRun, state.generation)
        val catalog = services.state.value.catalogs[alpha]
        within(alpha, R.string.provider_servers)
        await { services.state.value.busy.isEmpty() &&
            (services.state.value.catalogs[alpha] != catalog || services.state.value.error != null) }
        assertNull(services.state.value.error)
        assertEquals(alphaInitial.provider, services.state.value.selection?.provider)
        assertEquals(originalRun, state.generation)
        data("alpha-after-api-refresh", alpha)
        checks.put("API expiry and refresh preserve the separate live transport lease")
        gates.put("api_refresh", true)
        val alphaCurrent = rotate(alphaInitial)
        gates.put("credential_rotation", true)
        assertEquals(betaCurrent, selection(beta))
        connect(alphaCurrent)
        data("alpha-rotated", alpha)

        mark("revoke-live-alpha")
        assertEquals(ConnectionPhase.Connected, state.phase)
        val revokedGeneration = state.generation
        host("revoke-alpha", provider = alpha, selected = alphaCurrent)
        await { state.phase == ConnectionPhase.Failed }
        stoppedOwners()
        assertEquals(revokedGeneration, state.generation)
        assertEquals(alphaCurrent.provider, services.state.value.selection?.provider)
        assertTrue(state.failure in setOf(ConnectionFailure.Native, ConnectionFailure.Provider))
        write("revocation.json", stateEvidence().put("live_endpoint_closed", true))
        stop()
        click(R.string.connect_transport)
        await { state.phase == ConnectionPhase.Failed }
        assertTrue(state.failure in setOf(ConnectionFailure.Native, ConnectionFailure.Provider))
        assertTrue(state.receipts.isEmpty())
        stoppedOwners()
        assertEquals(alphaCurrent.provider, services.state.value.selection?.provider)
        write("revoked-readmission.json", stateEvidence().put("no_fallback", true))
        stop()
        remove(alpha)
        assertEquals(betaCurrent, selection(beta))
        assertEquals(manualBefore, sha256(manualSnapshot.readBytes()))
        assertEquals(preferences, runBlocking { PreferencesStore(context).preferences.first() })
        checks.put("server revocation closes the live native endpoint and refuses explicit readmission")
        gates.put("live_revocation", true)

        connect(betaCurrent)
        data("beta-after-alpha-revoke", beta)
        stop()
        mark("modified-selection-control")
        // A binding the held grant does not match cannot open, whatever the screen selects.
        runBlocking {
            try { services.open(betaCurrent.copy(host = "unauthorized.example")).close(); throw AssertionError("Modified binding opened") }
            catch (refused: ProviderCodec.Refusal) { assertEquals("binding", refused.code) }
        }
        assertEquals(betaCurrent.provider, services.state.value.selection?.provider)
        assertEquals(betaCurrent, selection(beta))
        assertEquals(ConnectionPhase.Idle, state.phase)
        stoppedOwners()
        write("modified-selection.json", stateEvidence().put("refusal", "binding").put("no_fallback", true))
        connect(betaCurrent)
        data("beta-explicit-reconnect", beta)
        stop()
        assertEquals(manualBefore, sha256(manualSnapshot.readBytes()))
        assertEquals(preferences, runBlocking { PreferencesStore(context).preferences.first() })
        checks.put("two providers, manual ciphertext and routing preferences retain independent authority")
        gates.put("provider_isolation", true).put("exact_catalog_selection", true)
        write("restart.json", JSONObject().put("pid", Process.myPid()).put("manual", manual)
            .put("manual_snapshot_sha256", manualBefore).put("selection", JSONObject(ServiceRoute(betaCurrent.provider, betaCurrent.epoch, "relay", null).encode())))
        write("i2-result.json", stateEvidence().put("passed", true).put("gates", gates).put("checks", checks).put("receipts", receipts))
    }

    @Test fun localPermitInvalidation() {
        config
        val previous = JSONObject(File(handoff, "restart.json").readText())
        assertNotEquals(previous.getInt("pid"), Process.myPid())
        val route = requireNotNull(ServiceRoute.decode(previous.getJSONObject("selection").toString()))
        await { services.state.value.loaded && state.phase == ConnectionPhase.Idle }
        assertEquals(route, services.state.value.selection?.copy(choice = null))
        val selected = selection(route.provider)
        assertFalse(services.state.value.manual)
        stoppedOwners()
        navigate()
        chooseNetwork()
        connect(selected)
        data("local-permit-before-close", beta)
        write("traffic-evidence-local-permit.json", JSONObject().put("receipts", receipts))
        val invalidatedRun = state.generation
        val before = state.receipts.toList()
        host("permit-active-before", provider = beta, selected = selected)
        mark("local-permit-invalidation")
        // Public teardown invalidates the captured permit without revoking the
        // server grant or stopping this separately owned native connection.
        runBlocking(Dispatchers.IO) { services.close() }
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (state.phase != ConnectionPhase.Failed && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        write("local-permit-observation.json", stateEvidence().put("captured_run", invalidatedRun)
            .put("receipts_unchanged", before == state.receipts))
        assertEquals("Captured permit invalidation must stop the exact run", ConnectionPhase.Failed, state.phase)
        assertEquals(ConnectionFailure.Provider, state.failure)
        assertEquals("revoked", state.providerCode)
        assertEquals(0, state.nativeCode)
        assertEquals(invalidatedRun, state.generation)
        assertEquals(selected.provider, services.state.value.selection?.provider)
        assertFalse(services.state.value.manual)
        assertEquals(before, state.receipts)
        stoppedOwners()
        host("permit-active-after", provider = beta, selected = selected)
        gates.put("local_permit_invalidation", true)
        checks.put("local permit invalidation stops the captured native run while the server grant remains active")
        write("local-permit-result.json", stateEvidence().put("passed", true).put("gates", gates)
            .put("checks", checks).put("receipts", receipts).put("captured_run", invalidatedRun)
            .put("receipts_unchanged", true).put("no_fallback", true))
        // The closed service owner remains closed. A real process restart owns
        // the next acquisition from the unchanged persisted selection.
    }

    @Test fun afterProcessDeath() {
        config
        val previous = JSONObject(File(handoff, "restart.json").readText())
        assertNotEquals(previous.getInt("pid"), Process.myPid())
        val route = requireNotNull(ServiceRoute.decode(previous.getJSONObject("selection").toString()))
        val manual = previous.getString("manual")
        mark("after-process-death")
        await { services.state.value.loaded && state.phase == ConnectionPhase.Idle }
        assertEquals(route, services.state.value.selection?.copy(choice = null))
        val selected = selection(route.provider)
        assertFalse(services.state.value.manual)
        assertNull(services.state.value.authorizing)
        assertNull(services.state.value.codePrompt)
        assertTrue(services.state.value.catalogs.isEmpty())
        assertTrue(state.receipts.isEmpty())
        stoppedOwners()
        navigate()
        chooseNetwork()
        val preferences = runBlocking { PreferencesStore(context).preferences.first() }
        connect(selected)
        data("restart", beta)
        stop()
        connect(selected)
        data("before-transport-expiry", beta)
        val expiringRun = state.generation
        val beforeExpiry = state.receipts.toList()
        host("transport-expiry-arm", provider = beta, selected = selected)
        val wait = traffic.optLong("expiry_wait_ms", 420_000)
        require(wait in 1..500_000)
        await(wait) { state.phase == ConnectionPhase.Failed }
        // The reference operator deliberately closes before its advertised
        // deadline; native CLOSED can therefore precede local permit expiry.
        assertTrue((state.failure == ConnectionFailure.Native && state.nativeCode == -12 && state.providerCode.isEmpty()) ||
            (state.failure == ConnectionFailure.Provider && state.providerCode == "revoked" && state.nativeCode == 0))
        assertEquals(selected.provider, services.state.value.selection?.provider)
        assertEquals(expiringRun, state.generation)
        assertFalse(services.state.value.manual)
        assertEquals(beforeExpiry, state.receipts)
        stoppedOwners()
        write("transport-expiry.json", stateEvidence().put("passed", true).put("exact_run_stopped", true)
            .put("receipts_unchanged", true).put("no_fallback", true))
        host("transport-expired", provider = beta, selected = selected)
        gates.put("transport_expiry", true)
        stop()
        host("offline-beta", provider = beta, selected = selected)
        remove(beta)
        assertTrue(services.state.value.entries.isEmpty())
        assertEquals(selected.provider, services.state.value.selection?.provider)
        assertFalse(services.state.value.manual)
        click(R.string.connect_transport)
        await { state.phase == ConnectionPhase.Failed }
        assertEquals(ConnectionFailure.Provider, state.failure)
        stoppedOwners()
        write("removed-selection.json", stateEvidence().put("no_fallback", true))
        stop()
        host("offline-all")
        assertEquals(previous.getString("manual_snapshot_sha256"),
            sha256(File(context.noBackupFilesDir, "ito-transport/snapshot.bin").readBytes()))
        assertEquals(preferences, runBlocking { PreferencesStore(context).preferences.first() })
        click(MANUAL_LABEL)
        await { services.state.value.manual && state.catalog.selectedId == manual && state.phase == ConnectionPhase.Idle }
        connect(null, manual)
        data("manual-offline", "manual")
        stop()
        independent()
        checks.put("actual process restart retains selection and requires explicit connection")
        checks.put("transport expiry stops the exact run; offline removal keeps selected authority unusable")
        checks.put("independent manual import remains functional with both provider APIs stopped")
        gates.put("restart", true).put("offline_removal", true).put("manual_independent", true)
        write("i2-restart-result.json", stateEvidence().put("passed", true).put("gates", gates).put("checks", checks).put("receipts", receipts))
    }

    private fun addAndAuthorize(provider: String, issuer: String) {
        mark("add:$provider")
        click(R.string.provider_add)
        input(R.string.provider_origin, provider)
        input(R.string.provider_issuer, issuer)
        compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText(text(R.string.provider_add)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        await { services.state.value.entries.any { it.provider == provider } && services.state.value.busy.isEmpty() }
        assertNull(services.state.value.error)
        ProviderAppLinks.verify(context, ProviderRegistration(context.packageName,
            config.getString("client_id"), config.getString("callback")))
        mark("browser:$provider")
        within(provider, R.string.provider_sign_in)
        host("browser-return", provider = provider)
        // This instrumentation already owns UiAutomation for the real document
        // picker. Keep the browser gesture on that same UiDevice. A second
        // shell uiautomator process competes for the active automation owner.
        val browserPackage = "com.android.chrome"
        val returnLabel = "Return to YUME"
        val returnLink = checkNotNull(device.wait(
            Until.findObject(By.pkg(browserPackage).text(returnLabel)), 25_000)) {
            "The registered external browser return control did not appear"
        }
        assertEquals(browserPackage, device.currentPackageName)
        assertEquals(provider, services.state.value.authorizing)
        assertEquals(issuer, services.state.value.entries.single { it.provider == provider }.issuer)
        returnLink.click()
        check(device.wait(Until.hasObject(By.pkg(context.packageName)), 15_000)) {
            "The verified browser return did not resume the consuming app"
        }
        assertEquals(context.packageName, device.currentPackageName)
        await { services.state.value.authorizing == null && services.state.value.busy.isEmpty() }
        assertNull(services.state.value.error)
        assertEquals(issuer, services.state.value.entries.single { it.provider == provider }.issuer)
        write("browser-return-${if (provider == alpha) "alpha" else "beta"}.json", stateEvidence()
            .put("provider", provider).put("issuer", issuer).put("browser_package", browserPackage)
            .put("return_control", returnLabel).put("gesture_owner", "instrumentation UiDevice")
            .put("actual_browser_return_click", true).put("actual_app_return", true)
            .put("consumer_package", context.packageName).put("authorization_completed", true)
            .put("no_intent_or_callback_injection", true))
        checks.put("actual external browser and verified app-link return for $provider")
        gates.put(if (provider == alpha) "browser_alpha" else "browser_beta", true)
    }

    private fun provision(provider: String, wrongCode: Boolean = false) {
        mark("provision:$provider:wrong=$wrongCode")
        if (services.state.value.catalogs[provider] == null) {
            within(provider, R.string.provider_servers)
            await { services.state.value.catalogs[provider] != null && services.state.value.busy.isEmpty() }
            assertNull(services.state.value.error)
        }
        val catalog = services.state.value.catalogs.getValue(provider)
        assertEquals(1, catalog.services.size)
        assertEquals(1, catalog.services.single().servers.size)
        // Choosing is local. Connect fetches the grant, its kit and the opening code.
        visible(hasText(catalog.services.single().servers.single().label) and hasClickAction()).performClick()
        await { services.state.value.selection?.provider == provider && services.state.value.busy.isEmpty() }
        click(R.string.connect_transport)
        await { services.state.value.codePrompt?.provider == provider }
        val prompt = requireNotNull(services.state.value.codePrompt)
        host("opening-code", provider = provider, prompt = prompt)
        val codeFile = File(handoff, "${prompt.grant}.code")
        check(codeFile.isFile && codeFile.length() in 25..80)
        val code = codeFile.readText().trim().toCharArray()
        assertTrue(codeFile.delete())
        try {
            if (wrongCode) code[0] = if (code[0] == '0') '1' else '0'
            try { input(R.string.opening_code, String(code)) }
            catch (_: AssertionError) { throw AssertionError("Provider opening-code entry failed") }
            click(R.string.save)
        } finally { code.fill('\u0000') }
        await { services.state.value.codePrompt == null && state.phase in setOf(ConnectionPhase.Connected, ConnectionPhase.Failed) }
        if (wrongCode) {
            assertEquals(ConnectionPhase.Failed, state.phase)
            stop()
            assertNotNull(services.state.value.error)
            assertNull(services.state.value.entries.single { it.provider == provider }.selection)
            stoppedOwners()
            write("wrong-opening-code.json", stateEvidence().put("refused", true).put("no_publication", true))
            return
        }
        assertEquals(ConnectionPhase.Connected, state.phase)
        stop()
        assertNull(services.state.value.error)
        val selected = selection(provider)
        assertEquals(selected.provider, services.state.value.selection?.provider)
        val expected = JSONObject(File(handoff, "${prompt.grant}.json").readText())
        assertEquals(expected.getString("client_identity"), selected.identity)
        assertEquals(expected.getString("server_key_sha256"), selected.serverKey)
        assertEquals(catalog.services.single().id, selected.service)
        assertEquals(catalog.services.single().servers.single().id, selected.server)
        runBlocking {
            services.open(selected).use { opened ->
                val paths = expected.getJSONObject("files")
                // This qualification fixture has six runtime credentials. Its
                // launcher, SOCKS adapter and unused public key stay out of Ito's
                // device-free runtime staging; the production selector is not
                // the oracle for this independent expected set.
                val required = setOf("yume.json", "credentials/admission.key", "credentials/client-access.psk",
                    "credentials/client-composite.pem", "credentials/server-composite.pub.pem",
                    "credentials/server-mlkem.pub.pem", "credentials/server-trust.pem")
                val excluded = setOf("start-client", "adapters/socks5.json", "credentials/client-composite.pub.pem")
                assertEquals(required + excluded, paths.keys().asSequence().toSet())
                val root = opened.session.directory.toPath()
                val staged = mutableSetOf<String>()
                java.nio.file.Files.walk(root).use { entries ->
                    entries.forEach { path ->
                        val directory = java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        val regular = java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        assertTrue("Runtime staging contains only directories and regular files", directory || regular)
                        if (regular) {
                            assertTrue(staged.size < required.size)
                            staged.add(root.relativize(path).toString())
                        }
                    }
                }
                assertEquals(required, staged)
                for (path in required) {
                    require(ProviderBundleCodec.isSafePath(path))
                    val digest = paths.getString(path)
                    require(digest.matches(Regex("[0-9a-f]{64}")))
                    val file = File(opened.session.directory, path)
                    require(file.length() in 1..ProviderBundleCodec.MAX_FILE_BYTES.toLong())
                    val owned = file.readBytes()
                    try { assertEquals(digest, sha256(owned)) }
                    finally { owned.fill(0) }
                }
                // The session owns this document until native borrowers end.
                assertEquals(paths.getString("yume.json"), sha256(opened.session.config))
                for (path in excluded + "adapters") assertFalse(java.nio.file.Files.exists(
                    File(opened.session.directory, path).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            }
        }
        checks.put("exact fixture config and six credential hashes match; launcher, adapter and unused public key are absent")
        stoppedOwners()
    }

    /** A connected relay renews at half its lease: it prepares a fresh grant while serving, then reconnects with it. */
    private fun rotate(previous: ServiceSelection): ServiceSelection {
        val run = state.generation
        assertEquals(ConnectionPhase.Connected, state.phase)
        assertEquals(previous.provider, services.state.value.selection?.provider)
        val renewAt = requireNotNull(services.renewAfter(previous))
        val deadline = SystemClock.elapsedRealtime() + (renewAt - System.currentTimeMillis() / 1000 + 90).coerceAtLeast(30) * 1000
        while ((selection(previous.provider).grant == previous.grant || state.generation == run || state.phase != ConnectionPhase.Connected) &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
        val replacement = selection(previous.provider)
        assertNotEquals(previous.grant, replacement.grant)
        assertNotEquals(previous.identity, replacement.identity)
        assertEquals(previous.copy(grant = replacement.grant, identity = replacement.identity), replacement)
        assertEquals(ConnectionPhase.Connected, state.phase)
        write("rotation-${if (previous.provider == alpha) "alpha" else "beta"}.json", stateEvidence()
            .put("old_grant", previous.grant).put("new_grant", replacement.grant).put("passed", true))
        stop()
        return replacement
    }

    private fun selection(provider: String) = requireNotNull(services.state.value.entries.single { it.provider == provider }.selection)

    private fun connect(selection: ServiceSelection?, manual: String? = null) {
        if (selection != null) {
            if (services.state.value.manual || services.state.value.selection?.provider != selection.provider) {
                within(selection.provider, R.string.provider_select)
            }
            await { services.state.value.selection?.provider == selection.provider && services.state.value.busy.isEmpty() && state.phase == ConnectionPhase.Idle }
        } else {
            assertTrue(services.state.value.manual)
            assertEquals(manual, state.catalog.selectedId)
        }
        click(R.string.connect_transport)
        await { state.phase in setOf(ConnectionPhase.Connected, ConnectionPhase.Failed) }
        assertEquals(ConnectionPhase.Connected, state.phase)
        assertEquals(ConnectionFailure.None, state.failure)
        assertEquals(network, state.selectedNetwork)
        assertTrue(state.boundSockets > 0)
        if (selection != null) assertEquals(selection.identity, state.localIdentity)
        assertArrayEquals(longArrayOf(1, 1, 1, 0, 1, 0, 0), ItoNative.liveHandles())
    }

    private fun data(label: String, owner: String) {
        mark("data:$label")
        val expectedPeer = traffic.getJSONObject("peers").getString(owner)
        assertEquals(expectedPeer, state.peer)
        for ((kind, size, port, button) in listOf(
            Probe("tcp", 65_536, traffic.getInt("tcp_port"), R.string.test_stream),
            Probe("udp", 1024, traffic.getInt("udp_port"), R.string.test_packet))) {
            val count = state.receipts.size
            input(R.string.test_destination, traffic.getString("host"))
            input(R.string.test_port, port.toString())
            input(if (kind == "tcp") R.string.stream_service else R.string.packet_service, kind)
            click(button)
            await { state.receipts.size > count || state.phase == ConnectionPhase.Failed }
            assertEquals(ConnectionPhase.Connected, state.phase)
            val receipt = state.receipts.last()
            assertEquals(size, receipt.bytes)
            assertEquals(kind, receipt.kind)
            assertEquals(expectedPeer, receipt.peer)
            assertEquals(network, receipt.network)
            assertEquals(state.generation, receipt.generation)
            assertTrue(receipt.nativeBytesSent >= size && receipt.nativeBytesReceived >= size)
            receipts.put(JSONObject().put("label", label).put("provider", owner).put("kind", kind)
                .put("bytes", size).put("sha256", receipt.sha256).put("peer", receipt.peer)
                .put("generation", receipt.generation).put("network", receipt.network)
                .put("local", state.localIdentity).put("bound_sockets", state.boundSockets)
                .put("native_bytes_sent", receipt.nativeBytesSent).put("native_bytes_received", receipt.nativeBytesReceived))
        }
        write("traffic-evidence-${if (File(handoff, "restart.json").exists()) "restart" else "initial"}.json", JSONObject().put("receipts", receipts))
    }

    private fun chooseNetwork() {
        val choice = state.networks.firstOrNull { it.wifi } ?: throw AssertionError("Qualification requires explicit Wi-Fi Network")
        click(context.getString(R.string.network_choice, choice.handle, text(R.string.network_wifi),
            text(if (choice.metered) R.string.network_metered else R.string.network_unmetered)))
        await { state.selectedNetwork == choice.handle && state.phase == ConnectionPhase.Idle }
        network = choice.handle
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val selected = manager.allNetworks.single { it.networkHandle == network }
        val capabilities = requireNotNull(manager.getNetworkCapabilities(selected))
        assertTrue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertFalse(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }

    private fun stop() {
        if (state.phase in setOf(ConnectionPhase.Starting, ConnectionPhase.Connected, ConnectionPhase.Failed)) click(R.string.stop_transport)
        await { state.phase == ConnectionPhase.Idle }
        stoppedOwners()
    }

    private fun remove(provider: String) {
        within(provider, R.string.provider_remove)
        compose.onNode(hasText(text(R.string.remove)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        await { services.state.value.entries.none { it.provider == provider } && services.state.value.busy.isEmpty() }
    }

    private fun importManual(): String {
        val manual = config.getJSONObject("manual")
        val file = manual.getString("file")
        require(file.matches(Regex("ito-i2-[A-Za-z0-9-]+\\.kit")))
        click(R.string.import_configuration)
        check(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10_000) ||
            device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 1000))
        var document = device.wait(Until.findObject(By.text(file)), 1500)
        if (document == null) {
            val roots = device.findObject(By.desc("Show roots")) ?: device.findObject(By.desc("Open navigation drawer"))
            checkNotNull(roots).click()
            checkNotNull(device.wait(Until.findObject(By.text("Downloads")), 5000)).click()
            document = device.wait(Until.findObject(By.text(file)), 10_000)
        }
        checkNotNull(document).click()
        await { compose.onAllNodes(hasText(text(R.string.configuration_name)) and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        input(R.string.configuration_name, MANUAL_LABEL)
        try { input(R.string.opening_code, manual.getString("code")) }
        catch (_: AssertionError) { throw AssertionError("Manual opening-code entry failed") }
        click(R.string.import_action)
        await { state.catalog.entries.size == 1 && state.phase == ConnectionPhase.Idle }
        assertNull(state.catalog.selectedId)
        val id = state.catalog.entries.single().id
        click(MANUAL_LABEL)
        await { services.state.value.manual && state.catalog.selectedId == id && state.phase == ConnectionPhase.Idle }
        return id
    }

    private fun independent() {
        assertEquals("", device.executeShellCommand("pm list packages com.fixcraft.yume").trim())
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.BIND_VPN_SERVICE"))
        assertTrue(info.services.orEmpty().none { it.permission == "android.permission.BIND_VPN_SERVICE" })
        assertFalse(device.hasObject(By.text("Connection request")))
        gates.put("no_yume4a", true).put("no_vpn_permission", true)
    }

    private fun rawDirectControl() {
        mark("raw-direct-control")
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val selected = manager.allNetworks.single { it.networkHandle == network }
        runBlocking {
            withContext(Dispatchers.IO) {
                selected.socketFactory.createSocket().use { socket ->
                    try {
                        socket.connect(InetSocketAddress(traffic.getString("host"), traffic.getInt("tcp_port")), 1500)
                        fail("Server-local destination unexpectedly accepts direct app connection")
                    } catch (_: IOException) { /* The native server path is required to reach this fixture. */ }
                }
            }
        }
        stoppedOwners()
        gates.put("raw_direct_control_failed", true)
        write("raw-direct-control.json", stateEvidence().put("direct_tcp_refused", true))
    }

    private fun stoppedOwners() {
        await {
            ItoNative.liveHandles().all { it == 0L } &&
                File(context.noBackupFilesDir, "ito-transport/sessions").listFiles().orEmpty().isEmpty() &&
                File(context.noBackupFilesDir, "ito-provider-runtime/ito-transport/sessions").listFiles().orEmpty().isEmpty()
        }
    }

    private fun host(action: String, provider: String? = null, prompt: CodePrompt? = null,
                     selected: ServiceSelection? = null, timeout: Long = 45_000) {
        val id = prompt?.grant ?: action
        write("$id.request", JSONObject().put("action", action).put("provider", provider)
            .put("grant_id", prompt?.grant ?: selected?.grant))
        val reply = File(handoff, "$id.ready")
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!reply.isFile && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(reply.isFile && reply.length() == 5L && reply.readText() == "ready") { "Bounded I2 host action did not complete" }
        assertTrue(reply.delete())
    }

    /** Steps appear only on the rows they apply to, and the menu owns refreshing and removal. */
    private fun within(provider: String, resource: Int) {
        val state = services.state.value
        if (resource == R.string.provider_remove) { menu(provider); click(resource); return }
        if (resource == R.string.provider_servers && state.catalogs[provider] != null) {
            menu(provider); click(R.string.provider_refresh_servers); return
        }
        val entries = state.entries.filter { row ->
            when (resource) {
                R.string.provider_sign_in -> row.issuer != null && !row.authorized
                R.string.provider_servers -> state.catalogs[row.provider] == null && (row.issuer == null || row.authorized)
                R.string.provider_select -> row.choice != null && (state.manual || state.selection?.provider != row.provider)
                else -> true
            }
        }
        val index = entries.indexOfFirst { it.provider == provider }
        require(index >= 0)
        visible(hasText(text(resource)) and hasClickAction(), index).performClick()
    }

    private fun menu(provider: String) {
        val entries = services.state.value.entries
        val label = entries.single { it.provider == provider }.label
        val index = entries.filter { it.label == label }.indexOfFirst { it.provider == provider }
        visible(hasContentDescription(context.getString(R.string.provider_more, label)) and hasClickAction(), index).performClick()
    }
    private fun navigate() = compose.onNode(hasText(text(R.string.connections)) and isSelectable()).performClick()
    private fun click(resource: Int) = click(text(resource))
    private fun click(value: String) = visible(hasText(value) and hasClickAction()).performClick()
    private fun input(resource: Int, value: String) = visible((hasText(text(resource)) or hasContentDescription(text(resource))) and hasSetTextAction()).performTextReplacement(value)
    private fun text(resource: Int) = context.getString(resource)
    private fun await(timeout: Long = 45_000, condition: () -> Boolean) = compose.waitUntil(timeout) { condition() }

    private fun visible(matcher: SemanticsMatcher, index: Int = 0): SemanticsNodeInteraction {
        val target = compose.onAllNodes(matcher)[index]
        if (compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            target.performScrollTo()
            val scroll = compose.onNode(hasScrollAction() and hasAnyDescendant(matcher))
            repeat(15) {
                val viewport = scroll.fetchSemanticsNode().boundsInRoot
                val node = target.fetchSemanticsNode()
                val top = node.positionInRoot.y
                val bottom = top + node.size.height
                if (target.isDisplayed() && top >= viewport.top && bottom <= viewport.bottom) return target
                val distance = if (top < viewport.top) top - viewport.top else bottom - viewport.bottom
                scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, distance.coerceIn(-viewport.height * .75f, viewport.height * .75f)) }
            }
            throw AssertionError("I2 control did not become fully visible")
        }
        return target.assertIsDisplayed()
    }

    private fun mark(value: String) { stage = value; write("stage.json", JSONObject().put("stage", stage)) }
    private fun write(name: String, value: JSONObject) { check(handoff.isDirectory); File(handoff, name).writeText(value.toString(2)) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun stateEvidence(): JSONObject = JSONObject().put("pid", Process.myPid()).put("phase", state.phase.name)
        .put("failure", state.failure.name).put("native_code", state.nativeCode).put("provider_code", state.providerCode)
        .put("generation", state.generation).put("network", state.selectedNetwork).put("peer", state.peer)
        .put("local_identity", state.localIdentity).put("manual_id", state.catalog.selectedId)
        .put("provider_selection", services.state.value.selection?.let { JSONObject(it.encode()) })
        .put("manual", services.state.value.manual).put("provider_error", services.state.value.error)
        .put("handles", JSONArray(ItoNative.liveHandles().toList()))
    private data class Probe(val kind: String, val size: Int, val port: Int, val button: Int)
    private companion object { const val MANUAL_LABEL = "I2 independent manual" }
}
