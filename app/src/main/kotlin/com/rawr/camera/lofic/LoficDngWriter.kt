package com.rawr.camera.lofic

import android.util.Half
import android.util.Rational
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rawr 17U fork: a linear CFA DNG (DNG 1.4) of the merged LOFIC frame in 16-bit floating point (SampleFormat 3), the
 * format of Adobe's HDR-merge DNGs, as Anton Tretiakov's (@designation_zero) X17U LOFIC RAW specification describes:
 * BGGR, black 0, 1.0 = k x 959, BaselineExposure so the Long clip renders as white. No lens-shading correction.
 */
internal class LoficDngWriter(
    private val width: Int,
    private val height: Int,
    private val make: String,
    private val model: String,
    private val uniqueModel: String,
    private val software: String,
    private val orientation: Int,
    private val exposureNs: Long,
    private val iso: Int,
    private val baselineExposure: Double,
    private val colorMatrix1: Array<Rational>?,
    private val colorMatrix2: Array<Rational>?,
    private val forwardMatrix1: Array<Rational>?,
    private val forwardMatrix2: Array<Rational>?,
    private val calibration1: Array<Rational>?,
    private val calibration2: Array<Rational>?,
    private val illuminant1: Int?,
    private val illuminant2: Int?,
    private val asShotNeutral: Array<Rational>?
) {
    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private val entries = ArrayList<Entry>()

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun short(tag: Int, vararg v: Int) {
        val b = le(2 * v.size)
        v.forEach { b.putShort(it.toShort()) }
        entries += Entry(tag, 3, v.size, b.array())
    }

    private fun long(tag: Int, vararg v: Long) {
        val b = le(4 * v.size)
        v.forEach { b.putInt(it.toInt()) }
        entries += Entry(tag, 4, v.size, b.array())
    }

    private fun bytes(tag: Int, vararg v: Int) {
        entries += Entry(tag, 1, v.size, ByteArray(v.size) { v[it].toByte() })
    }

    private fun ascii(tag: Int, s: String) {
        val a = (s + "\u0000").toByteArray(Charsets.US_ASCII)
        entries += Entry(tag, 2, a.size, a)
    }

    private fun rational(tag: Int, signed: Boolean, v: Array<Rational>) {
        val b = le(8 * v.size)
        v.forEach {
            b.putInt(it.numerator)
            b.putInt(it.denominator)
        }
        entries += Entry(tag, if (signed) 10 else 5, v.size, b.array())
    }

    private fun srational(tag: Int, v: Double) {
        val den = 1_000_000
        rational(tag, true, arrayOf(Rational(Math.round(v * den).toInt(), den)))
    }

    /** Exposure time in microseconds, exact for the HAL's nanosecond values to the microsecond. */
    private fun exposureRational(): Rational = Rational(maxOf(1L, exposureNs / 1000L).toInt(), 1_000_000)

    private fun buildEntries(dataOffset: Long) {
        entries.clear()
        long(254, 0)
        long(256, width.toLong())
        long(257, height.toLong())
        short(258, 16)
        short(259, 1)
        short(262, 32803)
        ascii(271, make)
        ascii(272, model)
        long(273, dataOffset)
        short(274, orientation)
        short(277, 1)
        long(278, height.toLong())
        long(279, width.toLong() * height * 2)
        short(284, 1)
        ascii(305, software)
        short(339, 3)
        short(33421, 2, 2)
        bytes(33422, 2, 1, 1, 0)
        rational(33434, false, arrayOf(exposureRational()))
        short(34855, iso.coerceIn(0, 65535))
        bytes(50706, 1, 4, 0, 0)
        bytes(50707, 1, 4, 0, 0)
        ascii(50708, uniqueModel)
        short(50714, 0)
        colorMatrix1?.let { rational(50721, true, it) }
        colorMatrix2?.let { rational(50722, true, it) }
        calibration1?.let { rational(50723, true, it) }
        calibration2?.let { rational(50724, true, it) }
        asShotNeutral?.let { rational(50728, false, it) }
        srational(50730, baselineExposure)
        illuminant1?.let { short(50778, it) }
        illuminant2?.let { short(50779, it) }
        forwardMatrix1?.let { rational(50964, true, it) }
        forwardMatrix2?.let { rational(50965, true, it) }
        entries.sortBy { it.tag }
    }

    /** The header (TIFF header, IFD0 and its out-of-line values); the pixel data follows it directly. */
    private fun header(): ByteArray {
        // Two passes: the data offset depends on the header size, which does not depend on the offset's value.
        buildEntries(0)
        val ifdSize = 2 + entries.size * 12 + 4
        val extra = entries.sumOf { if (it.data.size > 4) (it.data.size + 1) and 1.inv() else 0 }
        val dataOffset = 8L + ifdSize + extra
        buildEntries(dataOffset)
        val out = ByteArrayOutputStream(dataOffset.toInt())
        val head = le(8)
        head.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        out.write(head.array())
        val ifd = le(ifdSize)
        ifd.putShort(entries.size.toShort())
        var valueOffset = 8 + ifdSize
        val values = ByteArrayOutputStream()
        for (e in entries) {
            ifd.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
            if (e.data.size <= 4) {
                val inline = ByteArray(4)
                e.data.copyInto(inline)
                ifd.put(inline)
            } else {
                ifd.putInt(valueOffset)
                values.write(e.data)
                if (e.data.size and 1 == 1) values.write(0)
                valueOffset += (e.data.size + 1) and 1.inv()
            }
        }
        ifd.putInt(0)
        out.write(ifd.array())
        out.write(values.toByteArray())
        check(out.size().toLong() == dataOffset) { "DNG header size ${out.size()} != $dataOffset" }
        return out.toByteArray()
    }

    /** Writes the DNG; [row] fills one merged row (width values, 1.0 = k x 959) for each row index in turn. */
    fun write(out: OutputStream, row: (Int, FloatArray) -> Unit) {
        out.write(header())
        val values = FloatArray(width)
        val bytes = ByteArray(width * 2)
        for (y in 0 until height) {
            row(y, values)
            var o = 0
            for (x in 0 until width) {
                val h = Half.toHalf(values[x]).toInt()
                bytes[o] = h.toByte()
                bytes[o + 1] = (h shr 8).toByte()
                o += 2
            }
            out.write(bytes)
        }
        out.flush()
    }
}
