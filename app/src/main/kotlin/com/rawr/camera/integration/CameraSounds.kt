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
    fun videoStart(enabled: Boolean) = play(MediaActionSound.START_VIDEO_RECORDING, enabled)
    fun videoStop(enabled: Boolean) = play(MediaActionSound.STOP_VIDEO_RECORDING, enabled)
}
