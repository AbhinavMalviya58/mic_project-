# Audio Playback & Device Routing — Implementation Reference

> **Platform:** Android (Kotlin) · **Min SDK:** 24 · **Target SDK:** 37  
> **Constraint:** Zero third-party libraries. Uses only Android SDK APIs.

---

## Overview

The implementation is split into two coordinated classes:

| Class | Role |
|---|---|
| `AudioPlaybackManager` | Core engine — TTS, MediaPlayer, routing, audio focus, device detection |
| `DevicePriorityManager` | Persists and resolves the user-configured device priority order |

Both are exposed to the UI via a standard `AndroidViewModel` which enforces a three-tier route cascade before delegating to the manager.

---

## Part 1 — Voice/Audio Generation

### How voice output is produced

Android's native `TextToSpeech` engine is used exclusively. No third-party TTS SDK is involved.

**Initialization** (called once in `AudioPlaybackManager.init {}`):

```kotlin
private fun initTts() {
    tts = TextToSpeech(context) { status ->
        mainHandler.post {
            if (status == TextToSpeech.SUCCESS) {
                // Try device locale; fall back to English
                val langResult = tts?.setLanguage(Locale.getDefault())
                isTtsReady = langResult != TextToSpeech.LANG_MISSING_DATA &&
                             langResult != TextToSpeech.LANG_NOT_SUPPORTED
                if (!isTtsReady) {
                    val engResult = tts?.setLanguage(Locale.ENGLISH)
                    isTtsReady = engResult != TextToSpeech.LANG_MISSING_DATA &&
                                 engResult != TextToSpeech.LANG_NOT_SUPPORTED
                }
                if (isTtsReady) {
                    // Flush any speak request that arrived before TTS finished initializing
                    val text = pendingText
                    if (text != null) {
                        pendingText = null
                        speakInternal(text, pendingRoute, pendingCallback)
                    }
                }
            }
        }
    }
}
```

**Public entry point:**

```kotlin
fun speakText(
    text: String,
    route: AudioRoute = AudioRoute.DEFAULT,
    onResult: ((PlaybackResult) -> Unit)? = null
) {
    if (text.isBlank()) { /* emit Error */ return }

    lastText = text; lastAudioSource = null; lastRoute = route  // save for replay()
    stopInternal(restoreAudio = false)                          // cancel any prior playback

    if (!isTtsReady) {
        // Queue request; initTts() callback will flush it
        pendingText = text; pendingRoute = route; pendingCallback = onResult
        return
    }
    speakInternal(text, route, onResult)
}
```

**Core TTS playback with routing applied:**

```kotlin
private fun speakInternal(
    text: String,
    route: AudioRoute,
    onResult: ((PlaybackResult) -> Unit)?
) {
    // 1. Resolve route and configure AudioManager accordingly
    val routeResult = resolveAndApplyRoute(route, forTts = true)
    if (routeResult is PlaybackResult.Error) { onResult?.invoke(routeResult); return }

    // 2. Acquire audio focus before making sound
    if (!requestAudioFocus()) { restoreAudioRouting(); /* emit Error */; return }

    // 3. Observe TTS progress to update isPlaying state and clean up afterwards
    val utteranceId = UUID.randomUUID().toString()
    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String?) {
            mainHandler.post { _isPlaying.value = true }
        }
        override fun onDone(id: String?) {
            mainHandler.post {
                _isPlaying.value = false
                abandonAudioFocus()
                restoreAudioRouting()      // ← IMPORTANT: restores AudioManager state
                onResult?.invoke(routeResult)
                _lastResult.value = routeResult
            }
        }
        override fun onError(id: String?) { /* emit Error, clean up */ }
    })

    // 4. Speak — QUEUE_FLUSH cancels any currently-speaking utterance
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle().apply {
        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
    }, utteranceId)
}
```

**How audio file playback works** (`playAudio`):

