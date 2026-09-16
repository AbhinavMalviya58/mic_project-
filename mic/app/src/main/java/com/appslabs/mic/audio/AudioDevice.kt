package com.appslabs.mic.audio

/**
 * Normalized representation of an Android audio output device.
 *
 * Populated from [android.media.AudioDeviceInfo] via AudioManager.getDevices().
 */
data class AudioDevice(
    /** Matches [android.media.AudioDeviceInfo.getId]. -1 for the always-present virtual speaker entry. */
    val id: Int,

    /** Human-readable label (product name when available, otherwise a type label). */
    val name: String,

    /** Raw [android.media.AudioDeviceInfo] type constant, e.g. TYPE_BUILTIN_SPEAKER. */
    val type: Int,

    /** True when the device is currently detected as connected/available. */
    val isConnected: Boolean,

    /** True for TYPE_BLUETOOTH_A2DP or TYPE_BLUETOOTH_SCO. */
    val isBluetooth: Boolean,

    /** True for TYPE_WIRED_HEADPHONES, TYPE_WIRED_HEADSET, TYPE_USB_HEADSET. */
    val isWired: Boolean,

    /** True for TYPE_BUILTIN_SPEAKER. */
    val isSpeaker: Boolean
)
