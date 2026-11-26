package com.rawr.camera.integration

import com.rawr.camera.model.FocusMode
import com.rawr.camera.model.FocusUiState
import com.rawr.camera.model.ManualFocusCapability
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToLong

/** Light flicker from mains lighting: twice the mains frequency (50 Hz mains -> 100 Hz flicker). */
enum class FlickerFrequency(val hz: Int, val mainsHz: Int) {
    Hz100(100, 50),
    Hz120(120, 60);

    /** One flicker period in nanoseconds (10 ms at 100 Hz, 8.33 ms at 120 Hz). */
    val periodNs: Long get() = 1_000_000_000L / hz
}

/**
 * What the phone's auxiliary sensors measure: beside the rear cameras the light (illuminance, colour
 * temperature, mains flicker) and the laser time-of-flight distance; elsewhere the barometric pressure and
 * the compass heading. Every field is null when the phone has no such sensor or the value is not
 * trustworthy (too dark for a colour temperature, a sentinel value, a stale reading, no ToF target, an
 * uncalibrated compass).
 */
data class AuxSensorReading(
    /** Incident illuminance at the back of the phone, lux. */
    val lux: Float? = null,
    /** Correlated colour temperature of the light, kelvin. */
    val cctK: Float? = null,
    /** Mains flicker, once confirmed over several readings; null = none confirmed. */
    val flicker: FlickerFrequency? = null,
    /** The sensor's latest dominant frequency (Hz), before classification. */
    val flickerRawHz: Float? = null,
    /** True when the phone has a flicker sensor, so a null [flicker] means "no flicker seen". */
    val flickerSensor: Boolean = false,
    /**
     * Flicker that is not mains, once confirmed (LED lights dimmed by PWM flicker at a few hundred
     * hertz), Hz; null = none confirmed. Only when [flicker] is null.
     */
    val flickerPwmHz: Float? = null,
    /** Distance to what the laser AF time-of-flight sensor sees, millimetres. */
    val tofDistanceMm: Float? = null,
    /** Barometric pressure, hectopascals. */
    val pressureHpa: Float? = null,
    /** Compass direction of the rear lens axis from magnetic north, degrees (capture metadata only). */
    val headingMagneticDeg: Float? = null
) {
    /**
     * Incident-light exposure value at ISO 100: EV = log2(E * S / C) with the usual incident-meter
     * calibration C = 250, so EV100 = log2(lux / 2.5).
     */
    val ev100: Float? get() = lux?.takeIf { it > 0f }?.let { log2(it / 2.5f) }

    /** Period of the confirmed flicker, mains or PWM, nanoseconds; null = none. */
    val flickerPeriodNs: Long?
        get() = flicker?.periodNs ?: flickerPwmHz?.takeIf { it > 0f }?.let { (1e9 / it).roundToLong() }

    /**
     * JNI form for capture metadata: {lux, cctK, flickerHz, flickerRawHz, tofDistanceMm, pressureHpa}.
     * NaN = unknown; flickerHz 0 = the flicker sensor saw no mains flicker. The heading travels with the GPS
     * fix instead (EXIF GPSImgDirection), see CaptureGps.withLensHeading.
     */
    fun toNative(): FloatArray = floatArrayOf(
        lux ?: Float.NaN,
        cctK ?: Float.NaN,
        flicker?.hz?.toFloat() ?: if (flickerSensor) 0f else Float.NaN,
        flickerRawHz ?: Float.NaN,
        tofDistanceMm ?: Float.NaN,
        pressureHpa ?: Float.NaN
    )

    val isEmpty: Boolean
        get() = lux == null && cctK == null && flicker == null && flickerRawHz == null && flickerPwmHz == null &&
            tofDistanceMm == null && pressureHpa == null && headingMagneticDeg == null
}

/**
 * Turns the flicker sensor's dominant-frequency stream into a stable mains decision.
 *
 * The sensor reports the strongest frequency as a multiple of its analysis bin (7.8125 Hz on the
 * Xiaomi 15's TCS3408), about eight times a second. Under steady daylight the value wanders at
 * random; under mains lighting it sits on the bin nearest 100 or 120 Hz. A frequency is confirmed
 * after [confirmCount] readings in a row near it and dropped after [releaseCount] readings in a row
 * that are not, so a single noisy reading neither switches nor clears the decision.
 */