```kotlin
fun playAudio(source: AudioSource, route: AudioRoute = AudioRoute.DEFAULT, ...) {
    stopInternal(restoreAudio = false)

    val routeResult = resolveAndApplyRoute(route, forTts = false)
    if (!requestAudioFocus()) { /* emit Error */; return }

    val player = MediaPlayer()
    // Always USAGE_MEDIA for MediaPlayer; device pinning via setPreferredDevice (API 28+)
    player.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    )
    // Set data source depending on AudioSource subtype (Resource / Uri / File)
    when (source) {
        is AudioSource.Resource  -> player.setDataSource(context, Uri.parse("android.resource://..."))
        is AudioSource.UriSource -> player.setDataSource(context, source.uri)
        is AudioSource.FileSource -> player.setDataSource(source.file.absolutePath)
    }
    // API 28+: pin stream to exact hardware device
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val deviceInfo = findAndroidDeviceInfoForResult(routeResult)
        if (deviceInfo != null) player.preferredDevice = deviceInfo
    }
    player.setOnPreparedListener { it.start(); _isPlaying.value = true }
    player.setOnCompletionListener { /* release, abandon focus, restore routing */ }
    player.prepareAsync()
    mediaPlayer = player
}
```

---

## Part 2 — Audio Device Management

### How devices are detected

```kotlin
fun refreshAvailableDevices() {
    val rawDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    val detected = rawDevices
        .filter { it.type in DevicePriorityManager.DEFAULT_PRIORITY }
        .map { info ->
            AudioDevice(
                id          = info.id,
                name        = resolveDeviceName(info),   // productName → label fallback
                type        = info.type,
                isConnected = true,
                isBluetooth = info.type == TYPE_BLUETOOTH_A2DP || info.type == TYPE_BLUETOOTH_SCO,
                isWired     = info.type in listOf(TYPE_WIRED_HEADPHONES, TYPE_WIRED_HEADSET, TYPE_USB_HEADSET),
                isSpeaker   = info.type == TYPE_BUILTIN_SPEAKER
            )
        }

    // Guarantee: synthesize built-in speaker entry if AudioManager omitted it (rare ROM quirk)
    val finalList = if (detected.any { it.isSpeaker }) detected
                    else detected + AudioDevice(/* synthetic speaker */)

    _availableDevices.value = finalList   // pushes to StateFlow → UI re-renders instantly
}
```

### How live connect/disconnect is tracked

```kotlin
// Registered in init {} on the main thread handler — fires automatically
private val deviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
        refreshAvailableDevices()   // re-scans → updates StateFlow → UI repaints
    }
    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
        refreshAvailableDevices()
    }
}
```
No polling. The OS calls these overrides immediately when hardware state changes.

### How the output device is selected and prioritized

**Route resolution** happens in `resolveAndApplyRoute()`. The cascade is:

```
1. Explicit route (SPEAKER / BLUETOOTH / WIRED_HEADSET)
      → checked directly against available connected devices
      → if unavailable: returns PlaybackResult.Fallback
2. AudioRoute.DEFAULT
      → DevicePriorityManager.resolveDevice(connectedDevices)
      → walks user-ordered list, picks first connected device
```

```kotlin
private fun resolveAndApplyRoute(route: AudioRoute, forTts: Boolean): PlaybackResult {
    val connected = _availableDevices.value
    return when (route) {
        AudioRoute.SPEAKER -> {
            if (forTts) applySpeakerModeForTts()
            PlaybackResult.Success(AudioRoute.SPEAKER, "Phone Speaker")
        }
        AudioRoute.BLUETOOTH -> {
            val bt = connected.firstOrNull { it.isBluetooth && it.isConnected }
            if (bt != null) {
                if (forTts) applyMediaModeForTts()
                PlaybackResult.Success(AudioRoute.BLUETOOTH, bt.name)
            } else {
                PlaybackResult.Fallback(
                    requestedRoute = AudioRoute.BLUETOOTH,
                    actualRoute    = AudioRoute.DEFAULT,
                    reason         = "No Bluetooth audio device is connected"
                )
            }
        }
        AudioRoute.WIRED_HEADSET -> {
            val wired = connected.firstOrNull { it.isWired && it.isConnected }
            if (wired != null) {
                if (forTts) applyMediaModeForTts()
                PlaybackResult.Success(AudioRoute.WIRED_HEADSET, wired.name)
            } else {
                PlaybackResult.Fallback(
                    requestedRoute = AudioRoute.WIRED_HEADSET,
                    actualRoute    = AudioRoute.DEFAULT,
                    reason         = "No wired headset or headphones are connected"
                )
            }
        }
        AudioRoute.DEFAULT -> {
            val priorityDevice = devicePriorityManager.resolveDevice(connected)
            if (priorityDevice != null) {
                val resolvedRoute = when {
                    priorityDevice.isSpeaker   -> AudioRoute.SPEAKER
                    priorityDevice.isBluetooth -> AudioRoute.BLUETOOTH
                    priorityDevice.isWired     -> AudioRoute.WIRED_HEADSET
                    else                       -> AudioRoute.DEFAULT
                }
                if (forTts) {
                    if (priorityDevice.isSpeaker) applySpeakerModeForTts()
                    else applyMediaModeForTts()
                }
                PlaybackResult.Success(resolvedRoute, priorityDevice.name)
            } else {
                PlaybackResult.Success(AudioRoute.DEFAULT, "System Default")
            }
        }
    }
}
```

