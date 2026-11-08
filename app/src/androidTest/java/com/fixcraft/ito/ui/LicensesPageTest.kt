package com.fixcraft.ito.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.R
import com.fixcraft.ito.domain.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A person who received this build can read its license, its notices and where its source is, from the app itself. */
@RunWith(AndroidJUnit4::class)
class LicensesPageTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsStateTheLicenseAndOpenTheNotices() {
        var opened = 0
        compose.setContent { ItoTheme { SettingsPage(Preferences(), onLicenses = { opened++ }) } }
        compose.onNodeWithText(context.getString(R.string.about_license)).performScrollTo().assertExists()
        compose.onNodeWithText(SOURCE_URL.removePrefix("https://")).performScrollTo().assertExists()
        compose.onNodeWithText(context.getString(R.string.about_licenses)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun theListShowsWhatThisBuildPackaged() {
        val opened = mutableListOf<String>()
        compose.setContent { ItoTheme { LicensesPage { opened += it } } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("LICENSE")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("manifest")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Apache-2.0").performScrollTo().assertExists()
        compose.onNodeWithText("LICENSE").performClick()
        compose.runOnIdle { assertEquals(listOf("LICENSE"), opened) }
    }

    @Test fun theLicenseTextOpens() {
        compose.setContent { ItoTheme { NoticePage("LICENSE") } }
        val title = hasText("GNU AFFERO GENERAL PUBLIC LICENSE", substring = true)
        compose.waitUntil(5_000) { compose.onAllNodes(title).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aMissingNoticeSaysSoInWords() {
        compose.setContent { ItoTheme { NoticePage("no-such-notice.txt") } }
        val message = hasText(context.getString(R.string.notice_unreadable))
        compose.waitUntil(5_000) { compose.onAllNodes(message).fetchSemanticsNodes().isNotEmpty() }
    }
}
