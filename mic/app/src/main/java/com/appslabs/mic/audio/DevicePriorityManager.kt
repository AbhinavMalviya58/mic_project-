package com.appslabs.mic.audio

import android.content.Context
import android.media.AudioDeviceInfo

/**
 * Persists and resolves the user's ordered device-type priority list.
 *
 * When the active route is [AudioRoute.DEFAULT] on both the per-message and global
 * preferred tiers, [AudioPlaybackManager] calls [resolveDevice] to pick the
 * highest-priority currently-connected device.
 *
 * Storage: SharedPreferences (comma-separated list of [AudioDeviceInfo] type integers).
 * No Room / third-party dependency required.
 */
class DevicePriorityManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the current priority list (device type integers, highest priority first). */
    fun getPriorityList(): List<Int> {
        val stored = prefs.getString(KEY_PRIORITY, null)
        return if (stored.isNullOrEmpty()) {
            DEFAULT_PRIORITY
        } else {
            try {
                stored.split(",").mapNotNull { it.trim().toIntOrNull() }
                    .takeIf { it.isNotEmpty() } ?: DEFAULT_PRIORITY
            } catch (_: Exception) {
                DEFAULT_PRIORITY
            }
        }
    }

    /** Overwrites the full priority list. */
    fun setPriorityList(types: List<Int>) {
        prefs.edit().putString(KEY_PRIORITY, types.joinToString(",")).apply()
    }

    /** Moves the given device type one position higher in priority (if not already first). */
    fun moveUp(type: Int) {
        val list = getPriorityList().toMutableList()
        val idx = list.indexOf(type)
        if (idx > 0) {
            list.removeAt(idx)
            list.add(idx - 1, type)
            setPriorityList(list)
        }
    }

    /** Moves the given device type one position lower in priority (if not already last). */
    fun moveDown(type: Int) {
        val list = getPriorityList().toMutableList()
        val idx = list.indexOf(type)
        if (idx in 0 until list.lastIndex) {
            list.removeAt(idx)
            list.add(idx + 1, type)
            setPriorityList(list)
        }
    }

    /**
     * Walks the priority list from the top and returns the first [AudioDevice]
     * whose [AudioDevice.type] matches a connected entry, or null if none matched.
     * The built-in speaker always matches if it reaches the end of the list.
     */
    fun resolveDevice(connectedDevices: List<AudioDevice>): AudioDevice? {
        val priority = getPriorityList()
        for (type in priority) {
            val device = connectedDevices.firstOrNull { it.type == type && it.isConnected }
            if (device != null) return device
        }
        return null
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val PREFS_NAME = "audio_device_priority"
        private const val KEY_PRIORITY = "device_type_order"

        /**
         * Default priority: external Bluetooth first, then wired, then the built-in speaker.
         * The built-in speaker is always listed last so there is always a fallback.
         */
        val DEFAULT_PRIORITY: List<Int> = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )

        /** Human-readable labels for each managed device type. */
        val DEVICE_TYPE_LABELS: Map<Int, String> = mapOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP     to "Bluetooth A2DP",
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO      to "Bluetooth SCO",
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES   to "Wired Headphones",
            AudioDeviceInfo.TYPE_WIRED_HEADSET      to "Wired Headset",
            AudioDeviceInfo.TYPE_USB_HEADSET        to "USB Headset",
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER    to "Phone Speaker"
        )

        /** Emoji icons shown in the priority list UI. */
        val DEVICE_TYPE_ICONS: Map<Int, String> = mapOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP     to "🎧",
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO      to "🎧",
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES   to "🎵",
            AudioDeviceInfo.TYPE_WIRED_HEADSET      to "🔌",
            AudioDeviceInfo.TYPE_USB_HEADSET        to "🔌",
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER    to "🔊"
        )
    }
}
