package com.rawr.camera.integration

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The speech model Voice Shutter listens with (Vosk small English, Apache-2.0), bundled as a zip asset at build time
 * (app/build.gradle.kts, generateVoiceModelAssets) and unpacked here once: about 68 MB in the app's private files.
 */
internal object VoiceModelStore {
    private const val ASSET = "voice/vosk-model.zip"
    private const val MODEL_DIR = "vosk-model-small-en-us-0.15"
    private const val COMPLETE = ".complete"

    /** The unpacked model directory, unpacking it first if needed (blocking: call off the main thread). Null if it fails. */
    fun ensure(context: Context): File? = runCatching {
        val root = File(context.filesDir, "voice").apply { mkdirs() }
        val dir = File(root, MODEL_DIR)
        if (File(dir, COMPLETE).exists()) return@runCatching dir
        val tmp = File(root, "$MODEL_DIR.tmp")
        tmp.deleteRecursively()
        dir.deleteRecursively()
        tmp.mkdirs()
        val tmpPath = tmp.canonicalPath + File.separator
        context.assets.open(ASSET).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(tmp, entry.name)
                    // A zip entry must not climb out of the target directory.
                    require(target.canonicalPath.startsWith(tmpPath)) { "bad zip entry ${entry.name}" }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val unpacked = File(tmp, MODEL_DIR)
        require(unpacked.isDirectory) { "model directory missing from the zip" }
        File(unpacked, COMPLETE).writeText("ok")
        require(unpacked.renameTo(dir)) { "could not move the unpacked model" }
        tmp.deleteRecursively()
        dir
    }.onFailure { android.util.Log.w("RawrVoiceShutter", "speech model unpack failed", it) }.getOrNull()
}
