package com.rawr.camera.lofic

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import com.rawr.camera.BuildConfig
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.log2

/**
 * Photos of the "1x LOFIC" lens (L2): takes the next Long/Short pair from the running viewfinder session, merges it
 * with the measured k into a float16 DNG (DCIM/Camera, RAWR_..._LOFIC.dng) and hands it to the Renderer for the JPEG.
 * The merge, noise model and DNG writer are the LOFIC screen's. From Anton Tretiakov's (@designation_zero) X17U
 * LOFIC RAW v1.0 spec; needs his root module.
 */
internal object LoficSaver {
    private const val TAG = "RawrLofic"
    private const val CAMERA_ID = "2"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lofic-photo") }
    @Volatile private var busy = false

    /** True when the shutter belongs to a LOFIC session; the photo is then taken here. */
    fun handles(): Boolean = try { LoficNative.active() } catch (e: Throwable) { false }

    fun capture(context: Context, onDone: (String) -> Unit = {}) {
        if (busy) {
            onDone("LOFIC: still saving the previous photo")
            return
        }
        busy = true
        val app = context.applicationContext
        LoficNative.request()
        worker.execute {
            try {
                onDone(save(app))
            } catch (e: Throwable) {
                Log.w(TAG, "LOFIC photo", e)
                onDone("LOFIC photo failed: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun matrix(t: ColorSpaceTransform?): Array<Rational>? =
        t?.let { m -> Array(9) { i -> m.getElement(i % 3, i / 3) } }

    private fun save(context: Context): String {
        val pair = LoficNative.waitPair(1500) ?: return "LOFIC: no Long/Short pair from the camera".also {
            Log.w(TAG, "LOFIC_PHOTO_FAILED reason=no_pair")
        }
        val longBytes = pair[0] as ByteArray
        val shortBytes = pair[1] as ByteArray
        val ints = pair[2] as IntArray
        val longs = pair[3] as LongArray
        val neutralValues = pair[4] as FloatArray
        val width = ints[0]
        val rows = ints[1]
        val iso = ints[2].takeIf { it > 0 } ?: 100
        val matched = ints[3] == 1
        val exposureNs = longs[1]
        if (rows in 16 until LoficSetup.RAW_HEIGHT) {
            // Without the root module the HAL reads a 16:9 LOFIC mode (2304 rows) with no real Short gain.
            Log.w(TAG, "LOFIC_PHOTO_FAILED reason=module_missing rows=$rows")
            return "LOFIC: the X17U LOFIC RAW module is not active (16:9 readout, $rows rows) - no LOFIC photo"
        }
        if (width != LoficSetup.RAW_WIDTH || rows < 16 ||
            longBytes.size < rows * LoficSetup.LONG_ROW_BYTES || shortBytes.size < rows * LoficSetup.SHORT_ROW_BYTES) {
            Log.w(TAG, "LOFIC_PHOTO_FAILED reason=size width=$width rows=$rows long=${longBytes.size} short=${shortBytes.size}")
            return "LOFIC: unexpected frame size ${width}x$rows"
        }
        val ratio = LoficMerge.estimateK(longBytes, shortBytes, rows, iso)
        val model = LoficSetup.modelK(iso)
        val baseline = log2(ratio.k * LoficMerge.SHORT_RANGE / LoficMerge.LONG_CLIP)
        val c = (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(CAMERA_ID)
        val neutral = neutralValues.takeIf { n -> n.all { it > 0f } }
            ?.map { Rational((it * 1_000_000f).toInt(), 1_000_000) }?.toTypedArray()
        val orientation = when (c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }
        val writer = LoficDngWriter(
            width = width,
            height = rows,
            make = Build.MANUFACTURER,
            model = Build.MODEL,
            uniqueModel = "${Build.MANUFACTURER} ${Build.MODEL} / LOFIC 1x / camera $CAMERA_ID",
            software = "Rawr v${BuildConfig.VERSION_NAME} LOFIC",
            orientation = orientation,
            exposureNs = exposureNs,
            iso = iso,
            baselineExposure = baseline,
            colorMatrix1 = matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
            colorMatrix2 = matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
            forwardMatrix1 = matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)),
            forwardMatrix2 = matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)),
            calibration1 = matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
            calibration2 = matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
            illuminant1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
            illuminant2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            asShotNeutral = neutral,
            noiseProfile = LoficMerge.noiseProfile(ratio.k, iso)
        )
        val name = "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date()) + "_LOFIC.dng"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore insert failed")
        val l = IntArray(width)
        val sh = IntArray(width)
        (resolver.openOutputStream(uri) ?: error("no output stream")).use { raw ->
            BufferedOutputStream(raw, 1 shl 20).use { out ->
                writer.write(out) { y, row ->
                    LoficMerge.unpackLong(longBytes, y, l)
                    LoficMerge.unpackShort(shortBytes, y, sh)
                    LoficMerge.mergeRow(l, sh, ratio.k, row)
                }
            }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        val jpeg = queueJpeg(context, uri, name)
        val headroom = log2(LoficMerge.SHORT_RANGE * ratio.k / LoficMerge.LONG_CLIP)
        val module = ratio.measured && ratio.k > 0.75 * model && ratio.k < 1.25 * model
        Log.i(TAG, ("LOFIC_PHOTO_SAVED name=$name rows=$rows k=%.1f measured=${ratio.measured} samples=${ratio.samples} " +
            "model=%.1f iso=$iso exposureNs=$exposureNs headroom=%.2f module=$module result=${if (matched) "matched" else "nearest"} " +
            "jpeg=$jpeg neutral=${neutralValues.joinToString(",") { "%.3f".format(Locale.ROOT, it) }}")
            .format(Locale.ROOT, ratio.k, model, headroom))
        return "LOFIC: saved $name (%.1f stops above the Long clip)".format(Locale.ROOT, headroom)
    }

    /** Queues the DNG's JPEG (Renderer: import, current look, JPEG export), as the LOFIC screen does. */
    private fun queueJpeg(context: Context, uri: android.net.Uri, name: String): String = try {
        LoficJpegQueue.add(context, uri, name)
    } catch (e: Exception) {
        Log.w(TAG, "jpeg", e)
        "failed (${e.message?.replace("%", " pct")})"
    }
}
