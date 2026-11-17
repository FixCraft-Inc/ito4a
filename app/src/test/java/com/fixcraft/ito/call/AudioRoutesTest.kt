package com.fixcraft.ito.call

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRoutesTest {
    private fun kinds(routes: List<AudioRoute>) = routes.map { it.kind }

    @Test fun onlyCallCapableDevicesAreRoutes() {
        assertEquals(AudioRouteKind.Bluetooth, AudioRoutes.kindOf(26))
        assertEquals(AudioRouteKind.Wired, AudioRoutes.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertNull(AudioRoutes.kindOf(AudioDeviceInfo.TYPE_HDMI))
        assertNull(AudioRoutes.kindOf(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    @Test fun beforeAndroidTwelveAHeadsetReplacesTheEarpiece() {
        val phone = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertEquals(listOf(AudioRouteKind.Earpiece, AudioRouteKind.Speaker), kinds(AudioRoutes.legacy(phone, emptyList())))
        assertEquals(listOf(AudioRouteKind.Wired, AudioRouteKind.Speaker),
            kinds(AudioRoutes.legacy(phone + AudioDeviceInfo.TYPE_WIRED_HEADSET, emptyList())))
        val bluetooth = AudioRoutes.legacy(phone + AudioDeviceInfo.TYPE_BLUETOOTH_SCO, listOf("", "Car kit"))
        assertEquals(AudioRoute(AudioRouteKind.Bluetooth.ordinal, AudioRouteKind.Bluetooth, "Car kit"), bluetooth.last())
        // A tablet has no earpiece, so it is not offered.
        assertEquals(listOf(AudioRouteKind.Speaker), kinds(AudioRoutes.legacy(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), emptyList())))
    }

    @Test fun oneEntryPerProductInPhoneSpeakerExternalOrder() {
        val routes = AudioRoutes.distinct(listOf(AudioRoute(9, AudioRouteKind.Bluetooth, "Buds"),
            AudioRoute(3, AudioRouteKind.Speaker, ""), AudioRoute(10, AudioRouteKind.Bluetooth, "Buds"),
            AudioRoute(2, AudioRouteKind.Earpiece, "")))
        assertEquals(listOf(2, 3, 9), routes.map { it.id })
    }
}
