package com.rawr.camera.integration

import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

/** Readings the ToF has to offer before its first distance is published, so one invented value never lands. */
internal const val TOF_SETTLE_SAMPLES = 3

/** Readings the window keeps: at 10 Hz that is a half second, long enough to see a target settle or move. */
internal const val TOF_WINDOW_SAMPLES = 5

/** A window whose readings span more than this, or [TOF_SPREAD_SHARE] of the median, has no single target. */
internal const val TOF_SPREAD_MM = 50f
internal const val TOF_SPREAD_SHARE = 0.1f

/** Turns the temporal filter on or off for one session, on any device, whatever `ro.product.device` says. */
internal const val TOF_STABILITY_PROPERTY = "debug.rawr.tof_stability"

/** The light above which the L8's distance is withheld; 0 means no limit. Overrides [NEZHA_TOF_MAX_LUX]. */
internal const val TOF_MAX_LUX_PROPERTY = "debug.rawr.tof_max_lux"

/**
 * On the 17 Ultra the L8 reports 0.4-0.65 m in bright light that is not there, seen at 15,000-57,000 lux
 * (open sky, sunlit paving, a car in the sun: utalpt's tests). This is the lower end of that range, and it
 * is a starting point, not a calibration: the sweep in `docs/aux-sensors.md` is what settles it. Other
 * devices keep the VI5300's status as their only guard and get no limit.
 */
internal const val NEZHA_TOF_MAX_LUX = 15_000f

/**
 * The distances the ToF's last readings agree on, so a single invented reading cannot become a focus.
 *
 * The ST L8 answers every event with status 0, so the status alone cannot say whether the laser found a
 * target (see `tofDistanceForFocus` for the other half of the guard, the comparison with AF). Here the
 * readings themselves decide: none is offered until [TOF_SETTLE_SAMPLES] arrive, the median is offered
 * while they agree within [maxSpread], and a window that disagrees with itself is emptied rather than
 * averaged, because a spread that wide means the target is moving or the sensor is making it up.
 *
 * A missing reading (status invalid, out of range) empties the window too: it is evidence that the target
 * left, so the readings before it must not decide the distance after it.
 */
internal class TofWindow(
    private val capacity: Int = TOF_WINDOW_SAMPLES,
    private val settleSamples: Int = TOF_SETTLE_SAMPLES,
    private val maxSpread: (medianMm: Float) -> Float = { median ->
        maxOf(TOF_SPREAD_MM, TOF_SPREAD_SHARE * abs(median))
    }
) {
    private val recent = ArrayDeque<Float>()

    /** The distance to publish for this event: the median of the window, or null when the window has none. */
    fun accept(distanceMm: Float?): Float? {
        if (distanceMm == null) {
            recent.clear()
            return null
        }
        recent.addLast(distanceMm)
        while (recent.size > capacity) recent.removeFirst()
        if (recent.size < settleSamples) return null
        val sorted = recent.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else 0.5f * (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2])
        if (sorted.last() - sorted.first() > maxSpread(median)) {
            recent.clear()
            return null
        }
        return median
    }

    /** Forgets what came before, for a camera that just started. */
    fun reset() = recent.clear()
}

/**
 * The ToF's last readings and the light they were taken in, as one policy: [TofWindow] on the distances and
 * the lux ceiling on the L8's band layout.
 *
 * Both parts are off unless the phone is the 17 Ultra or the property turns them on, so the Xiaomi 15 and
 * every other device keep the exact behaviour they had.
 */
internal class TofFilter(
    private val window: TofWindow?,
    private val maxLux: Float,
    private val layout: TofLayout
) {
    /** The distance to publish, or null when the readings or the light say there is none to trust. */
    fun update(distanceMm: Float?, lux: Float?): Float? {
        val gated = if (layout == TofLayout.Bands && maxLux > 0f && lux != null && lux >= maxLux) null
        else distanceMm
        return if (window == null) gated else window.accept(gated)
    }

    fun reset() = window?.reset()
}

/**
 * The filter a phone gets, from its device name and properties: null means no filtering at all.
 *
 * [TofFilter] needs the L8's four-band layout to mean anything, so the VI5300 keeps its own way. The
 * temporal window is on by default on `nezha` and off elsewhere; [TOF_STABILITY_PROPERTY] turns it on or
 * off for one session on any device, which is how the same scene is compared with and without it. The lux
 * ceiling follows [TOF_MAX_LUX_PROPERTY] when set, and [NEZHA_TOF_MAX_LUX] only on the 17 Ultra.
 */
internal fun tofFilterFor(
    device: String,
    layout: TofLayout,
    readProperty: (String) -> String?
): TofFilter? {
    if (layout != TofLayout.Bands) return null
    val onNezha = device.equals(NEZHA_DEVICE, ignoreCase = true)
    val enabled = readProperty(TOF_STABILITY_PROPERTY)?.let { it != "0" && it.lowercase() != "false" } ?: onNezha
    val maxLux = readProperty(TOF_MAX_LUX_PROPERTY)?.toFloatOrNull()
        ?: if (onNezha) NEZHA_TOF_MAX_LUX else 0f
    if (!enabled && maxLux <= 0f) return null
    return TofFilter(if (enabled) TofWindow() else null, maxLux, layout)
}

/** `ro.product.device` of the Xiaomi 17 Ultra, the phone the L8 numbers were measured on. */
internal const val NEZHA_DEVICE = "nezha"

