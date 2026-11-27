package com.rawr.camera.model

import kotlin.math.roundToInt

/**
 * Rawr 17U fork: the tele's optical zoom between 75 mm and 100 mm on the Xiaomi 17 Ultra. The periscope moves its
 * optics to the position in the vendor key xiaomi.zoomRatio.debug.setZoomRatioCode (3.2 = 75 mm, 4.3 = 100 mm),
 * which the 4.3x lenses of the profile already send. The mapping is the one of the Telezoom module
 * (utalpt/x17u-telezoom, Prefs.code); the vendor tags were found by Anton Tretiakov (@designation_zero).
 */
object TeleOpticalZoom {
    const val MIN_MM = 75
    const val MAX_MM = 100

    /** Every millimetre from 75 to 100: the bar's steps. */
    val steps: List<Int> = (MIN_MM..MAX_MM).toList()

    /** Detents on the bar: the common tele focal lengths. */
    val presets: Set<Int> = setOf(75, 85, 90, 100)

    /** The vendor key's value for a focal length: linear, 0.044 per mm, two decimals (as in Telezoom). */
    fun code(mm: Int): Double = ((3.2 + (mm.coerceIn(MIN_MM, MAX_MM) - MIN_MM) * 0.044) * 100).roundToInt() / 100.0

    /** The optical zoom position a lens asks for, read from its optical group (":z4.30"); null on other lenses. */
    fun lensCode(lens: LensCapability?): Double? =
        lens?.group?.let { Regex(":z([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

    /** The lens can move the tele's optics (the 4.3x lenses of the 17 Ultra profile). */
    fun supports(lens: LensCapability?): Boolean = lensCode(lens) != null

    fun label(mm: Int): String = "${mm}mm"

    /** Zoom against the 23 mm main camera, one decimal: 75 mm = 3.2x, 85 mm = 3.6x, 100 mm = 4.3x. */
    fun zoomLabel(mm: Int): String = "%.1fx".format(java.util.Locale.ROOT, mm.coerceIn(MIN_MM, MAX_MM) / 23.4)

    private fun mode(lens: LensCapability): String = lens.displayName.trim().substringAfter(' ', "")
    private fun baseGroup(lens: LensCapability): String = lens.group.substringBefore(":z")

    /**
     * The lens that moves the optics and stands in for [lens]: same optics, same mode ("DCG", "50MP"...), but with
     * the zoom key (the 4.3x twin of a 3.2x lens). With the optics movable from 75 to 100 mm the fixed 75 mm lens is
     * the twin at 75 mm, so the lens bar shows one tele button. Null for every other lens.
     */
    fun twinOf(lens: LensCapability, lenses: List<LensCapability>): LensCapability? {
        if (supports(lens) || lens.group.isBlank()) return null
        return lenses.firstOrNull { other ->
            supports(other) && baseGroup(other) == lens.group && mode(other) == mode(lens)
        }
    }

    /** Ids the 17 Ultra profile's tele lenses had before "Tele DCG": the fixed "3.2x DCG" and the movable "4.3x DCG". */
    private val legacyPrefixes = listOf("3.2x ", "4.3x ")

    /** "3.2x DCG" / "4.3x DCG" -> "Tele DCG"; any other id unchanged. */
    fun teleId(id: String): String =
        legacyPrefixes.firstOrNull { id.startsWith(it) }?.let { "Tele " + id.removePrefix(it) } ?: id

    /**
     * The movable lens for a saved id of a tele lens that is no longer in the profile ("3.2x DCG" or "4.3x DCG" ->
     * "Tele DCG"; a user's own lens list may still have "4.3x DCG"). Null when the id is not one of those or no
     * movable lens of that mode is there.
     */
    fun legacyFixedLens(id: String, lenses: List<LensCapability>): String? {
        val prefix = legacyPrefixes.firstOrNull { id.startsWith(it) } ?: return null
        val mode = id.removePrefix(prefix)
        return listOf("Tele $mode", "4.3x $mode").firstNotNullOfOrNull { candidate ->
            lenses.firstOrNull { it.id == candidate && it.id != id && supports(it) }?.id
        }
    }

    /** The lens bar's lenses: the fixed tele lenses that have a movable twin are left out. */
    fun visibleLenses(lenses: List<LensCapability>): List<LensCapability> = lenses.filter { twinOf(it, lenses) == null }

    /** "4.3x DCG" shown as "3.6x DCG" at 85 mm on a lens that moves the optics. */
    fun displayName(lens: LensCapability, mm: Int): String =
        if (!supports(lens)) lens.displayName
        else (zoomLabel(mm) + " " + mode(lens)).trim()
}
