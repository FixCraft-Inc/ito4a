package com.fixcraft.ito.call

import android.media.AudioDeviceInfo

/** Where call audio plays and records. Headsets carry both microphone and output. */
enum class AudioRouteKind { Earpiece, Speaker, Wired, Usb, Bluetooth, HearingAid }

/**
 * One device the call can use right now. [id] is Android's device ID on
 * Android 12 and later, where the platform selects communication devices, and
 * the kind's ordinal before that. [name] is the product name Android reports
 * for external devices, empty for the phone's own earpiece and speaker.
 */
data class AudioRoute(val id: Int, val kind: AudioRouteKind, val name: String)

internal object AudioRoutes {
    /** The device types a voice call can be routed to, by kind. Others are left out. */
    fun kindOf(type: Int): AudioRouteKind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteKind.Earpiece
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRouteKind.Speaker
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRouteKind.Wired
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRouteKind.Usb
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> AudioRouteKind.Bluetooth
        AudioDeviceInfo.TYPE_HEARING_AID -> AudioRouteKind.HearingAid
        else -> null
    }

    /**
     * Before Android 12 a plugged-in headset takes over from the earpiece, and
     * Android offers no way back to it, so the list shows one or the other.
     */
    fun legacy(outputs: List<Int>, bluetoothNames: List<String>): List<AudioRoute> {
        val kinds = outputs.mapNotNull(::kindOf).toSet()
        val wired = kinds.firstOrNull { it == AudioRouteKind.Wired || it == AudioRouteKind.Usb }
        return buildList {
            if (wired != null) add(AudioRoute(wired.ordinal, wired, ""))
            else if (AudioRouteKind.Earpiece in kinds) add(AudioRoute(AudioRouteKind.Earpiece.ordinal, AudioRouteKind.Earpiece, ""))
            if (AudioRouteKind.Speaker in kinds) add(AudioRoute(AudioRouteKind.Speaker.ordinal, AudioRouteKind.Speaker, ""))
            if (AudioRouteKind.Bluetooth in kinds) add(AudioRoute(AudioRouteKind.Bluetooth.ordinal, AudioRouteKind.Bluetooth,
                bluetoothNames.firstOrNull { it.isNotBlank() } ?: ""))
        }
    }

    /** One entry per product, since a headset can appear once per profile. Order: phone, speaker, then external devices. */
    fun distinct(routes: List<AudioRoute>): List<AudioRoute> =
        routes.distinctBy { it.kind to it.name }.sortedBy { it.kind.ordinal }

    // AudioDeviceInfo's Bluetooth LE types from Android 12. Older versions never report them.
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
}
