package com.fixcraft.ito.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.R
import com.fixcraft.yume.consumer.ServiceEntry
import com.fixcraft.yume.consumer.ServiceRoute
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.consumer.ServiceUiState
import com.fixcraft.yume.provider.ProviderCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendered presentation with synthetic entries and callbacks. No provider, browser or connection runs. */
@RunWith(AndroidJUnit4::class)
class ProviderPanelTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private val added = mutableListOf<Pair<String, String?>>()
    private val authorized = mutableListOf<String>()
    private val catalogs = mutableListOf<String>()
    private val chosen = mutableListOf<String>()
    private val used = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private val actions = ServiceActions(add = { origin, issuer -> added += origin to issuer }, authorize = { authorized += it }, catalog = { catalogs += it },
        choose = { _, _, server -> chosen += server }, useProvider = { used += it }, cancel = { cancelled += it }, remove = { removed += it },
        code = { _, code -> code.fill('\u0000') })
    private lateinit var state: MutableState<ServiceUiState>

    private fun render(initial: ServiceUiState, advanced: Boolean = false, suggested: Boolean = false) {
        state = mutableStateOf(initial)
        compose.setContent {
            ItoTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProviderPanel(state.value, actions, advanced, if (suggested) PROVIDER else "", if (suggested) ISSUER else "")
                }
            }
        }
    }

    private val signedOut = ServiceEntry(PROVIDER, "Synthetic operator", ISSUER, null, null, false)
    private val signedIn = signedOut.copy(authorized = true)
    private val base = ServiceUiState(loaded = true, browserRegistered = true)
    private fun catalog(country: String? = "JP", second: Boolean = false) = ProviderCodec.Catalog(PROVIDER, "device", "$PROVIDER/provider/v1",
        1_800_000_000, 1_800_000_300,
        listOf(ProviderCodec.Service("relay", "Relay", "relay", emptyList(), listOfNotNull(
            ProviderCodec.Server("jp", "JP1 relay", "jp1.operator.example", 443, "b".repeat(64), country),
            ProviderCodec.Server("jp2", "JP2 relay", "jp2.operator.example", 443, "d".repeat(64), country).takeIf { second },
            ProviderCodec.Server("us", "US2 relay", "us2.operator.example", 443, "c".repeat(64), "US"),
        ))))
    private val choice = ProviderClient.Choice("relay", catalog().services[0].servers[0])
    private val route = ServiceRoute(PROVIDER, "00000000-0000-4000-8000-000000000001", "relay", choice)
    private val chosenEntry = signedIn.copy(choice = choice)

    private fun openMenu() = compose.onNode(hasContentDescription(context.getString(R.string.provider_more, signedOut.label))).performClick()

    @Test fun aSuggestionWaitsForContinueAndNamesWhatItContacts() {
        render(base, suggested = true)
        compose.runOnIdle { assertTrue(added.isEmpty()) }
        compose.onNodeWithText(context.getString(R.string.provider_suggested_body, "operator.example", "issuer.example")).assertExists()
        compose.onNodeWithText(text(R.string.provider_continue)).performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER to ISSUER), added) }
        compose.onNodeWithText(text(R.string.provider_other)).performClick()
        compose.onNode(hasText(text(R.string.provider_add)) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
    }

    @Test fun aBuildWithNoSuggestionStaysProviderless() {
        render(base)
        compose.onNodeWithText(text(R.string.provider_suggested_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.provider_scope)).assertExists()
    }

    @Test fun signingInNamesBothIdentitiesAndOffersOneStep() {
        render(base.copy(entries = listOf(signedOut)))
        compose.onNodeWithText("${text(R.string.provider_label_provider)}: operator.example").assertExists()
        compose.onNodeWithText("${text(R.string.provider_label_sign_in)}: issuer.example").assertExists()
        compose.onNodeWithText(text(R.string.provider_sign_in)).performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), authorized) }
        compose.runOnIdle { state.value = state.value.copy(authorizing = PROVIDER) }
        compose.onNodeWithText(text(R.string.provider_sign_in)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.provider_cancel_sign_in)).performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), cancelled) }
    }

    @Test fun aSignedInServiceOffersNoSecondSignIn() {
        render(base.copy(entries = listOf(signedIn)))
        compose.onNodeWithText(text(R.string.provider_signed_in)).assertExists()
        compose.onNodeWithText(text(R.string.provider_sign_in)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.provider_servers)).performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), catalogs) }
    }

    @Test fun aSignedInRowHidesOriginsUntilDetailsAreOpened() {
        render(base.copy(entries = listOf(signedIn)))
        assertTrue(compose.onAllNodes(hasText(PROVIDER)).fetchSemanticsNodes().isEmpty())
        openMenu()
        compose.onNodeWithText(text(R.string.provider_details)).performClick()
        assertEquals(1, compose.onAllNodes(hasText(PROVIDER)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText(ISSUER)).fetchSemanticsNodes().size)
    }

    @Test fun anotherServerCanBeChosenWithoutRemovingTheService() {
        render(base.copy(entries = listOf(chosenEntry), catalogs = mapOf(PROVIDER to catalog()), selection = route))
        compose.onNode(hasText("Japan") and isSelected()).performClick()
        compose.runOnIdle { assertTrue(chosen.isEmpty()) }
        compose.onNode(hasText("United States") and isNotSelected()).performClick()
        compose.runOnIdle { assertEquals(listOf("us"), chosen) }
    }

    @Test fun aCountryWithSeveralServersOpensIntoItsList() {
        render(base.copy(entries = listOf(signedIn), catalogs = mapOf(PROVIDER to catalog(second = true))))
        compose.onNodeWithText("JP2 relay").assertDoesNotExist()
        compose.onNode(hasText("Japan") and hasClickAction()).performClick()
        compose.onNodeWithText("JP2 relay").performClick()
        compose.runOnIdle { assertEquals(listOf("jp2"), chosen) }
    }

    @Test fun aChosenServiceNotInUseOffersToUseIt() {
        render(base.copy(entries = listOf(chosenEntry), manual = true))
        compose.onNodeWithText(text(R.string.provider_select)).performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), used) }
    }

    @Test fun removalNeedsConfirmation() {
        render(base.copy(entries = listOf(signedIn)))
        openMenu()
        compose.onNodeWithText(text(R.string.provider_remove)).performClick()
        compose.runOnIdle { assertTrue(removed.isEmpty()) }
        compose.onNode(hasText(text(R.string.remove)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(listOf(PROVIDER), removed) }
    }

    /** A flag is decoration: only the country name in the row's text may reach a screen reader. */
    @Test fun aServerRowReadsItsPlaceAndNameOnce() {
        render(base.copy(entries = listOf(signedIn), catalogs = mapOf(PROVIDER to catalog())))
        val row = compose.onNode(hasText("Japan") and hasClickAction()).fetchSemanticsNode()
        assertEquals(listOf("Japan", "JP1 relay"), row.config[SemanticsProperties.Text].map { it.text })
        assertFalse(SemanticsProperties.ContentDescription in row.config)
    }

    @Test fun anUnknownCountryIsSaidInWords() {
        render(base.copy(entries = listOf(signedIn), catalogs = mapOf(PROVIDER to catalog(country = null))))
        compose.onNodeWithText(text(R.string.provider_location_unknown)).assertExists()
    }

    /** Only an unverified sign-in link offers Android's link settings, next to its own message. */
    @Test fun anUnverifiedSignInLinkOffersLinkSettings() {
        render(base.copy(error = "binding"))
        compose.onNodeWithText(text(R.string.provider_error_binding)).assertExists()
        compose.onAllNodesWithText(text(R.string.provider_link_settings)).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(error = "link_unverified") }
        compose.onNodeWithText(text(R.string.provider_error_link_unverified)).assertExists()
        compose.onNodeWithText(text(R.string.provider_link_settings)).assertExists()
    }

    /** Missing trusted time has its own message, not the out-of-date one. */
    @Test fun missingTrustedTimeHasItsOwnMessage() {
        render(base.copy(error = "clock_unavailable"))
        compose.onNodeWithText(text(R.string.provider_error_clock_unavailable)).assertExists()
        compose.onAllNodesWithText(text(R.string.provider_error_stale)).assertCountEquals(0)
    }

    private companion object {
        const val PROVIDER = "https://operator.example"
        const val ISSUER = "https://issuer.example"
    }
}