class FlickerClassifier(
    private val confirmCount: Int = 3,
    /**
     * Misses in a row that drop a confirmed mains frequency: about two seconds, like PWM, so a frame
     * that briefly loses the lit surface does not flip rawr AE's shutter between snapped and free.
     */
    private val releaseCount: Int = 16,
    private val toleranceHz: Float = DEFAULT_TOLERANCE_HZ,
    /** Readings in a row a non-mains frequency needs: it has no expected value, so it must hold longer. */
    private val pwmConfirmCount: Int = 6,
    /** Odd readings in a row that drop a confirmed PWM frequency: about two seconds, as for mains. */
    private val pwmReleaseCount: Int = 16
) {
    var detected: FlickerFrequency? = null
        private set

    /**
     * A steady non-mains flicker (PWM-dimmed LEDs), Hz, while no mains flicker is detected: the mean of
     * the readings since it settled, so it is finer than one bin. Confirmed after [pwmConfirmCount]
     * readings in a row within a bin and a half of their mean, dropped after [pwmReleaseCount] that are not.
     */
    var pwmHz: Float? = null
        private set
    private var candidate: FlickerFrequency? = null
    private var candidateRun = 0
    private var missRun = 0
    private var pwmSum = 0.0
    private var pwmCount = 0
    private var pwmMissRun = 0

    fun update(hz: Float): FlickerFrequency? {
        val mains = updateMains(hz)
        updatePwm(hz, mains)
        return mains
    }

    private fun updatePwm(hz: Float, mains: FlickerFrequency?) {
        if (mains != null) {
            clearPwm()
            return
        }
        val usable = hz.isFinite() && hz >= MIN_PWM_HZ && hz <= MAX_PWM_HZ && classify(hz) == null
        val mean = if (pwmCount > 0) (pwmSum / pwmCount).toFloat() else Float.NaN
        // A frequency between two bins reads on both, and noise adds a third: one and a half bins
        // around the running mean keeps all three while a wandering reading still leaves.
        if (usable && pwmCount > 0 && abs(hz - mean) <= 1.5f * toleranceHz) {
            pwmSum += hz
            pwmCount++
            pwmMissRun = 0
        } else if (pwmHz != null && ++pwmMissRun < pwmReleaseCount) {
            return  // A confirmed PWM survives a few odd readings, as mains does.
        } else {
            clearPwm()
            if (usable) {
                pwmSum = hz.toDouble()
                pwmCount = 1
            }
        }
        if (pwmCount >= pwmConfirmCount) pwmHz = (pwmSum / pwmCount).toFloat()
    }

    private fun clearPwm() {
        pwmHz = null
        pwmSum = 0.0
        pwmCount = 0
        pwmMissRun = 0
    }

    private fun updateMains(hz: Float): FlickerFrequency? {
        val match = classify(hz)
        if (match != null && match == candidate) candidateRun++ else {
            candidate = match
            candidateRun = if (match != null) 1 else 0
        }
        if (match != null && match == detected) {
            missRun = 0
        } else if (detected != null) {
            missRun++
        }
        when {
            match != null && match != detected && candidateRun >= confirmCount -> {
                detected = match
                missRun = 0
            }
            detected != null && missRun >= releaseCount -> detected = null
        }
        return detected
    }

    fun reset() {
        detected = null
        candidate = null
        candidateRun = 0
        missRun = 0
        clearPwm()
    }

    private fun classify(hz: Float): FlickerFrequency? {
        if (!hz.isFinite()) return null
        return FlickerFrequency.entries.firstOrNull { abs(hz - it.hz) <= toleranceHz }
    }

    companion object {
        /** One analysis bin of the TCS3408 (1000 Hz / 128). */
        const val DEFAULT_TOLERANCE_HZ = 7.8125f

        /**
         * Non-mains flicker considered: below, the readings under steady light wander (15-55 Hz seen
         * in daylight); above, the period is too short to matter for a shutter.
         */
        const val MIN_PWM_HZ = 60f
        const val MAX_PWM_HZ = 1_000f
    }
}

