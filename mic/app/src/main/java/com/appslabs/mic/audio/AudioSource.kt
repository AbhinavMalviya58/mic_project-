package com.appslabs.mic.audio

import android.net.Uri
import java.io.File

/**
 * Describes the source of audio content for [AudioPlaybackManager.playAudio].
 */
sealed class AudioSource {
    /** A raw resource identifier (e.g. R.raw.notification_sound). */
    data class Resource(val resId: Int) : AudioSource()

    /** A content or file URI (e.g. from a media picker). */
    data class UriSource(val uri: Uri) : AudioSource()

    /** An absolute path to a file on device storage. */
    data class FileSource(val file: File) : AudioSource()
}
