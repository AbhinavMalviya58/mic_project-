package com.appslabs.mic.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Core audio playback and routing manager.
 *
 * Capabilities:
 *  - Text-to-Speech via Android native [TextToSpeech]
 *  - Audio file playback via [MediaPlayer]
 *  - Explicit output routing: SPEAKER / BLUETOOTH / WIRED_HEADSET / DEFAULT
 *  - Multi-device priority via [DevicePriorityManager] when route is DEFAULT
 *  - Audio focus management (API-level aware)
 *  - Live device connection/disconnection tracking
 *  - Full lifecycle cleanup via [release]
 *
 * Thread model: all public methods are safe to call from the main thread.
 * Internal callbacks always post back to the main thread before touching state.
 *
 * Usage:
 * ```
 * val manager = AudioPlaybackManager(applicationContext)
 * manager.speakText("Hello", AudioRoute.SPEAKER) { result -> /* handle */ }
 * manager.playAudio(AudioSource.Resource(R.raw.ding), AudioRoute.BLUETOOTH)
 * manager.stop()
 * manager.release() // call from ViewModel.onCleared()
 * ```
 *
 * @param context Must be application context to avoid Activity leaks.
 */
class AudioPlaybackManager(private val context: Context) {

    // ── Infrastructure ────────────────────────────────────────────────────────

    private val tag = "AudioPlaybackManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val devicePriorityManager = DevicePriorityManager(context)

    // ── State flows (observed by ViewModel → UI) ──────────────────────────────

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _lastResult = MutableStateFlow<PlaybackResult?>(null)
    val lastResult: StateFlow<PlaybackResult?> = _lastResult.asStateFlow()

    // ── TTS ───────────────────────────────────────────────────────────────────

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // Pending speak request queued while TTS is still initializing
    private var pendingText: String? = null
    private var pendingRoute: AudioRoute = AudioRoute.DEFAULT
    private var pendingCallback: ((PlaybackResult) -> Unit)? = null

    // ── MediaPlayer ───────────────────────────────────────────────────────────

    private var mediaPlayer: MediaPlayer? = null

    // ── Replay ────────────────────────────────────────────────────────────────

    private var lastText: String? = null
    private var lastAudioSource: AudioSource? = null
    private var lastRoute: AudioRoute = AudioRoute.DEFAULT

    // ── Audio focus ───────────────────────────────────────────────────────────

    private var audioFocusRequest: AudioFocusRequest? = null  // API 26+
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var hasAudioFocus = false

    // ── Routing state (restored after playback) ───────────────────────────────

    private var savedSpeakerphoneOn = false
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var speakerRoutingApplied = false   // tracks whether we changed AudioManager state
    private var communicationDeviceSet = false  // API 31+ only