/** Banding check for a shutter time under mains flicker. */
object FlickerBanding {
    /** Exposures of at least this many flicker periods average the flicker out well enough. */
    private const val SAFE_PERIODS = 10.0

    /** How far from a whole number of periods still counts as in step (fraction of a period). */
    private const val TOLERANCE = 0.05

    /**
     * True when [exposureNs] is short enough to show bands and is not a whole number of flicker
     * periods (1/100, 1/50, 1/33 s... at 100 Hz).
     */
    fun bands(exposureNs: Long, flicker: FlickerFrequency): Boolean = bands(exposureNs, flicker.periodNs)

    /** [bands] for any flicker period (PWM as well as mains), nanoseconds. */
    fun bands(exposureNs: Long, periodNs: Long): Boolean {
        if (exposureNs <= 0L || periodNs <= 0L) return false
        val periods = exposureNs.toDouble() / periodNs
        if (periods >= SAFE_PERIODS) return false
        if (periods < 1.0 - TOLERANCE) return true
        return abs(periods - kotlin.math.round(periods)) > TOLERANCE
    }

    /** The nearest banding-free shutter time: a whole number of periods, at least one. */
    fun nearestSafeExposureNs(exposureNs: Long, flicker: FlickerFrequency): Long =
        nearestSafeExposureNs(exposureNs, flicker.periodNs)

    /** [nearestSafeExposureNs] for any flicker period, nanoseconds. */
    fun nearestSafeExposureNs(exposureNs: Long, periodNs: Long): Long {
        val periods = (exposureNs.toDouble() / periodNs).roundToLong().coerceAtLeast(1L)
        return periods * periodNs
    }
}

/** A ToF reading this much nearer than a confirmed AF focus, in diopters, disagrees with the image ... */
private const val TOF_AF_GAP_DIOPTERS = 0.5f
/** ... or this share of the AF diopters, whichever is larger, so close up (where both read in many diopters) it scales. */
private const val TOF_AF_GAP_SHARE = 0.5f

/**
 * The ToF distance to show and focus at, or null when there is none or it contradicts the image.
 *
 * The Xiaomi 17 Ultra's ST L8 reports a valid-looking 0.4-0.65 m in bright light that is not there: at
 * open sky (AF at 11-20 m) and on sunlit paving or a car (AF at 1.2-1.9 m, utalpt's tests). Indoors it agrees
 * with AF (a backpack at 1.2 m reads 1.1-1.2 m). The Xiaomi 15's VI5300 flags the sky by its status.
 * So when the camera's AF has placed the lens and confirms focus, a ToF distance more than
 * max(0.5 D, half the AF diopters) nearer than that focus is not offered. A focus confirmed by contrast on the
 * image outweighs the laser then; the cost is a real near object AF did not pick (a hand in front of the
 * subject), which manual focus still reaches. Manual focus, a scanning or failed AF, or a lens whose focus
 * distance is not calibrated leave the reading as it is: that is where the ToF helps. The TOF cell, the
 * light-meter pill and the capture metadata (rawr:TofDistance) all take the distance from here.
 */
internal fun tofDistanceForFocus(
    reading: AuxSensorReading?,
    focus: FocusUiState,
    capability: ManualFocusCapability
): Float? {
    val distance = reading?.tofDistanceMm ?: return null
    val afDiopters = focus.appliedFocusDiopters
    val afConfirmed = focus.mode != FocusMode.Mf && focus.afFocused && capability.distanceReadoutTrustworthy &&
        afDiopters != null && afDiopters.isFinite() && afDiopters >= 0f
    if (!afConfirmed) return distance
    val gap = 1000f / distance - afDiopters!!
    return distance.takeUnless { gap > maxOf(TOF_AF_GAP_DIOPTERS, TOF_AF_GAP_SHARE * afDiopters) }
}
