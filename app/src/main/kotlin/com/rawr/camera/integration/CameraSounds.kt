package com.rawr.camera.integration

import android.media.MediaActionSound
import android.util.Log

/**
 * Rawr 17U fork: the system's camera sounds (Android's MediaActionSound: the shutter click and the video start /
 * stop tones the stock camera apps use). Played only when Settings → Photo → Shutter Sound is on, or always where
 * the system asks for the shutter sound ([MediaActionSound.mustPlayShutterSound], for example Japan and Korea) and
 * Settings → Photo → Follow System Shutter Rule is on (the default). Some phones report that requirement outside the
 * regions it is meant for, which left the sound on whatever the switch said, hence the second setting.
 */
object CameraSounds {
    private val sound: MediaActionSound by lazy {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    /** Settings → Photo → Follow System Shutter Rule; CaptureViewModel keeps it current. */
    @Volatile
    var followSystem: Boolean = true

    /** What the system says, whatever the setting: true where the sound must play (the Settings screen shows it). */
    fun requiredBySystem(): Boolean = runCatching { MediaActionSound.mustPlayShutterSound() }.getOrDefault(false)

    private fun required(): Boolean = followSystem && requiredBySystem()

    private fun play(kind: Int, enabled: Boolean) {
        val mustPlay = required()
        Log.i(TAG, "sound kind=$kind switch=$enabled systemRequires=${requiredBySystem()} followSystem=$followSystem -> ${enabled || mustPlay}")
        if (!enabled && !mustPlay) return
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
        Log.i(TAG, "video start tone switch=$enabled systemRequires=${requiredBySystem()} followSystem=$followSystem -> ${enabled || required()}")
        if (!enabled && !required()) return
        runCatching { sound.play(MediaActionSound.START_VIDEO_RECORDING) }
        Thread.sleep(START_TONE_MS)
    }

    /** The start tone lasts about 0.35 s on the 17 Ultra; the rest covers the time it takes to begin playing. */
    private const val START_TONE_MS = 500L
    private const val TAG = "RawrCameraSounds"
    fun videoStop(enabled: Boolean) = play(MediaActionSound.STOP_VIDEO_RECORDING, enabled)
}