    // ── Device callback ───────────────────────────────────────────────────────

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshAvailableDevices()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshAvailableDevices()
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        refreshAvailableDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS) {
                    // Try device locale first, fall back to English
                    val langResult = tts?.setLanguage(Locale.getDefault())
                    isTtsReady = langResult != TextToSpeech.LANG_MISSING_DATA &&
                            langResult != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!isTtsReady) {
                        val engResult = tts?.setLanguage(Locale.ENGLISH)
                        isTtsReady = engResult != TextToSpeech.LANG_MISSING_DATA &&
                                engResult != TextToSpeech.LANG_NOT_SUPPORTED
                    }
                    if (isTtsReady) {
                        // Flush any speak call that arrived before TTS finished initializing
                        val text = pendingText
                        if (text != null) {
                            pendingText = null
                            speakInternal(text, pendingRoute, pendingCallback)
                        }
                    } else {
                        val err = PlaybackResult.Error("TTS language not supported on this device")
                        pendingCallback?.invoke(err)
                        _lastResult.value = err
                        clearPending()
                    }
                } else {
                    val err = PlaybackResult.Error("TTS engine failed to initialize (status=$status)")
                    pendingCallback?.invoke(err)
                    _lastResult.value = err
                    clearPending()
                }
            }
        }
    }

    private fun clearPending() {
        pendingText = null
        pendingRoute = AudioRoute.DEFAULT
        pendingCallback = null
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Converts [text] to speech and plays it through the resolved audio route.
     *
     * Route resolution order:
     *   [route] (if not DEFAULT) → device priority list → system default
     *
     * @param text       The text to speak. Empty strings are rejected.
     * @param route      Desired output destination.
     * @param onResult   Called on the main thread with the actual routing outcome.
     */
    fun speakText(
        text: String,
        route: AudioRoute = AudioRoute.DEFAULT,
        onResult: ((PlaybackResult) -> Unit)? = null
    ) {
        if (text.isBlank()) {
            val err = PlaybackResult.Error("Cannot speak empty text")
            onResult?.invoke(err)
            _lastResult.value = err
            return
        }

        // Store for replay
        lastText = text
        lastAudioSource = null
        lastRoute = route

        // Cancel any ongoing playback before starting new
        stopInternal(restoreAudio = false)

        if (!isTtsReady) {
            // Queue the request; it will be dispatched once TTS finishes initializing
            pendingText = text
            pendingRoute = route
            pendingCallback = onResult
            return
        }

        speakInternal(text, route, onResult)
    }

    /**
     * Plays an audio file/resource/URI through the resolved audio route.
     *
     * @param source   Where the audio content comes from.
     * @param route    Desired output destination.
     * @param onResult Called on the main thread with the actual routing outcome.
     */
    fun playAudio(
        source: AudioSource,
        route: AudioRoute = AudioRoute.DEFAULT,
        onResult: ((PlaybackResult) -> Unit)? = null
    ) {
        lastAudioSource = source
        lastText = null
        lastRoute = route

        stopInternal(restoreAudio = false)

        val routeResult = resolveAndApplyRoute(route, forTts = false)
        if (routeResult is PlaybackResult.Error) {
            onResult?.invoke(routeResult)
            _lastResult.value = routeResult
            return
        }

        if (!requestAudioFocus()) {
            restoreAudioRouting()
            val err = PlaybackResult.Error("Could not obtain audio focus")
            onResult?.invoke(err)
            _lastResult.value = err
            return
        }

        try {
            val player = MediaPlayer()
            configureMediaPlayerAudioAttributes(player, routeResult)

            when (source) {
                is AudioSource.Resource ->
                    player.setDataSource(
                        context,
                        Uri.parse("android.resource://${context.packageName}/${source.resId}")
                    )
                is AudioSource.UriSource ->
                    player.setDataSource(context, source.uri)
                is AudioSource.FileSource ->
                    player.setDataSource(source.file.absolutePath)
            }

            // API 28+: suggest the exact output device to MediaPlayer
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val targetDeviceInfo = findAndroidDeviceInfoForResult(routeResult)
                if (targetDeviceInfo != null) {
                    player.preferredDevice = targetDeviceInfo
                }
            }

            player.setOnPreparedListener { mp ->
                _isPlaying.value = true
                mp.start()
            }
            player.setOnCompletionListener {
                mainHandler.post {
                    _isPlaying.value = false
                    abandonAudioFocus()
                    restoreAudioRouting()
                    mediaPlayer?.release()
                    mediaPlayer = null
                    onResult?.invoke(routeResult)
                    _lastResult.value = routeResult
                }
            }
            player.setOnErrorListener { _, what, extra ->
                mainHandler.post {
                    _isPlaying.value = false
                    abandonAudioFocus()
                    restoreAudioRouting()
                    mediaPlayer?.release()
                    mediaPlayer = null
                    val err = PlaybackResult.Error("MediaPlayer error (what=$what extra=$extra)")
                    onResult?.invoke(err)
                    _lastResult.value = err
                }
                true
            }

            player.prepareAsync()
            mediaPlayer = player

        } catch (e: Exception) {
            abandonAudioFocus()
            restoreAudioRouting()
            val err = PlaybackResult.Error("Failed to prepare audio: ${e.message}")
            onResult?.invoke(err)
            _lastResult.value = err
        }
    }

    /** Replays the last successfully requested content using [lastRoute]. */
    fun replay() {
        val text = lastText
        val source = lastAudioSource
        val route = lastRoute
        when {
            text != null -> speakText(text, route)
            source != null -> playAudio(source, route)
            else -> Log.d(tag, "replay() called but no prior content to replay")
        }
    }

    /** Stops all active playback and restores audio routing state. */
    fun stop() = stopInternal(restoreAudio = true)

    /**
     * Pauses media playback. TTS does not support pause; calling this on an
     * active TTS session stops it (same as [stop] for speech).
     */
    fun pause() {
        tts?.stop()
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (_: Exception) { /* MediaPlayer may be in an invalid state */ }
        _isPlaying.value = false
    }

    /** Resumes a paused [MediaPlayer] session. Has no effect on TTS. */
    fun resume() {
        try {
            if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
                mediaPlayer?.start()
                _isPlaying.value = true
            }
        } catch (_: Exception) { }
    }

    /** Returns a snapshot of all currently detected output devices. */
    fun getAvailableAudioDevices(): List<AudioDevice> = _availableDevices.value

    /** Returns true when at least one Bluetooth audio device is connected. */
    fun isBluetoothAudioConnected(): Boolean =
        _availableDevices.value.any { it.isBluetooth && it.isConnected }

    /** Returns true when at least one wired headset / headphones / USB headset is connected. */
    fun isWiredHeadsetConnected(): Boolean =
        _availableDevices.value.any { it.isWired && it.isConnected }

    /**
     * Releases all resources. Must be called when the owning scope is destroyed
     * (typically from [androidx.lifecycle.ViewModel.onCleared]).
     */
    fun release() {
        stopInternal(restoreAudio = true)
        tts?.shutdown()
        tts = null
        isTtsReady = false
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) { }
    }

    // ── Device detection ──────────────────────────────────────────────────────

    /**
     * Queries [AudioManager.getDevices] and updates [availableDevices].
     * Called automatically on init and whenever device connections change.
     */
    fun refreshAvailableDevices() {
        val rawDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val managedTypes = DevicePriorityManager.DEFAULT_PRIORITY.toSet()

        val detected = rawDevices
            .filter { it.type in managedTypes }
            .map { info ->
                AudioDevice(
                    id = info.id,
                    name = resolveDeviceName(info),
                    type = info.type,
                    isConnected = true,
                    isBluetooth = info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    isWired = info.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            info.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            info.type == AudioDeviceInfo.TYPE_USB_HEADSET,
                    isSpeaker = info.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                )
            }

        // The built-in speaker is always available; synthesize it if AudioManager
        // omitted it from the output list (rare but possible on some ROMs).
        val hasSpeaker = detected.any { it.isSpeaker }
        val finalList = if (hasSpeaker) detected else detected + AudioDevice(
            id = Int.MIN_VALUE,
            name = "Phone Speaker",
            type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            isConnected = true,
            isBluetooth = false,
            isWired = false,
            isSpeaker = true
        )

        _availableDevices.value = finalList
    }

    private fun resolveDeviceName(info: AudioDeviceInfo): String {
        // productName requires no special permission and is available API 23+
        val product = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            info.productName?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        } else null
        return product ?: DevicePriorityManager.DEVICE_TYPE_LABELS[info.type] ?: "Audio Device"
    }

    // ── Internal playback ─────────────────────────────────────────────────────

    private fun speakInternal(
        text: String,
        route: AudioRoute,
        onResult: ((PlaybackResult) -> Unit)?
    ) {
        val routeResult = resolveAndApplyRoute(route, forTts = true)
        if (routeResult is PlaybackResult.Error) {
            onResult?.invoke(routeResult)
            _lastResult.value = routeResult
            return
        }

        if (!requestAudioFocus()) {
            restoreAudioRouting()
            val err = PlaybackResult.Error("Could not obtain audio focus")
            onResult?.invoke(err)
            _lastResult.value = err
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                mainHandler.post { _isPlaying.value = true }
            }
            override fun onDone(id: String?) {
                mainHandler.post {
                    _isPlaying.value = false
                    abandonAudioFocus()
                    restoreAudioRouting()
                    onResult?.invoke(routeResult)
                    _lastResult.value = routeResult
                }
            }
            @Deprecated("Deprecated in API 21; still required for older engines")
            override fun onError(id: String?) {
                mainHandler.post {
                    _isPlaying.value = false
                    abandonAudioFocus()
                    restoreAudioRouting()
                    val err = PlaybackResult.Error("TTS engine reported a playback error")
                    onResult?.invoke(err)
                    _lastResult.value = err
                }
            }
        })

        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun stopInternal(restoreAudio: Boolean) {
        // Stop TTS
        try { tts?.stop() } catch (_: Exception) { }

        // Stop and release MediaPlayer
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        } catch (_: Exception) { }
        mediaPlayer = null

        _isPlaying.value = false
        abandonAudioFocus()
        if (restoreAudio) restoreAudioRouting()
    }

    // ── Route resolution ──────────────────────────────────────────────────────

    /**
     * Resolves the effective [PlaybackResult] for [route] and configures
     * [AudioManager] state accordingly.
     *
     * @param forTts When true, applies speaker-mode routing for TTS.
     *               When false, speaker preference is handled later via
     *               [MediaPlayer.setPreferredDevice] on API 28+.
     */
    private fun resolveAndApplyRoute(route: AudioRoute, forTts: Boolean): PlaybackResult {
        val connected = _availableDevices.value
        return when (route) {
            AudioRoute.SPEAKER -> {
                if (forTts) applySpeakerModeForTts()
                PlaybackResult.Success(AudioRoute.SPEAKER, "Phone Speaker")
            }

            AudioRoute.BLUETOOTH -> {
                val btDevice = connected.firstOrNull { it.isBluetooth && it.isConnected }
                if (btDevice != null) {
                    if (forTts) applyMediaModeForTts()
                    PlaybackResult.Success(AudioRoute.BLUETOOTH, btDevice.name)
                } else {
                    PlaybackResult.Fallback(
                        requestedRoute = AudioRoute.BLUETOOTH,
                        actualRoute = AudioRoute.DEFAULT,
                        reason = "No Bluetooth audio device is connected"
                    )
                }
            }

            AudioRoute.WIRED_HEADSET -> {
                val wiredDevice = connected.firstOrNull { it.isWired && it.isConnected }
                if (wiredDevice != null) {
                    if (forTts) applyMediaModeForTts()
                    PlaybackResult.Success(AudioRoute.WIRED_HEADSET, wiredDevice.name)
                } else {
                    PlaybackResult.Fallback(
                        requestedRoute = AudioRoute.WIRED_HEADSET,
                        actualRoute = AudioRoute.DEFAULT,
                        reason = "No wired headset or headphones are connected"
                    )
                }
            }

            AudioRoute.DEFAULT -> {
                val priorityDevice = devicePriorityManager.resolveDevice(connected)
                if (priorityDevice != null) {
                    val resolvedRoute = when {
                        priorityDevice.isSpeaker -> AudioRoute.SPEAKER
                        priorityDevice.isBluetooth -> AudioRoute.BLUETOOTH
                        priorityDevice.isWired -> AudioRoute.WIRED_HEADSET
                        else -> AudioRoute.DEFAULT
                    }
                    if (forTts) {
                        if (priorityDevice.isSpeaker) applySpeakerModeForTts()
                        else applyMediaModeForTts()
                    }
                    PlaybackResult.Success(resolvedRoute, priorityDevice.name)
                } else {
                    if (forTts) applyMediaModeForTts()
                    PlaybackResult.Success(AudioRoute.DEFAULT, "System Default")
                }
            }
        }
    }

    /**
     * Routes TTS audio to the built-in speaker.
     *
     * Strategy (API-level aware):
     *  • API 31+: [AudioManager.setCommunicationDevice] with the speaker device.
     *  • API 24–30: Switch to MODE_IN_COMMUNICATION + speakerphone on.
     *
     * In both cases TTS audio attributes are set to USAGE_VOICE_COMMUNICATION so
     * the audio policy engine routes the stream to the communication path, which
     * is then forced to the speaker.
     *
     * State is restored via [restoreAudioRouting] when playback ends.
     */
    private fun applySpeakerModeForTts() {
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: use the dedicated setCommunicationDevice API
            val speakerInfo = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speakerInfo != null) {
                audioManager.setCommunicationDevice(speakerInfo)
                communicationDeviceSet = true
            } else {
                // Fallback: legacy path even on API 31+
                applyLegacySpeakerMode()
            }
        } else {
            applyLegacySpeakerMode()
        }
        speakerRoutingApplied = true
    }

    private fun applyLegacySpeakerMode() {
        savedAudioMode = audioManager.mode
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true
    }

    /**
     * Configures TTS audio attributes for media-style routing (Bluetooth / wired / default).
     * The audio policy engine automatically routes USAGE_MEDIA to the connected external device.
     */
    private fun applyMediaModeForTts() {
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        // No AudioManager mode changes needed for USAGE_MEDIA
        speakerRoutingApplied = false
        communicationDeviceSet = false
    }

    /**
     * Restores AudioManager to the state it was in before [applySpeakerModeForTts].
     * Safe to call even if speaker mode was not applied.
     */
    private fun restoreAudioRouting() {
        if (!speakerRoutingApplied) return
        speakerRoutingApplied = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceSet) {
                audioManager.clearCommunicationDevice()
                communicationDeviceSet = false
            } else {
                audioManager.mode = savedAudioMode
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = savedSpeakerphoneOn
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to restore audio routing: ${e.message}")
        }
    }

    // ── MediaPlayer audio attributes ──────────────────────────────────────────

    private fun configureMediaPlayerAudioAttributes(player: MediaPlayer, routeResult: PlaybackResult) {
        // Always use USAGE_MEDIA for MediaPlayer; device targeting is via setPreferredDevice (API 28+)
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
    }

    // ── Device lookup for API 28+ setPreferredDevice ──────────────────────────

    /**
     * Finds the [AudioDeviceInfo] matching the actual route in [result] so it can
     * be passed to [MediaPlayer.setPreferredDevice] on API 28+.
     */
    private fun findAndroidDeviceInfoForResult(result: PlaybackResult): AudioDeviceInfo? {
        val route = when (result) {
            is PlaybackResult.Success  -> result.route
            is PlaybackResult.Fallback -> result.actualRoute
            is PlaybackResult.Error    -> return null
        }
        return findAndroidDeviceInfoByRoute(route)
    }

    fun findAndroidDeviceInfoByRoute(route: AudioRoute): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when (route) {
            AudioRoute.SPEAKER -> outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            AudioRoute.BLUETOOTH -> outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            AudioRoute.WIRED_HEADSET -> outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            AudioRoute.DEFAULT -> null
        }
    }

    fun findAndroidDeviceInfoByType(type: Int): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == type }

    // ── Audio focus ───────────────────────────────────────────────────────────

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS              -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT    -> pause()
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT    -> resume()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck (lower volume slightly) — MediaPlayer does this automatically
                // when its AudioAttributes have CONTENT_TYPE_MUSIC
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                .setAcceptsDelayedFocusGain(false)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            legacyFocusListener = focusChangeListener
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        hasAudioFocus = granted
        return granted
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(legacyFocusListener)
                legacyFocusListener = null
            }
        } catch (e: Exception) {
            Log.w(tag, "abandonAudioFocus failed: ${e.message}")
        }
        hasAudioFocus = false
    }
}
