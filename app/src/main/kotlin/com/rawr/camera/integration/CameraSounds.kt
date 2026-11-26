package com.rawr.camera.integration

import android.media.MediaActionSound

/**
 * Rawr 17U fork: the system's camera sounds (Android's MediaActionSound: the shutter click and the video start /
 * stop tones the stock camera apps use). Played only when Settings → Photo → Shutter Sound is on, or always where
 * the law asks for the shutter sound ([MediaActionSound.mustPlayShutterSound], for example Japan and Korea).
 */
object CameraSounds {
    private val sound: MediaActionSound by lazy {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    private fun required(): Boolean = runCatching { MediaActionSound.mustPlayShutterSound() }.getOrDefault(false)

    private fun play(kind: Int, enabled: Boolean) {
        if (!enabled && !required()) return
        runCatching { sound.play(kind) }
    }

    /** Loads the sounds ahead of the first press, so the first click is not late. */
    fun prepare(enabled: Boolean) {
        if (enabled || required()) runCatching { sound }
    }

    fun shutter(enabled: Boolean) = play(MediaActionSound.SHUTTER_CLICK, enabled)
    /**
     * The video start tone, played before the microphone opens and waited out, so it is not recorded into the
     * video. Blocks for [START_TONE_MS]: call it off the main thread, once the recording's own checks have passed.
     */
    fun videoStartBeforeRecording(enabled: Boolean) {
        if (!enabled && !required()) return
        runCatching { sound.play(MediaActionSound.START_VIDEO_RECORDING) }
        Thread.sleep(START_TONE_MS)
    }

    /** The start tone lasts about 0.35 s on the 17 Ultra; the rest covers the time it takes to begin playing. */
    private const val START_TONE_MS = 500L
    fun videoStop(enabled: Boolean) = play(MediaActionSound.STOP_VIDEO_RECORDING, enabled)
}
