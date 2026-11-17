package com.fixcraft.ito.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.fixcraft.ito.R
import com.fixcraft.ito.content.LocalCall
import com.fixcraft.ito.messaging.RingToken
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndLabelsTest {
    private val scope = SaverScope { true }
    private fun <T : Any> roundTrip(saver: Saver<T, String>, value: T): T? =
        saver.restore(requireNotNull(with(saver) { scope.save(value) }))

    @Test fun chatRoutesSurviveSavedStateWithTheirParents() {
        val routes = listOf(ChatsRoute.Home, ChatsRoute.AddContact, ChatsRoute.Scan(), ChatsRoute.Scan(ChatsRoute.AddContact),
            ChatsRoute.Scan(ChatsRoute.Profile("ito-pair-code")), ChatsRoute.Chat("ito-contact:1:abc"), ChatsRoute.Profile("x|y"))
        for (route in routes) assertEquals(route, roundTrip(ChatsRoute.Saver, route))
        assertEquals(ChatsRoute.Chat("c"), ChatsRoute.Profile("c").parent)
        assertEquals(ChatsRoute.AddContact, ChatsRoute.Scan(ChatsRoute.AddContact).parent)
    }

    @Test fun unknownOrEmptySavedRoutesOpenTheChatList() {
        for (text in listOf("", "me", "chat|", "profile|", "unknown|x")) assertEquals(text, ChatsRoute.Home, ChatsRoute.Saver.restore(text))
        // A scan never returns to another scan.
        assertEquals(ChatsRoute.Scan(), ChatsRoute.Saver.restore("scan|scan|add"))
    }

    @Test fun settingsRoutesSurviveSavedState() {
        for (route in listOf(SettingsRoute.Home, SettingsRoute.Profile, SettingsRoute.Licenses, SettingsRoute.Notice("LICENSE")))
            assertEquals(route, roundTrip(SettingsRoute.Saver, route))
        assertEquals(SettingsRoute.Home, SettingsRoute.Saver.restore("notice|"))
        assertEquals(SettingsRoute.Licenses, SettingsRoute.Notice("Apache-2.0.txt").parent)
    }

    @Test fun initialsUseTheFirstTwoWords() {
        assertEquals("AK", initials("Anastasia Kovalenko-Rimskaya Third"))
        assertEquals("R", initials("  rin "))
        assertEquals("?", initials("   "))
        assertEquals("😀", initials("😀 smile").take(2))
    }

    @Test fun callHistoryReadsDirectionAndWhyItDidNotConnect() {
        assertEquals(R.string.call_outgoing, callLabel(true, 0))
        assertEquals(R.string.incoming_call, callLabel(false, -23))
        assertEquals(R.string.ring_missed, callLabel(false, LocalCall.ringResult(RingToken.MISSED)))
        assertEquals(R.string.call_no_answer, callLabel(true, LocalCall.ringResult(RingToken.MISSED)))
        assertEquals(R.string.ring_declined, callLabel(true, LocalCall.ringResult(RingToken.DECLINED)))
        assertTrue(callMissed(false, LocalCall.ringResult(RingToken.CANCELED)))
        assertFalse(callMissed(true, LocalCall.ringResult(RingToken.MISSED)))
        assertFalse(callMissed(false, 0))
    }

    @Test fun calendarDaysCountAcrossMidnight() {
        val late = Calendar.getInstance().apply { set(2027, 0, 14, 23, 59) }
        val early = Calendar.getInstance().apply { set(2027, 0, 15, 0, 1) }
        assertEquals(1, daysBetween(late, early))
        assertEquals(0, daysBetween(early, early))
    }
}
