package com.rawr.camera.settings.preferences

import java.io.InputStream
import org.json.JSONTokener

/**
 * Rawr 17U fork: reads the processing recipe a Rawr photo carries, so its film look can be imported as a preset.
 * A DNG has it in its provenance (DngProvenance.cpp, Ed's code); a JPEG in its EXIF MakerNote (JpegExif.cpp, the
 * fork's). Both store it under the same key, as a JSON string value, so one streaming scan finds it in either.
 */
internal object PhotoRecipe {
    private val marker = "\"com.rawrcam.processing.recipe.v1\":\"".toByteArray(Charsets.US_ASCII)
    private const val MAX_RECIPE_BYTES = 1 shl 20

    /** True for the first bytes of a JPEG or a TIFF/DNG. */
    fun looksLikePhoto(head: ByteArray): Boolean = head.size >= 4 && (
        (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) ||
            (head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 42.toByte() && head[3] == 0.toByte()) ||
            (head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() && head[2] == 0.toByte() && head[3] == 42.toByte()))

    /** The recipe JSON, or null when the file carries none. Reads the stream once, in chunks. */
    fun find(input: InputStream): String? {
        val buffer = ByteArray(1 shl 16)
        var matched = 0
        var escaped = false
        var value: java.io.ByteArrayOutputStream? = null
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return null
            for (i in 0 until n) {
                val b = buffer[i]
                val out = value
                if (out == null) {
                    matched = when {
                        b == marker[matched] -> matched + 1
                        b == marker[0] -> 1
                        else -> 0
                    }
                    if (matched == marker.size) value = java.io.ByteArrayOutputStream()
                    continue
                }
                if (!escaped && b == '"'.code.toByte()) {
                    val literal = "\"" + String(out.toByteArray(), Charsets.UTF_8) + "\""
                    return JSONTokener(literal).nextValue() as? String
                }
                escaped = !escaped && b == '\\'.code.toByte()
                out.write(b.toInt())
                if (out.size() > MAX_RECIPE_BYTES) return null
            }
        }
    }
}
