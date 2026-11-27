package com.rawr.camera.renderer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rawr 17U fork: whether a DNG's first IFD holds float CFA samples (SampleFormat 3,
 * as the LOFIC DNGs are written) and its BaselineExposure. Read from the TIFF
 * header only; null when the file is not a readable TIFF.
 */
internal data class FloatDngInfo(val floatSamples: Boolean, val baselineExposure: Double?) {
    companion object {
        private const val SAMPLE_FORMAT = 339
        private const val BASELINE_EXPOSURE = 50730

        fun read(file: File): FloatDngInfo? = try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(8)
                raf.readFully(header)
                val order = when {
                    header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                    header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                    else -> return null
                }
                val head = ByteBuffer.wrap(header).order(order)
                if (head.getShort(2).toInt() != 42) return null
                val ifd = head.getInt(4).toLong() and 0xffffffffL
                if (ifd < 8 || ifd + 2 > raf.length()) return null
                raf.seek(ifd)
                val countBytes = ByteArray(2)
                raf.readFully(countBytes)
                val count = ByteBuffer.wrap(countBytes).order(order).short.toInt() and 0xffff
                if (count == 0 || ifd + 2 + count * 12L > raf.length()) return null
                val entries = ByteArray(count * 12)
                raf.readFully(entries)
                val e = ByteBuffer.wrap(entries).order(order)
                var floatSamples = false
                var baseline: Double? = null
                for (i in 0 until count) {
                    val at = i * 12
                    val tag = e.getShort(at).toInt() and 0xffff
                    val type = e.getShort(at + 2).toInt() and 0xffff
                    if (tag == SAMPLE_FORMAT && type == 3) {
                        floatSamples = (e.getShort(at + 8).toInt() and 0xffff) == 3
                    } else if (tag == BASELINE_EXPOSURE && type == 10) {
                        val offset = e.getInt(at + 8).toLong() and 0xffffffffL
                        if (offset + 8 <= raf.length()) {
                            val value = ByteArray(8)
                            raf.seek(offset)
                            raf.readFully(value)
                            val v = ByteBuffer.wrap(value).order(order)
                            val den = v.getInt(4)
                            if (den != 0) baseline = v.getInt(0).toDouble() / den
                        }
                    }
                }
                FloatDngInfo(floatSamples, baseline?.takeIf { it.isFinite() })
            }
        } catch (ignored: java.io.IOException) {
            null
        }
    }
}
