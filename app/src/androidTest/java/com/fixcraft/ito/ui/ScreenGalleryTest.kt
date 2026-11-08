package com.fixcraft.ito.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.R
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.call.IdentityFailure
import com.fixcraft.ito.call.OwnIdentity
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.domain.Presentation
import com.fixcraft.ito.domain.SavedService
import com.fixcraft.ito.domain.ServiceOrigin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Rendering gallery for visual review, not a behavior test. Each frame is a
 * production screen in its real frame, drawn from public fixture data. No peer,
 * relay, camera or stored identity runs, and nothing here shows that a call or a
 * connection works. Call screens have their own gallery in [com.fixcraft.ito.call].
 */
@RunWith(AndroidJUnit4::class)
class ScreenGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as ItoApplication
    private val capture = GalleryCapture(compose)
    private var screen by mutableStateOf<@Composable () -> Unit>({})
    private var title by mutableStateOf("Ito")
    private var page by mutableStateOf(Page.Calls)

    private fun show() = compose.setContent {
        ItoTheme {
            Surface(color = MaterialTheme.colorScheme.background) {
                ItoFrame(title, null, page, SnackbarHostState(), {}) { screen() }
            }
        }
    }

    private fun contact(seed: Int, name: String, trust: Int, replacedBy: ByteArray? = null, replaces: ByteArray? = null): Contact {
        val key = ByteArray(32) { (it * seed + 3).toByte() }
        return Contact(key, name, trust, CallNative.contactCodeFormat(key), "b66dd75f07e8c8c942f8c0929114ae0a707e90ce9bc4949c61498b147c61538b", replacedBy, replaces)
    }

    private val verified = contact(7, "Aiko", CallNative.TRUST_VERIFIED)
    private val unverified = contact(11, "Anastasia Vasilyevna Kovalenko-Rimskaya", CallNative.TRUST_UNVERIFIED)
    private val blocked = contact(13, "Sam", CallNative.TRUST_BLOCKED)
    private val replaced = contact(17, "Noor (old key)", CallNative.TRUST_UNVERIFIED, replacedBy = ByteArray(32) { (it * 19 + 3).toByte() })
    private val replacement = contact(19, "Noor", CallNative.TRUST_UNVERIFIED, replaces = ByteArray(32) { (it * 17 + 3).toByte() })
    private val everyone = listOf(verified, unverified, blocked, replaced, replacement)
    private val own: OwnIdentity get() {
        val key = ByteArray(32) { (it * 23 + 3).toByte() }
        return OwnIdentity(key, CallNative.contactCodeFormat(key), "1c8f3a20e6d94b7755aa01c3d2f6e89014bb7c62ea3390d5c17fa2486e0b9d41")
    }

    private fun frame(name: String, content: @Composable () -> Unit) {
        screen = content
        capture.frame(name)
    }

    @Test fun calls() {
        val model = CallsModel(app)
        show()
        frame("calls-identity-loading") { IdentityLoading() }
        frame("calls-identity-corrupt") { IdentityUnreadable(IdentityFailure.Corrupt) {} }
        frame("calls-identity-key-unavailable") { IdentityUnreadable(IdentityFailure.KeyUnavailable) {} }
        frame("calls-identity-io") { IdentityUnreadable(IdentityFailure.Io) {} }
        frame("calls-create-identity") { CreateIdentity(model) }
        frame("calls-home-empty") { Home(own, emptyList(), false, {}, {}) }
        frame("calls-home-populated-offline") { Home(own, everyone, false, {}, {}) }
        frame("calls-home-populated-ready") { Home(own, everyone, true, {}, {}) }
        frame("calls-add-contact") { AddContact(model) {} }
    }

    @Test fun contacts() {
        val model = CallsModel(app)
        show()
        for ((name, person, connected) in listOf(
            Triple("contact-verified-ready", verified, true),
            Triple("contact-verified-offline", verified, false),
            Triple("contact-unverified", unverified, false),
            Triple("contact-blocked", blocked, false),
            Triple("contact-old-key", replaced, false),
            Triple("contact-new-key", replacement, false),
        )) {
            title = person.name
            frame(name) { ContactDetails(person, everyone, connected, model, {}, {}) }
        }
        title = verified.name
        frame("contact-replace-key") { ReplaceKey(verified, model) {} }
        frame("contact-verified-again") { ContactDetails(verified, everyone, true, model, {}, {}) }
        compose.onNode(hasText(context.getString(R.string.block_contact)) and hasClickAction()).performScrollTo().performClick()
        capture.frame("dialog-block-contact", scroll = false)
    }

    @Test fun settings() {
        show()
        page = Page.Settings
        title = "Settings"
        frame("settings-simple") { SettingsPage(Preferences()) }
        frame("settings-advanced-locked") { SettingsPage(Preferences(presentation = Presentation.Advanced), lockEnabled = true) }
        title = "Licenses"
        frame("settings-licenses") { LicensesPage {} }
        title = "LICENSE"
        frame("settings-license-text") { NoticePage("LICENSE") }
    }

    @Test fun locked() {
        var variant by mutableStateOf(Triple(true, false, false))
        compose.setContent { ItoTheme { LockedScreen(variant.first, variant.second, variant.third, {}, {}, {}) } }
        capture.frame("locked", scroll = false)
        variant = Triple(true, true, true)
        capture.frame("locked-call-active", scroll = false)
        variant = Triple(false, false, false)
        capture.frame("lock-key-unavailable", scroll = false)
    }

    @Test fun savedAddresses() {
        show()
        page = Page.Service
        title = "Service"
        val saved = SavedService(UUID.fromString("00000000-0000-4000-8000-000000000001"), "Home relay", ServiceOrigin.parse("https://relay.example.org")!!)
        frame("service-saved-addresses") { PageColumn { SavedAddresses(Preferences(services = listOf(saved)), {}, {}) } }
    }
}
