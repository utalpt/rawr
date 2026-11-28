package com.rawr.camera.integration

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Rawr 17U fork: Voice Shutter. Listens through the microphone with an offline speech recognizer (Vosk, small English model,
 * the whole vocabulary) and calls [onKeyword] when the viewfinder hears "cheese" or "capture" on its own (VoiceKeywordGate).
 *
 * The caller owns when it runs: start() while the viewfinder is in front, in Photo mode, with the microphone permission;
 * stop() otherwise (video needs the microphone for itself, and Settings is a separate screen). Nothing is recorded or
 * stored: the audio goes to the recognizer and is dropped. [onKeyword] is called on this class's own thread.
 */
internal class VoiceShutter(private val appContext: Context, private val onKeyword: (String) -> Unit) {
    @Volatile private var generation = 0
    private var thread: Thread? = null

    @Synchronized fun start() {
        if (thread?.isAlive == true) return
        val mine = ++generation
        thread = Thread({ listen(mine) }, "RawrVoiceShutter").also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized fun stop() {
        generation++
        thread?.interrupt()
        thread = null
    }

    private fun active(mine: Int) = mine == generation

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO before start()
    private fun listen(mine: Int) {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var audio: AudioRecord? = null
        try {
            val began = SystemClock.elapsedRealtime()
            val dir = VoiceModelStore.ensure(appContext) ?: return
            if (!active(mine)) return
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(dir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            if (!active(mine)) return
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audio = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK * 2 * 4))
                .build()
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "microphone not available")
                return
            }
            audio.startRecording()
            Log.i(TAG, "listening (ready in ${SystemClock.elapsedRealtime() - began} ms)")
            val buffer = ShortArray(CHUNK)
            val gate = VoiceKeywordGate()
            while (active(mine) && !Thread.currentThread().isInterrupted) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                val isFinal = recognizer.acceptWaveForm(buffer, read)
                val text = runCatching {
                    if (isFinal) JSONObject(recognizer.result).optString("text") else JSONObject(recognizer.partialResult).optString("partial")
                }.getOrDefault("")
                val word = gate.onHypothesis(text, isFinal, SystemClock.elapsedRealtime())
                if (word != null && active(mine)) {
                    Log.i(TAG, "heard \"$word\"")
                    recognizer.reset()
                    gate.reset()
                    onKeyword(word)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "voice shutter stopped", t)
        } finally {
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            Log.i(TAG, "stopped")
        }
    }

    private companion object {
        const val TAG = "RawrVoiceShutter"
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1600 // 100 ms
    }
}
