package com.appslabs.mic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.appslabs.mic.audio.AudioPlaybackManager
import com.appslabs.mic.audio.AudioRoute
import com.appslabs.mic.audio.AudioSource
import com.appslabs.mic.audio.DevicePriorityManager
import com.appslabs.mic.audio.PlaybackResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel that bridges [AudioPlaybackManager] to the Compose UI layer.
 *
 * Survives configuration changes (rotation, theme switch) via the standard
 * AndroidViewModel lifecycle. [AudioPlaybackManager.release] is called from
 * [onCleared] so TTS, MediaPlayer, and AudioManager resources are freed when
 * the screen is genuinely destroyed.
 *
 * Routing priority enforced here:
 *   per-message route (if not DEFAULT) → [preferredRoute] (if not DEFAULT) → manager DEFAULT
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Manager ───────────────────────────────────────────────────────────────

    val manager = AudioPlaybackManager(application.applicationContext)

    // ── Forwarded state flows (read-only for UI) ──────────────────────────────

    /** Live list of detected output devices; updates on connect/disconnect. */
    val availableDevices = manager.availableDevices

    /** True while TTS or MediaPlayer is actively producing audio. */
    val isPlaying = manager.isPlaying

    /** Last routing result (Success / Fallback / Error). Null before first play. */
    val lastResult = manager.lastResult

    // ── Preferred route (global setting) ─────────────────────────────────────

    private val _preferredRoute = MutableStateFlow(AudioRoute.DEFAULT)
    val preferredRoute: StateFlow<AudioRoute> = _preferredRoute.asStateFlow()

    fun setPreferredRoute(route: AudioRoute) {
        _preferredRoute.value = route
    }

    // ── Device priority list ──────────────────────────────────────────────────

    /**
     * Current ordered priority list of [android.media.AudioDeviceInfo] type constants.
     * The UI renders this as a reorderable list; [DevicePriorityManager] persists it.
     */
    private val _devicePriorityList = MutableStateFlow(
        manager.devicePriorityManager.getPriorityList()
    )
    val devicePriorityList: StateFlow<List<Int>> = _devicePriorityList.asStateFlow()

    /** Moves [deviceType] one step higher in the priority list and persists the change. */
    fun movePriorityUp(deviceType: Int) {
        manager.devicePriorityManager.moveUp(deviceType)
        _devicePriorityList.value = manager.devicePriorityManager.getPriorityList()
    }

    /** Moves [deviceType] one step lower in the priority list and persists the change. */
    fun movePriorityDown(deviceType: Int) {
        manager.devicePriorityManager.moveDown(deviceType)
        _devicePriorityList.value = manager.devicePriorityManager.getPriorityList()
    }

    // ── Playback actions ──────────────────────────────────────────────────────

    /**
     * Speaks [text] using the resolved route.
     *
     * @param text         Text to convert to speech.
     * @param messageRoute Per-message override. Pass [AudioRoute.DEFAULT] to use
     *                     the global preferred route instead.
     */
    fun speakText(
        text: String,
        messageRoute: AudioRoute = AudioRoute.DEFAULT
    ) {
        val effectiveRoute = resolveEffectiveRoute(messageRoute)
        manager.speakText(text, effectiveRoute)
    }

    /**
     * Plays [source] using the resolved route.
     *
     * @param source       Audio content descriptor.
     * @param messageRoute Per-message override.
     */
    fun playAudio(
        source: AudioSource,
        messageRoute: AudioRoute = AudioRoute.DEFAULT
    ) {
        val effectiveRoute = resolveEffectiveRoute(messageRoute)
        manager.playAudio(source, effectiveRoute)
    }

    fun stop()   = manager.stop()
    fun replay() = manager.replay()
    fun pause()  = manager.pause()
    fun resume() = manager.resume()

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Three-tier route cascade:
     *  1. [messageRoute] if explicitly set (not DEFAULT)
     *  2. [_preferredRoute] if explicitly set (not DEFAULT)
     *  3. DEFAULT → AudioPlaybackManager will consult DevicePriorityManager
     */
    private fun resolveEffectiveRoute(messageRoute: AudioRoute): AudioRoute = when {
        messageRoute != AudioRoute.DEFAULT    -> messageRoute
        _preferredRoute.value != AudioRoute.DEFAULT -> _preferredRoute.value
        else                                  -> AudioRoute.DEFAULT
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        manager.release()
    }
}
