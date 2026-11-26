package com.rawr.camera.model

/**
 * Rawr 17U fork: the switch each film section waits on, so ALL PARAMS can mark a section "OFF" and dim the
 * values that do nothing while it is off. The idea of showing it comes from apos-fandango's Rawr X14U fork
 * (github.com/apos-fandango/rawr-xiaomi14u); the code is the fork's own. Switches match what the native
 * renderer checks (SpektraFilm.cpp: grainEnabled, halationEnabled for scatter and halation, scannerEnabled for
 * levels, sharpness and glare, the two diffusion switches, the UV and IR filters).
 */
object FilmSectionGates {
    private val itemGates = mapOf(
        "CameraUvCutNm" to listOf("CameraUvEnabled"),
        "CameraIrCutNm" to listOf("CameraIrEnabled")
    )

    /** Switches a section (or one of its subsections) depends on; empty when nothing gates it. */
    fun gatesFor(sectionKey: String?, subsectionKey: String? = null): List<String> = when (sectionKey) {
        "grain" -> listOf("GrainEnabled")
        "halation" -> listOf("HalationEnabled")
        "scanner" -> listOf("ScannerEnabled")
        "filters" -> listOf("CameraUvEnabled", "CameraIrEnabled")
        "diffusion" -> when (subsectionKey) {
            "camera" -> listOf("CameraDiffusionEnabled")
            "print" -> listOf("PrintDiffusionEnabled")
            else -> listOf("CameraDiffusionEnabled", "PrintDiffusionEnabled")
        }
        else -> emptyList()
    }

    /** True when every switch the section or subsection waits on is off. Unknown switches never gate. */
    fun isOff(state: FilmSimQuickState, sectionKey: String?, subsectionKey: String? = null): Boolean {
        val flags = gatesFor(sectionKey, subsectionKey).mapNotNull { state.flags[it] }
        return flags.isNotEmpty() && flags.none { it.enabled }
    }

    /** Whether an item in the given subsection does anything now. The switches themselves always stay lit. */
    fun isLive(state: FilmSimQuickState, sectionKey: String?, subsectionKey: String?, itemKey: String): Boolean {
        val gates = itemGates[itemKey] ?: gatesFor(sectionKey, subsectionKey)
        if (itemKey in gates || itemKey in gatesFor(sectionKey)) return true
        val flags = gates.mapNotNull { state.flags[it] }
        return flags.isEmpty() || flags.any { it.enabled }
    }
}
