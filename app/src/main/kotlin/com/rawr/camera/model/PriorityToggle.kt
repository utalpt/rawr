package com.rawr.camera.model

import kotlin.math.abs
import kotlin.math.ln

/**
 * Rawr 17U fork (asked for by iG0Lka): one tap switches between ISO priority (I, static scenes) and shutter priority
 * (S, motion), each entered with its own saved value. Where the switch is drawn: nowhere (OFF), as a button in the top bar,
 * or as an "S"/"I" tab on the fixed exposure cell. Photo mode only.
 */
enum class PriorityToggleStyle(val id: String, val label: String) {
    OFF("off", "Off"),
    TOP_BAR("top_bar", "Top bar"),
    EXPOSURE_CELL("exposure_cell", "Exposure cell");

    fun next(): PriorityToggleStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: String?): PriorityToggleStyle = entries.firstOrNull { it.id == id } ?: OFF
    }
}

object PriorityTogglePresets {
    const val DEFAULT_ISO = 50
    const val DEFAULT_SHUTTER_NS = 1_000_000L  // 1/1000 s
    val isoValues = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
    val shutterValuesNs = listOf(125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L,
        33_333_333L)

    fun nextIso(value: Int): Int = isoValues[(isoValues.indexOfNearest(value.toDouble()) { it.toDouble() } + 1) % isoValues.size]
    fun nextShutter(ns: Long): Long =
        shutterValuesNs[(shutterValuesNs.indexOfNearest(ns.toDouble()) { it.toDouble() } + 1) % shutterValuesNs.size]

    fun isoLabel(value: Int): String = "ISO $value"
    fun shutterLabel(ns: Long): String = ExposureFormat.formatShutterNs(ns)

    /** The entry of [values] nearest to [target] on a log scale (exposure steps); null when empty. */
    fun <K, V : Number> nearest(values: Map<K, V>, target: Double): Map.Entry<K, V>? =
        values.entries.filter { it.value.toDouble() > 0.0 }.minByOrNull { abs(ln(it.value.toDouble() / target)) }

    private inline fun <T> List<T>.indexOfNearest(target: Double, value: (T) -> Double): Int =
        indices.minByOrNull { abs(ln(value(this[it]) / target.coerceAtLeast(1e-9))) } ?: 0
}
