package com.fixcraft.ito.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.call.IdentityFailure
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
