package com.fixcraft.ito.ui

import androidx.annotation.StringRes
import com.fixcraft.ito.R
import com.fixcraft.ito.content.LocalCall
import com.fixcraft.ito.messaging.RingToken
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Times as a chat list shows them: the time today, the weekday this week, otherwise the date. */
internal fun listTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val days = daysBetween(then, today)
    return when {
        days == 0 -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
        days in 1..6 -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(millis))
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))
    }
}

/** The clock time inside a message bubble. */
internal fun bubbleTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** Whole calendar days from [then] to [now], in the device's time zone. */
internal fun daysBetween(then: Calendar, now: Calendar): Int {
    fun startOfDay(calendar: Calendar) = (calendar.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return Math.round((startOfDay(now) - startOfDay(then)) / 86_400_000.0).toInt()
}

/** How one call in history reads: its direction, or why it did not connect. */
@StringRes
internal fun callLabel(outgoing: Boolean, result: Int): Int {
    val ringEnd = LocalCall.ringEnd(result)
    return when (ringEnd) {
        RingToken.CANCELED -> if (outgoing) R.string.ring_canceled else R.string.ring_missed
        RingToken.DECLINED -> R.string.ring_declined
        RingToken.BUSY -> R.string.ring_busy
        RingToken.MISSED -> if (outgoing) R.string.call_no_answer else R.string.ring_missed
        else -> if (outgoing) R.string.call_outgoing else R.string.incoming_call
    }
}

/** Whether a call in history is one the person missed, drawn in the error colour. */
internal fun callMissed(outgoing: Boolean, result: Int): Boolean {
    val ringEnd = LocalCall.ringEnd(result)
    return !outgoing && (ringEnd == RingToken.MISSED || ringEnd == RingToken.CANCELED)
}
