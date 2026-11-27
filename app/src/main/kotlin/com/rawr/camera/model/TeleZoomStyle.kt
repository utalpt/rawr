package com.rawr.camera.model

/**
 * Rawr 17U fork: how the TELE cell's optical zoom control looks. BAR is the FOCAL sheet above the exposure row; the
 * others stand on the viewfinder's right edge, vertical, as the Telezoom module draws them (utalpt/x17u-telezoom,
 * Bar.java: Wheel, Arc and Rocker).
 */
enum class TeleZoomStyle(val id: String, val label: String) {
    BAR("bar", "Bar"),
    WHEEL("wheel", "Wheel"),
    DIAL("dial", "Dial"),
    ROCKER("rocker", "Rocker");

    fun next(): TeleZoomStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: String?): TeleZoomStyle = entries.firstOrNull { it.id == id } ?: BAR
    }
}