/** Logcat tag of the sample lines, so `adb logcat -s RawrTof` is the whole instrument. */
internal const val TOF_LOG_TAG = "RawrTof"

/** Turns the sample log on or off while it runs: `debug.rawr.tof_log 1`, read on the fly. */
internal const val TOF_LOG_PROPERTY = "debug.rawr.tof_log"

/** Readings the log keeps, the 20-50 the sweep needs to tell a target from an invention. */
internal const val TOF_LOG_SAMPLES = 50

/** One summary line every this many readings: at 10 Hz that is a line per second. */
internal const val TOF_SUMMARY_EVERY = 10

/** How often the property is read again, so `setprop` works without restarting the camera. */
internal const val TOF_LOG_RECHECK = 10

/** One ToF event as the sensor delivered it, before any filtering. */
internal data class TofSample(
    val distanceMm: Float?,
    val status: String,
    val lux: Float?,
    val cctK: Float?,
    val atNanos: Long
) {
    fun asLogLine(): String =
        "TOF_SAMPLE d=${distanceMm.mm()} s=$status lux=${lux.mm()} cct=${cctK.mm()} t=${atNanos / 1_000_000L}"
}

/** What the readings in [TofSamples] say: where they agree, and how much they disagree. */
internal data class TofSummary(
    val count: Int,
    val seen: Int,
    val medianMm: Float?,
    val madMm: Float?,
    val minMm: Float?,
    val maxMm: Float?,
    val withoutTarget: Int,
    val lux: Float?,
    val cctK: Float?
) {
    fun asLogLine(): String =
        "TOF_SUMMARY n=$count seen=$seen median=${medianMm.mm()} mad=${madMm.mm()} " +
            "min=${minMm.mm()} max=${maxMm.mm()} noTarget=$withoutTarget lux=${lux.mm()} cct=${cctK.mm()}"
}

/**
 * The audit's instrument for the ToF: the last [TOF_LOG_SAMPLES] readings, with the median and the median
 * absolute deviation of the distances in them.
 *
 * The sweep in `docs/aux-sensors.md` needs the numbers a scene produces, not a verdict about them: at each
 * light level and target distance the log says whether the L8 reads the wall twice the same way, and by how
 * much it wanders when it does not.
 */
internal class TofSamples(private val capacity: Int = TOF_LOG_SAMPLES) {
    private val recent = ArrayDeque<TofSample>()
    private var seenCount = 0

    /** Readings recorded since the last [clear]. */
    val seen: Int get() = seenCount

    fun record(sample: TofSample) {
        recent.addLast(sample)
        while (recent.size > capacity) recent.removeFirst()
        seenCount++
    }

    fun clear() {
        recent.clear()
        seenCount = 0
    }

    fun summary(): TofSummary? {
        if (recent.isEmpty()) return null
        val distances = recent.mapNotNull { it.distanceMm }
        val last = recent.last()
        val sorted = distances.sorted()
        val median = medianOf(distances)
        return TofSummary(
            count = recent.size,
            seen = seenCount,
            medianMm = median,
            madMm = median?.let { medianOf(distances.map { d -> abs(d - it) }) },
            minMm = sorted.firstOrNull(),
            maxMm = sorted.lastOrNull(),
            withoutTarget = recent.size - distances.size,
            lux = last.lux,
            cctK = last.cctK
        )
    }
}

/** The middle value of [values], or null when there is none. */
private fun medianOf(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else 0.5f * (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2])
}

/** Whole millimetres, or `-` when the reading has none: the log is read by a person, not a parser. */
private fun Float?.mm(): String = this?.roundToInt()?.toString() ?: "-"

/** Is the sample log on? `0` and `false` leave it off. */
internal fun tofLogEnabled(readProperty: (String) -> String?): Boolean =
    readProperty(TOF_LOG_PROPERTY)?.let { it != "0" && it.lowercase() != "false" } == true

/**
 * The sample log itself: one line per reading while it is on, a summary every [TOF_SUMMARY_EVERY], and
 * nothing at all while it is off.
 *
 * The property is read again every [TOF_LOG_RECHECK] readings, so a `setprop` takes effect without a restart:
 * the sweep turns it on for one cell, reads the lines, and turns it off.
 */
internal class TofLog(private val readProperty: (String) -> String?) {
    private val samples = TofSamples()
    private var enabled = false
    private var events = 0

    /** The lines this reading produces, or an empty list when the log is off. */
    fun record(sample: TofSample): List<String> {
        if (events % TOF_LOG_RECHECK == 0) enabled = tofLogEnabled(readProperty)
        events++
        if (!enabled) return emptyList()
        samples.record(sample)
        val summary = if (samples.seen % TOF_SUMMARY_EVERY == 0) samples.summary()?.asLogLine() else null
        return listOfNotNull(sample.asLogLine(), summary)
    }

    fun reset() {
        samples.clear()
        events = 0
    }
}

/** The statuses of the pairs the layout carries, as one token per band: what the sensor says of each. */
internal fun tofStatusText(values: FloatArray, layout: TofLayout): String =
    when (layout) {
        TofLayout.Single -> values.getOrNull(1)?.toInt()?.toString() ?: "-"
        TofLayout.Bands -> (0 until 4).joinToString(",") { band ->
            values.getOrNull(band * 2 + 1)?.toInt()?.toString() ?: "-"
        }
    }
