package com.rawr.camera.lofic

/**
 * Rawr 17U fork: the LOFIC pair of the Xiaomi 17 Ultra main camera, from Anton Tretiakov's (@designation_zero)
 * X17U LOFIC RAW v1.0 integration specification. Two RAW_SENSOR buffers arrive per frame; they hold packed MIPI data
 * with the rows back to back (the row stride the buffer reports does not describe it): the Long exposure (DCG,
 * RAW14) and the Short one (LOFIC overflow, RAW10, Long exposure / 12.55). Which reader gets which is not fixed.
 */
internal object LoficMerge {
    /** Black-subtracted Long value where Long clips (16383 - 1024 = 15359). */
    const val LONG_CLIP = 15359.0

    /** Black-subtracted Short value where Short clips (1023 - 64 = 959). */
    const val SHORT_RANGE = 959.0

    /** Index of the last non-zero byte at or below [from], scanning back; -1 when all zero. */
    fun lastNonZero(data: java.nio.ByteBuffer, from: Int): Int {
        var i = minOf(from, data.capacity() - 1)
        while (i >= 0 && data.get(i).toInt() == 0) i--
        return i
    }

    /**
     * Rows filled in a buffer of [rowBytes] per row, from its used length: the last non-zero byte at or below the end
     * of [maxRows] rows (plus slack). The last byte of a row may be 0, so a partial row counts as filled.
     */
    fun filledRows(data: java.nio.ByteBuffer, rowBytes: Int, maxRows: Int): Int {
        val last = lastNonZero(data, rowBytes * maxRows + 4095)
        return if (last < 0) 0 else (last + rowBytes) / rowBytes
    }

    /**
     * True when [data] is the Long stream of a setup with [rows] rows: its data reaches past the end of the Short
     * stream's (rows x 5120 bytes), right up to rows x 7168 bytes.
     */
    fun isLong(data: java.nio.ByteBuffer, rows: Int): Boolean {
        val end = rows * LoficSetup.LONG_ROW_BYTES
        val start = rows * LoficSetup.SHORT_ROW_BYTES + 4096
        val from = minOf(end - 1, data.capacity() - 1)
        var i = from
        val stop = maxOf(start, from - 65536)
        while (i >= stop) {
            if (data.get(i).toInt() != 0) return true
            i--
        }
        return false
    }

    /** One RAW14 row (7 bytes per 4 pixels) to black-subtracted values, clipped at the Long clip. */
    fun unpackLong(src: ByteArray, row: Int, out: IntArray) {
        var o = row * LoficSetup.LONG_ROW_BYTES
        var x = 0
        while (x < LoficSetup.RAW_WIDTH) {
            val lsb = (src[o + 4].toInt() and 0xff) or ((src[o + 5].toInt() and 0xff) shl 8) or
                ((src[o + 6].toInt() and 0xff) shl 16)
            for (i in 0 until 4) {
                val v = ((src[o + i].toInt() and 0xff) shl 6) or ((lsb shr (6 * i)) and 63)
                out[x + i] = (v - LoficSetup.LONG_BLACK).coerceIn(0, LONG_CLIP.toInt())
            }
            o += 7
            x += 4
        }
    }

    /** One RAW10 row (5 bytes per 4 pixels) to black-subtracted values. */
    fun unpackShort(src: ByteArray, row: Int, out: IntArray) {
        var o = row * LoficSetup.SHORT_ROW_BYTES
        var x = 0
        while (x < LoficSetup.RAW_WIDTH) {
            val lsb = src[o + 4].toInt() and 0xff
            for (i in 0 until 4) {
                val v = ((src[o + i].toInt() and 0xff) shl 2) or ((lsb shr (2 * i)) and 3)
                out[x + i] = (v - LoficSetup.SHORT_BLACK).coerceAtLeast(0)
            }
            o += 5
            x += 4
        }
    }

    class Ratio(val k: Double, val measured: Boolean, val samples: Long)

    /**
     * The Long/Short ratio of this frame: over the pixels whose Long value is well inside its range
     * (2000 < L < 14500), k = sum(L) / sum(S). Fewer than 20 000 such pixels (a flat, dark or clipped scene): the model
     * 212 x Long analog gain, valid with the root module only. Every second row is enough.
     */
    fun estimateK(long: ByteArray, short: ByteArray, rows: Int, iso: Int): Ratio {
        val l = IntArray(LoficSetup.RAW_WIDTH)
        val s = IntArray(LoficSetup.RAW_WIDTH)
        var sumL = 0.0
        var sumS = 0.0
        var n = 0L
        var row = 0
        while (row < rows) {
            unpackLong(long, row, l)
            unpackShort(short, row, s)
            for (x in 0 until LoficSetup.RAW_WIDTH) {
                val lv = l[x]
                if (lv in 2001..14499) {
                    sumL += lv
                    sumS += s[x]
                    n++
                }
            }
            row += 2
        }
        return if (n >= 20_000 && sumS > 0) Ratio(sumL / sumS, true, n)
        else Ratio(LoficSetup.modelK(iso), false, n)
    }

    /**
     * One merged row on the Long scale, normalised so 1.0 = k x 959 (the Short clip): the Long value below the merge
     * band, k x Short above it, a linear ramp between 11000 and 14000 (black-subtracted Long values).
     */
    fun mergeRow(l: IntArray, s: IntArray, k: Double, out: FloatArray) {
        val scale = 1.0 / (k * SHORT_RANGE)
        for (x in 0 until LoficSetup.RAW_WIDTH) {
            val lv = l[x].toDouble()
            val w = ((lv - 11000.0) / 3000.0).coerceIn(0.0, 1.0)
            out[x] = (((1.0 - w) * lv + w * k * s[x]) * scale).toFloat()
        }
    }

    /**
     * DNG NoiseProfile (S, O) of the merged frame, normalized (1.0 = k x 959 Long DN),
     * for its Long part: variance = (0.007 x ISO + 0.1) x L + 4 x (ISO / 100)^2 in Long DN,
     * measured on flat areas of the 27 Nov test DNGs (ISO 51-631). The k x Short part is
     * noisier than this (a model for it is still to come).
     */
    fun noiseProfile(k: Double, iso: Int): DoubleArray {
        val scale = k * SHORT_RANGE
        val shot = 0.007 * iso + 0.1
        val read = 4.0 * (iso / 100.0) * (iso / 100.0)
        return doubleArrayOf(shot / scale, read / (scale * scale))
    }
}
