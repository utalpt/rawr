package com.rawr.camera.ui

import android.os.Build
import com.rawr.camera.integration.SystemPropertiesUtil

/**
 * How far the phone's colour sensor sits from the light it is pointed at, as a mired offset added to
 * the temperature the SENSOR white balance offers.
 *
 * On the Xiaomi 17 Ultra the rear colour sensor reports 5900 K for a 5600 K reference (utalpt's
 * measurement, audit item 4): tapping SENSOR therefore seeds the manual WB about 9 mired too warm.
 * The offset is mired, not kelvin: 1 000 000 / 5600 - 1 000 000 / 5900 = +9.1 mired, while the same
 * -300 K at 3300 K would be 27 mired and at 9000 K would be only 3.7. A single kelvin number would
 * therefore over-correct the warm end of the rail and under-correct the cool end; the mired offset
 * is the same error everywhere, which is what a sensor bias is.
 *
 * The correction is a provisional calibration, not a fixed law: the audit measured one illuminant.
 * `docs/white-balance-quality.md` is the gray-card procedure that fills the table by illuminant
 * (2700/4000/5600/6500 K, several lux levels, two rear lenses); until it does, only the 17 Ultra
 * gets the measured value and every other device gets none.
 */
internal object SensorWhiteBalanceCorrection {
    /** Mired offset for the 17 Ultra, `ro.product.device` = `nezha`. */
    const val NEZHA_MIRED = 9f

    /** `debug.rawr.wb_sensor_mired`: the offset, in mired, on any device; unset = device default. */
    const val PROPERTY = "debug.rawr.wb_sensor_mired"

    /** A real sensor bias is a handful of mired; a property beyond this is a typo, not a calibration. */
    const val MAX_MIRED = 60f

    /**
     * The offset to use for [device], with [property] reading a system property (null when unset).
     * The property wins on every device, so the correction can be measured on a phone it is not
     * calibrated for and switched off to compare the raw reading in the same session.
     */
    fun offsetMired(device: String, property: (String) -> String?): Float {
        property(PROPERTY)?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }?.let {
            return it.coerceIn(-MAX_MIRED, MAX_MIRED)
        }
        return if (device == "nezha") NEZHA_MIRED else 0f
    }

    /** The offset this device uses; read once, from the panel. */
    val offsetMired: Float by lazy {
        runCatching { offsetMired(Build.DEVICE.orEmpty()) { SystemPropertiesUtil.get(it) } }.getOrDefault(0f)
    }
}
