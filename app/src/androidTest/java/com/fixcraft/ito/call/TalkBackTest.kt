package com.fixcraft.ito.call

import android.app.UiAutomation
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.MainActivity
import com.fixcraft.ito.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The emulator's real TalkBack service reads the Calls tab. The test turns
 * TalkBack on, places accessibility focus on each element in reading order and
 * records the focused element's text. Every stop must have a label, and the
 * page's main elements must appear in order. TalkBack's own gesture
 * navigation is not driven here. TalkBack is turned off again on every path.
 * Physical-device TalkBack remains a separate gate.
 */
@RunWith(AndroidJUnit4::class)
class TalkBackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun talkBackFocusesAndNamesTheCallsTab() {
        // Keep TalkBack running while this test observes it.
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val book = (context.applicationContext as ItoApplication).callBook
        book.open()
        if ((book.state.value as BookState.Ready).identity == null) book.createIdentity()
        val manager = context.getSystemService(AccessibilityManager::class.java)
        shell(automation, "settings put secure enabled_accessibility_services $SERVICE")
        shell(automation, "settings put secure accessibility_enabled 1")
        val stops = mutableListOf<String>()
        try {
            await { manager.isTouchExplorationEnabled }
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(3000)
                // Injected swipes and key chords do not drive TalkBack's own
                // navigation on the emulator, so focus is placed on each element
                // in tree order with the action TalkBack itself uses, and the
                // focused element's text is read back with TalkBack running.
                // Compose exposes only the rows on screen, so at the end of each
                // pass the page scrolls forward, as TalkBack does, and reading goes on.
                val seen = mutableSetOf<String>()
                for (pass in 0 until 8) {
                    val root = requireNotNull(automation.rootInActiveWindow)
                    for (node in traversal(root)) {
                        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                        Thread.sleep(150)
                        val stop = focused(automation)
                        if (seen.add(stop)) stops += stop
                    }
                    val scroller = scrollable(requireNotNull(automation.rootInActiveWindow)) ?: break
                    if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
                    Thread.sleep(500)
                }
            }
        } finally {
            // executeShellCommand does not run a shell, so the setting is deleted.
            shell(automation, "settings delete secure enabled_accessibility_services")
            shell(automation, "settings put secure accessibility_enabled 0")
            // Private files stay readable with run-as on a disposable test user.
            File(context.filesDir, "wp4-screens").apply { mkdirs() }
                .resolve("talkback-stops.txt").writeText(stops.joinToString("\n") + "\n")
        }
        val focused = stops.filter { it != NONE }
        assertTrue("TalkBack never focused an element: $stops", focused.isNotEmpty())
        assertTrue("a focus stop without a label: $stops", focused.none { it.startsWith(" [") })
        // Actions come before the contact list, and the public code follows it.
        val expected = listOf(R.string.add_contact, R.string.answer_invitation, R.string.contacts,
            R.string.your_contact_code, R.string.your_contact_code_qr, R.string.copy_code).map(context::getString)
        var position = -1
        for (label in expected) {
            val next = focused.withIndex().indexOfFirst { (index, text) -> index > position && text.contains(label) }
            assertTrue("TalkBack did not reach '$label' in order: $stops", next >= 0)
            position = next
        }
        await { !manager.isTouchExplorationEnabled }
    }

    /** Nodes TalkBack stops on: actionable or labelled, in depth-first reading order. */
    private fun traversal(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            val labelled = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            // Off-screen elements of the scrolling page count too: TalkBack scrolls to them.
            if (node.isClickable || node.isScreenReaderFocusable || labelled) {
                found += node
                // A merged actionable node speaks for its children.
                if (node.isClickable || node.isScreenReaderFocusable) return
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return found
    }

    private fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let(::scrollable)?.let { return it }
        return null
    }

    private fun focused(automation: UiAutomation) =
        spoken(automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))

    /** The focused node's own and descendants' text, as TalkBack composes an utterance. */
    private fun spoken(node: AccessibilityNodeInfo?): String {
        if (node == null) return NONE
        val parts = mutableListOf<String>()
        fun collect(current: AccessibilityNodeInfo, depth: Int) {
            listOfNotNull(current.contentDescription, current.text, current.stateDescription)
                .map { it.toString() }.filter { it.isNotBlank() }.forEach(parts::add)
            if (depth < 4) for (index in 0 until current.childCount) current.getChild(index)?.let { collect(it, depth + 1) }
        }
        collect(node, 0)
        val text = parts.joinToString(" ").trim()
        if (text.isNotEmpty()) return text
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        return " [${node.className} ${node.viewIdResourceName} $bounds]"
    }

    private fun shell(automation: UiAutomation, command: String) {
        automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(100)
        }
    }

    private companion object {
        const val SERVICE = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        const val NONE = "<no focus>"
    }
}