### Priority list resolution

```kotlin
// DevicePriorityManager.kt
fun resolveDevice(connectedDevices: List<AudioDevice>): AudioDevice? {
    for (type in getPriorityList()) {    // walks list highest-priority first
        val device = connectedDevices.firstOrNull { it.type == type && it.isConnected }
        if (device != null) return device
    }
    return null
}

// Default order (overridable by user in UI):
val DEFAULT_PRIORITY = listOf(
    TYPE_BLUETOOTH_A2DP,   // 1st: Bluetooth wireless
    TYPE_BLUETOOTH_SCO,    // 2nd: Bluetooth hands-free
    TYPE_WIRED_HEADPHONES, // 3rd: 3.5mm headphones
    TYPE_WIRED_HEADSET,    // 4th: 3.5mm headset w/ mic
    TYPE_USB_HEADSET,      // 5th: USB audio
    TYPE_BUILTIN_SPEAKER   // 6th: always-present fallback
)
```

The list is stored in `SharedPreferences` as comma-separated type integers and is user-reorderable in the UI. Changes persist across app restarts.

### Speaker forcing — API-level handling

Routing TTS audio to the speaker requires special handling because `USAGE_MEDIA` naturally routes to headsets when connected. Two APIs are used based on the device's Android version:

```kotlin
private fun applySpeakerModeForTts() {
    // TTS AudioAttributes: VOICE_COMMUNICATION tells the engine to use the call path
    tts?.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {   // API 31+
        val speakerInfo = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == TYPE_BUILTIN_SPEAKER }
        if (speakerInfo != null) {
            audioManager.setCommunicationDevice(speakerInfo)
            communicationDeviceSet = true
        } else {
            applyLegacySpeakerMode()   // fallback even on API 31+
        }
    } else {                                                 // API 24–30
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

// Always called after playback ends — does NOT affect other apps permanently
private fun restoreAudioRouting() {
    if (!speakerRoutingApplied) return
    speakerRoutingApplied = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceSet) {
        audioManager.clearCommunicationDevice()
        communicationDeviceSet = false
    } else {
        audioManager.mode = savedAudioMode
        @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = savedSpeakerphoneOn
    }
}
```

### Disconnected device handling in the UI

When the user taps a disconnected device (Bluetooth or Wired while unplugged):

```kotlin
// In AudioRouterScreen — applies to both GlobalRouteSelector and MessageRouteChips
val onUnavailableClick: (String) -> Unit = { message ->
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()   // e.g. "Bluetooth Not Connected"
    viewModel.speakText(message, AudioRoute.DEFAULT)               // also spoken via TTS
}

// Routing logic blocks the selection:
onClick = {
    if (isAvailable) onSelect(route)                            // connected → select normally
    else onUnavailableClick(option.notConnectedMsg!!)           // not connected → toast + TTS
}
```

Device availability is checked directly against the live `availableDevices` StateFlow:
```kotlin
val hasBluetooth = availableDevices.any { it.isBluetooth && it.isConnected }
val hasWired     = availableDevices.any { it.isWired && it.isConnected }
```

---

## Supporting Data Contracts

```kotlin
// Route options
enum class AudioRoute { DEFAULT, SPEAKER, BLUETOOTH, WIRED_HEADSET }

// Playback outcome — always returned, never null after first play
sealed class PlaybackResult {
    data class Success(val route: AudioRoute, val deviceName: String) : PlaybackResult()
    data class Fallback(val requestedRoute: AudioRoute, val actualRoute: AudioRoute,
                        val reason: String) : PlaybackResult()
    data class Error(val message: String) : PlaybackResult()
}

// Audio content descriptor
sealed class AudioSource {
    data class Resource(val resId: Int) : AudioSource()    // R.raw.*
    data class UriSource(val uri: Uri) : AudioSource()
    data class FileSource(val file: File) : AudioSource()
}
```

---

