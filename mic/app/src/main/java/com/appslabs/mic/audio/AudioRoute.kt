package com.appslabs.mic.audio

/**
 * Represents the desired audio output destination for playback.
 *
 * Routing priority (highest → lowest):
 *   Per-message route → Global preferred route → Device priority list → System default
 */
enum class AudioRoute {
    /** Let the device priority list (or system) decide. */
    DEFAULT,

    /** Force playback through the built-in phone speaker. */
    SPEAKER,

    /** Prefer a connected Bluetooth audio device (A2DP or SCO). */
    BLUETOOTH,

    /** Prefer a connected wired headset / headphones / USB headset. */
    WIRED_HEADSET
}
