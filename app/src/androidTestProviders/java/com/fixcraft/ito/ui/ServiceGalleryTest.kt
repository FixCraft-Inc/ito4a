package com.fixcraft.ito.ui

import android.accessibilityservice.AccessibilityService
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.R
import com.fixcraft.ito.transport.ConnectionFailure
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ConnectionState
import com.fixcraft.ito.transport.NetworkChoice
import com.fixcraft.ito.transport.store.ConfigurationCatalog
import com.fixcraft.ito.transport.store.ConfigurationMetadata
import com.fixcraft.yume.consumer.CodePrompt
import com.fixcraft.yume.consumer.ServiceEntry
import com.fixcraft.yume.consumer.ServiceRoute
import com.fixcraft.yume.consumer.ServiceSelection
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.consumer.ServiceUiState
import com.fixcraft.yume.provider.ProviderCodec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendering gallery of the provider build's Service page, for visual review. Each
 * frame is the production page drawn from a synthetic state. Nothing is clicked, so
 * no provider, browser or connection runs, and a frame says nothing about service
 * behavior.
 */
@RunWith(AndroidJUnit4::class)
class ServiceGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as ItoApplication
    private val capture = GalleryCapture(compose)
    private var connection by mutableStateOf(ConnectionState(phase = ConnectionPhase.Idle))
    private var services by mutableStateOf(ServiceUiState())
    private var advanced by mutableStateOf(false)
    private val nothing = ServiceActions(add = { _, _ -> }, authorize = {}, catalog = {}, choose = { _, _, _ -> }, useProvider = {},
        cancel = {}, remove = {}, code = { _, code -> code.fill('\u0000') })

    private fun show() = compose.setContent {
        ItoTheme {
            Surface(color = MaterialTheme.colorScheme.background) {
                ItoFrame("Service", null, Page.Service, SnackbarHostState(), {}) {
                    PageColumn { ServiceConnectionPage(connection, app.connections, services, nothing, advanced) }
                }
            }
        }
    }

    private fun selection(server: ProviderCodec.Server) = ServiceSelection(PROVIDER, EPOCH, "example-device", "relay", server.id, "example-grant",
        "a".repeat(64), server.serverKeySha256, server.host, server.port, server.label, "relay")

    private fun signedIn(selection: ServiceSelection?, renewAfter: Long? = FUTURE, expiresAt: Long? = FUTURE + 1800, suspended: Boolean = false) =
        ServiceEntry(PROVIDER, LABEL, ISSUER, selection, expiresAt.takeIf { selection != null }, suspended, authorized = true,
            server = JAPAN.takeIf { selection != null }, renewAfter = renewAfter.takeIf { selection != null },
            choice = selection?.let(::choiceOf))

    /** The selected route a held grant belongs to, as the controller publishes it. */
    private fun routeOf(selection: ServiceSelection) = ServiceRoute(PROVIDER, EPOCH, "relay", choiceOf(selection))

    private fun choiceOf(selection: ServiceSelection) =
        ProviderClient.Choice(selection.service, listOf(JAPAN, STATES, UNKNOWN).first { it.id == selection.server })

    private fun signedOut(issuer: String? = ISSUER, label: String = LABEL) = ServiceEntry(PROVIDER, label, issuer, null, null, false)

    @Test fun provider() {
        show()
        val base = ServiceUiState(loaded = true, browserRegistered = true)
        val chosen = selection(JAPAN)
        val frames = listOf(
            "empty" to base.copy(manual = true),
            "signed-out" to base.copy(entries = listOf(signedOut())),
            "signed-out-same-origins" to base.copy(entries = listOf(signedOut(issuer = PROVIDER))),
            "signed-out-long-names" to base.copy(entries = listOf(signedOut(label = LONG_LABEL))),
            "signed-out-unregistered" to base.copy(entries = listOf(signedOut()), browserRegistered = false),
            "browser-pending" to base.copy(entries = listOf(signedOut()), authorizing = PROVIDER),
            "sign-in-cancelled" to base.copy(entries = listOf(signedOut()), error = "cancelled"),
            "signed-in-loading" to base.copy(entries = listOf(signedIn(null)), busy = setOf(PROVIDER)),
            "signed-in-no-list" to base.copy(entries = listOf(signedIn(null))),
            "signed-in-no-servers" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG.copy(services = emptyList()))),
            "servers-unselected" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG)),
            "servers-unknown-country" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG_UNKNOWN)),
            "server-provisioning" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG), busy = setOf(PROVIDER)),
            "server-selected" to base.copy(entries = listOf(signedIn(chosen)), catalogs = mapOf(PROVIDER to CATALOG), selection = routeOf(chosen)),
            "server-selected-no-list" to base.copy(entries = listOf(signedIn(chosen)), selection = routeOf(chosen)),
            "server-installed-not-in-use" to base.copy(entries = listOf(signedIn(chosen)), catalogs = mapOf(PROVIDER to CATALOG), manual = true),
            "suspended" to base.copy(entries = listOf(signedIn(chosen, suspended = true)), selection = routeOf(chosen)),
            "error-unauthorized" to base.copy(entries = listOf(signedOut()), error = "unauthorized"),
            "error-unavailable" to base.copy(entries = listOf(signedIn(chosen)), catalogs = mapOf(PROVIDER to CATALOG), selection = routeOf(chosen), error = "unavailable"),
            "removed" to base.copy(removed = true),
            "code-prompt" to base.copy(entries = listOf(signedOut(issuer = null)), codePrompt = CodePrompt("example", PROVIDER, "example-grant")),
        )
        for ((name, state) in frames) {
            services = state
            capture.frame("service-$name", scroll = name != "code-prompt")
        }
    }

    /** The first Back can close only the keyboard, so this repeats until the dialog is gone. */
    private fun dismissDialog() {
        repeat(3) {
            if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) return
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitForIdle()
            Thread.sleep(300)
        }
    }

    @Test fun dialogs() {
        show()
        val chosen = selection(JAPAN)
        services = ServiceUiState(entries = listOf(signedIn(chosen)), catalogs = mapOf(PROVIDER to CATALOG), selection = routeOf(chosen), loaded = true, browserRegistered = true)
        val menu = hasContentDescription(context.getString(R.string.provider_more, LABEL)) and hasClickAction()
        compose.onNode(menu).performScrollTo().performClick()
        capture.frame("dialog-service-menu", scroll = false)
        compose.onNode(hasText(context.getString(R.string.provider_details)) and hasClickAction()).performClick()
        capture.frame("dialog-service-details", scroll = false)
        dismissDialog()
        compose.onNode(menu).performScrollTo().performClick()
        compose.onNode(hasText(context.getString(R.string.provider_remove)) and hasClickAction()).performClick()
        capture.frame("dialog-remove-service", scroll = false)
        dismissDialog()
        compose.onNode(hasText(context.getString(R.string.provider_add)) and hasClickAction()).performScrollTo().performClick()
        capture.frame("dialog-add-service", scroll = false)
        dismissDialog()
        val files = ConfigurationCatalog(listOf(ConfigurationMetadata("one", "Home relay")), "one")
        connection = ConnectionState(phase = ConnectionPhase.Idle, catalog = files)
        compose.onNode(hasContentDescription(context.getString(R.string.remove_configuration, "Home relay")) and hasClickAction()).performScrollTo().performClick()
        capture.frame("dialog-remove-file", scroll = false)
    }

    @Test fun connection() {
        show()
        val chosen = selection(JAPAN)
        services = ServiceUiState(entries = listOf(signedIn(chosen)), catalogs = mapOf(PROVIDER to CATALOG), selection = routeOf(chosen), loaded = true, browserRegistered = true)
        for ((name, state) in listOf(
            "starting" to ConnectionState(phase = ConnectionPhase.Starting),
            "connected" to ConnectionState(phase = ConnectionPhase.Connected, peer = "ab".repeat(32)),
            "stopping" to ConnectionState(phase = ConnectionPhase.Stopping),
            "failed-network" to ConnectionState(phase = ConnectionPhase.Failed, failure = ConnectionFailure.NetworkLost),
            "failed-server" to ConnectionState(phase = ConnectionPhase.Failed, failure = ConnectionFailure.Native, nativeCode = -7),
            "failed-access" to ConnectionState(phase = ConnectionPhase.Failed, failure = ConnectionFailure.Provider, providerCode = "revoked"),
        )) {
            connection = state
            capture.frame("service-$name")
        }
    }

    @Test fun manual() {
        show()
        val files = ConfigurationCatalog(listOf(ConfigurationMetadata("one", "Home relay"), ConfigurationMetadata("two", "A long configuration name for the family relay")), "one")
        services = ServiceUiState(loaded = true, manual = true, browserRegistered = true)
        connection = ConnectionState(phase = ConnectionPhase.Idle, catalog = files)
        capture.frame("service-manual-selected")
        connection = ConnectionState(phase = ConnectionPhase.Connected, catalog = files, peer = "ab".repeat(32))
        capture.frame("service-manual-connected")
    }

    @Test fun advanced() {
        show()
        advanced = true
        val chosen = selection(JAPAN)
        services = ServiceUiState(entries = listOf(signedIn(chosen)), catalogs = mapOf(PROVIDER to CATALOG), selection = routeOf(chosen), loaded = true, browserRegistered = true)
        connection = ConnectionState(phase = ConnectionPhase.Connected, peer = "ab".repeat(32),
            networks = listOf(NetworkChoice(101, wifi = true, metered = false), NetworkChoice(102, wifi = false, metered = true)))
        capture.frame("service-advanced-connected")
    }

    private companion object {
        const val PROVIDER = "https://api.example.org"
        const val ISSUER = "https://account.example.org"
        const val EPOCH = "00000000-0000-4000-8000-000000000001"
        const val LABEL = "Example Network"
        const val LONG_LABEL = "Example Community Network of the Northern Prefectures, Volunteer Operators' Association"
        const val FUTURE = 4_102_444_800L
        val JAPAN = ProviderCodec.Server("jp-relay", "JP1 relay", "jp1.example.org", 443, "b".repeat(64), "JP")
        val STATES = ProviderCodec.Server("us2-relay", "US2 relay", "us2.example.org", 443, "c".repeat(64), "US")
        val UNKNOWN = ProviderCodec.Server("lab-relay", "Lab relay", "lab.example.org", 8443, "d".repeat(64), null)
        private fun catalog(vararg servers: ProviderCodec.Server) = ProviderCodec.Catalog(PROVIDER, "example-device", "$PROVIDER/provider/v1",
            1_800_000_000, 1_800_000_300, listOf(ProviderCodec.Service("relay", "Relay", "relay", emptyList(), servers.toList())))
        val CATALOG = catalog(JAPAN, STATES)
        val CATALOG_UNKNOWN = catalog(JAPAN, UNKNOWN)
    }
}
