package com.appslabs.mic.audio

/**
 * Result returned (or emitted via StateFlow) after a playback attempt.
 *
 * Callers should always handle [Fallback] and [Error] in the UI so the user
 * understands what actually happened.
 */
sealed class PlaybackResult {

    /** Playback started on the exact requested route. */
    data class Success(
        val route: AudioRoute,
        val deviceName: String = ""
    ) : PlaybackResult()

    /**
     * The requested route was unavailable; playback fell back to [actualRoute].
     * [reason] contains a human-readable explanation.
     */
    data class Fallback(
        val requestedRoute: AudioRoute,
        val actualRoute: AudioRoute,
        val reason: String,
        val deviceName: String = ""
    ) : PlaybackResult()

    /** Playback could not start at all. */
    data class Error(val message: String) : PlaybackResult()
}
