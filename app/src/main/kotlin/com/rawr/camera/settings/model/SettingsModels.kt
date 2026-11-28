package com.rawr.camera.settings.model

import com.rawr.camera.model.CaptureControlLayout
import com.rawr.camera.model.ControlSurfaceStyle
import com.rawr.camera.model.GridMode
import com.rawr.camera.model.ImageToneState
import com.rawr.camera.model.OverlayMode
import com.rawr.camera.model.ScopeType
import com.rawr.camera.model.ToneParameter
import com.rawr.camera.model.TonemapCatalog
import com.rawr.camera.model.TonemapControlContract
import com.rawr.camera.model.TonemapParam
import com.rawr.camera.model.WaveformMode
import com.rawr.camera.model.valueFor
import com.rawr.camera.model.withValue
import kotlin.math.roundToInt

enum class SettingsSection(val title: String) {
    Capture("Capture"),
    Lens("Lens"),
    Exposure("Exposure"),
    HighlightReconstruction("Highlight Reconstruction"),
    Ois("OIS"),
    Image("Image"),
    ImageTone("Tone & Color"),
    DynamicRange("Dynamic Range"),
    LutProfile("LUT Profile"),
    Demosaic("Demosaic"),
    Defringe("Defringe"),
    Denoise("Denoise"),
    LensShading("Lens Shading"),
    Distortion("Distortion Correction"),
    Jpeg("JPEG"),
    DisplayControls("Display & Control"),
    Dng("DNG"),
    Monitoring("Monitoring"),
    ControlStyle("Control Style"),
    Storage("Storage"),
    Renderer("Renderer"),
    VideoEncoder("Video Encoder"),
    Experimental("Experimental"),
    GpuDriver("GPU Driver"),
    ZeroCopy("Zero Copy"),
    Multiframe("Multiframe"),
    FilmSim("Film Simulation"),
    Debug("Debug"),
    About("About"),
    // Rawr 17U fork: the settings home groups by what the user wants to do; these hubs link to the pages above.
    PhotoHub("Photo"),
    LookHub("Look"),
    RawHub("RAW development"),
    SystemHub("System"),

    // Kept only for compatibility with older persisted/navigation tests; no longer exposed.
    CameraDevice("Camera / Device")
}

enum class ImageToneTab(val title: String) {
    ExposureTonality("Exposure & Tonality"),
    Color("Color"),
    Output("Output")
}

enum class ResetTarget {
    ExposureTonality,
    Color,
    Output,
    AllImageTone
}

data class ChoiceCandidate(val id: String, val label: String, val supportingText: String? = null)

data class FalseColorPresetCandidate(val id: String, val displayName: String, val description: String)

enum class ImageToneNumericParameter {
    RenderExposure,
    BlackToe,
    Shadows,
    Contrast,
    MidtonePivot,
    Highlights,
    ShoulderWhitePoint,
    Saturation,
    Vibrance,
    ColorRenderingStrength,
    WbTemperature,
    WbTint,
    JpegQuality
}

data class NumericSettingSpec(
    val parameter: ImageToneNumericParameter,
    val label: String,
    val minimum: Float,
    val maximum: Float,
    val defaultValue: Float,
    val step: Float,
    val unit: String = "",
    val decimals: Int = 0
) {
    init {
        require(maximum > minimum)
        require(step > 0f)
        require(defaultValue in minimum..maximum)
    }
}

data class CameraInformation(
    val displayName: String,
    val lensSummary: String,
    val sensorSummary: String,
    val capabilitySummary: String
)

data class SettingsCapabilities(
    val saveLocationChoices: List<ChoiceCandidate>,
    val falseColorPresets: List<FalseColorPresetCandidate>,
    val peakingSensitivityChoices: List<ChoiceCandidate>,
    val outputColorSpaces: List<ChoiceCandidate>,
    val transferFunctions: List<ChoiceCandidate>,
    val jpegChromaSubsamplingChoices: List<ChoiceCandidate>,
    val dngCompressionChoices: List<ChoiceCandidate>,
    val maxPostGainChoices: List<ChoiceCandidate>,
    val autoMinFpsChoices: List<ChoiceCandidate>,
    val oisSupported: Boolean,
    val cameraInformation: CameraInformation
) {
    init {
        require(saveLocationChoices.isNotEmpty())
        require(falseColorPresets.isNotEmpty())
        require(peakingSensitivityChoices.isNotEmpty())
        require(outputColorSpaces.isNotEmpty())
        require(transferFunctions.isNotEmpty())
        require(jpegChromaSubsamplingChoices.isNotEmpty())
        require(maxPostGainChoices.isNotEmpty())
        require(autoMinFpsChoices.isNotEmpty())
        requireUniqueIds("saveLocationChoices", saveLocationChoices.map { it.id })
        requireUniqueIds("falseColorPresets", falseColorPresets.map { it.id })
        requireUniqueIds("peakingSensitivityChoices", peakingSensitivityChoices.map { it.id })
        requireUniqueIds("outputColorSpaces", outputColorSpaces.map { it.id })
        requireUniqueIds("transferFunctions", transferFunctions.map { it.id })
        requireUniqueIds("jpegChromaSubsamplingChoices", jpegChromaSubsamplingChoices.map { it.id })
        requireUniqueIds("maxPostGainChoices", maxPostGainChoices.map { it.id })
        requireUniqueIds("autoMinFpsChoices", autoMinFpsChoices.map { it.id })
    }

    private fun requireUniqueIds(name: String, ids: List<String>) {
        require(ids.size == ids.toSet().size) { "$name must contain unique candidate IDs" }
    }
}

enum class SelfTimer(val label: String) {
    Off("Off"),
    TwoSeconds("2 s"),
    ThreeSeconds("3 s"),
    FiveSeconds("5 s"),
    TenSeconds("10 s");

    /** Whole-second delay; 0 means no delay. */
    val seconds: Int
        get() = when (this) {
            Off -> 0
            TwoSeconds -> 2
            ThreeSeconds -> 3
            FiveSeconds -> 5
            TenSeconds -> 10
        }

    /** Compact top-bar label. */
    val shortLabel: String
        get() = when (this) {
            Off -> "OFF"
            TwoSeconds -> "2s"
            ThreeSeconds -> "3s"
            FiveSeconds -> "5s"
            TenSeconds -> "10s"
        }

    companion object {
        /** Capture-screen cycle order: Off → 3s → 5s → 10s → Off. */
        fun nextCaptureOption(current: SelfTimer): SelfTimer = when (current) {
            Off -> ThreeSeconds
            ThreeSeconds -> FiveSeconds
            FiveSeconds -> TenSeconds
            TenSeconds -> Off
            // Legacy persisted value outside the cycle: re-enter at 3s.
            TwoSeconds -> ThreeSeconds
        }
    }
}

enum class AntiFlicker(val label: String) {
    Auto("Auto"),
    Hz50("50 Hz"),
    Hz60("60 Hz"),
    Off("Off")
}

/** rawr auto exposure: how far dim scenes are placed below day brightness; native NightLook ids. */
enum class RawrNightLook(val label: String, val nativeId: Int) {
    Natural("Natural", 1),
    Bright("Bright", 2),
    Off("Off", 0)
}

/**
 * Slowest shutter rawr AE will use. ISO takes over the rest of the light; the engine starts
 * raising ISO at 1/30 s (or this, if faster). Auto: 1/15 s for photo, the frame time for video.
 */
/**
 * How the lens bar lays its buttons out. Auto keeps every lens on one row while they fit and falls back to
 * one button per optical group when they would not; the other two settle it whatever the device has.
 */
enum class LensBarGrouping(val label: String) {
    Auto("Auto"),
    Grouped("Grouped"),
    Flat("All lenses")
}

/** Lens button scale: the tap target, not just the glyph. */
enum class LensBarSize(val label: String, val scale: Float) {
    Compact("Compact", 0.82f),
    Normal("Normal", 1.0f),
    Large("Large", 1.18f)
}

enum class RawrMaxShutter(val label: String, val ns: Long) {
    Auto("Auto (1/15)", 0L),
    S125("1/125", 8_000_000L),
    S60("1/60", 16_666_667L),
    S30("1/30", 33_333_333L),
    S15("1/15", 66_666_667L),
    S8("1/8", 125_000_000L),
    S5("1/5", 200_000_000L)
}

/** Auto shutter/ISO trade (EV): faster shutters raise ISO; lower ISO lets the shutter run longer first. */
enum class RawrBalance(val label: String, val ev: Float) {
    LowIso("Low ISO", -1f),
    Balanced("Balanced", 0f),
    Faster("Faster", 1f),
    Fastest("Fastest", 2f)
}

/** Digital gain allowed past the highest real ISO, applied cleanly after RAW (EV). */
enum class RawrDigitalGain(val label: String, val ev: Float) {
    Off("Off", 0f),
    Ev1("+1 EV", 1f),
    Ev2("+2 EV", 2f),
    Ev3("+3 EV", 3f),
    Ev4("+4 EV", 4f),
    /** No practical limit: whatever the scene still lacks becomes gain (noisy). */
    Maximum("Maximum", 16f)
}

/** How quickly rawr AE follows the scene; native speed ids. */
enum class RawrAeSpeed(val label: String, val nativeId: Int) {
    Slow("Slow", 0),
    Normal("Normal", 1),
    Fast("Fast", 2)
}

/** rawr metering mode; a tap-to-meter spot overrides it while set. */
enum class RawrMetering(val label: String, val nativeId: Int) {
    Matrix("Matrix", 0),
    CenterWeighted("Centre", 1),
    Average("Average", 2),
    Highlight("Highlight", 3)
}

/** rawr auto exposure: Auto/S/I solved in the app with Camera2 AE off. On by default. */
/** ISO always spans base to the sensor's analog maximum; no user range. */
data class RawrAeSettings(
    val enabled: Boolean = true,
    val nightLook: RawrNightLook = RawrNightLook.Natural,
    val maxShutter: RawrMaxShutter = RawrMaxShutter.Auto,
    val balance: RawrBalance = RawrBalance.Balanced,
    val digitalGain: RawrDigitalGain = RawrDigitalGain.Ev2,
    val speed: RawrAeSpeed = RawrAeSpeed.Normal,
    val metering: RawrMetering = RawrMetering.Matrix
)

enum class ExposureStep(val label: String) {
    Third("1/3 EV"),
    Half("1/2 EV")
}

/** How a boost request above the limit rolls off (native knee width 2 / 1 / 0.5 EV). */
enum class HighlightProtection(val label: String, val kneeSummary: String) {
    Low("Soft", "Up to 2 EV more past the limit · brightest, most clipping risk"),
    Normal("Normal", "Up to 1 EV more past the limit · balanced"),
    High("Hard", "Up to 0.5 EV more past the limit · darkest, safest highlights")
}

/** Shadow lift (adaptive scene-key curve) after the brightness boost; native AutoKeyMode ids. */
/** Photo dynamic range method as stored: Adaptive = the global curve (strength in autoKeyExposure). */
enum class PhotoToneMapping { Adaptive, Local }

/**
 * Dynamic Range as the UI presents it (Image > Rendering > Dynamic Range,
 * Renderer > Render): fitting the scene's range before Tone Mapping.
 * Off and Global are the adaptive curve's strength (Off / the rest).
 */
enum class DynamicRangeMethod(val label: String, val summary: String) {
    Off("Off", "No shadow brightening: the image as exposed"),
    Global("Global", "One curve for the whole image"),
    Local("Local", "Region by region (exposure fusion): keeps local contrast")
}

// Keep the experimental implementation and recipe support, but hide its controls.
const val LOCAL_TONE_CONTROLS_VISIBLE = false
val visibleDynamicRangeMethods: List<DynamicRangeMethod> = DynamicRangeMethod.entries.filter {
    LOCAL_TONE_CONTROLS_VISIBLE || it != DynamicRangeMethod.Local
}

fun SettingsValues.photoDynamicRange(): DynamicRangeMethod = when {
    photoToneMapping == PhotoToneMapping.Local -> DynamicRangeMethod.Local
    autoKeyExposure == AutoKeyExposure.Off -> DynamicRangeMethod.Off
    else -> DynamicRangeMethod.Global
}

fun SettingsValues.videoDynamicRange(): DynamicRangeMethod =
    if (videoAutoKeyExposure == AutoKeyExposure.Off) DynamicRangeMethod.Off else DynamicRangeMethod.Global

/** Global strengths (the adaptive curve without Off). */
val globalDynamicRangeStrengths: List<AutoKeyExposure> = AutoKeyExposure.entries.filter { it != AutoKeyExposure.Off }

/** Conservative UI ranges; image quality still depends on scene content. */
data class LocalToneTuning(
    val highlightsEv: Float = 2f,
    val sigma: Float = 5f,
    val coarsestMip: Int = 6,
    val boostLocalContrast: Boolean = false
) {
    fun sanitized() = copy(
        highlightsEv = if (highlightsEv.isFinite()) (highlightsEv.coerceIn(.5f, 2f) * 10f).roundToInt() / 10f else 2f,
        sigma = if (sigma.isFinite()) (sigma.coerceIn(3f, 5f) * 10f).roundToInt() / 10f else 5f,
        coarsestMip = coarsestMip.coerceIn(6, 8)
    )
}

/** Local tone mapping shadows range, shared with native local_tone/ExposureFusion.h. */
object LocalToneLimits {
    const val DEFAULT_SHADOWS_EV = 2.0f
    const val MIN_SHADOWS_EV = 1.0f
    const val MAX_SHADOWS_EV = 3.0f
    fun shadowsEv(value: Float): Float =
        if (value.isFinite()) (value.coerceIn(MIN_SHADOWS_EV, MAX_SHADOWS_EV) * 10f).roundToInt() / 10f else DEFAULT_SHADOWS_EV
}

enum class AutoKeyExposure(val label: String, val summary: String, val nativeId: Int) {
    Off("Off", "No lift · renders the boosted exposure as is", 0),
    Natural("Low", "Lifts shadows up to 1 EV", 1),
    Medium("Medium", "Lifts shadows up to 2 EV", 3),
    Strong("Strong", "Lifts shadows up to 3 EV", 2)
}

/** [nativeId] matches tonemap_integration::ColorRenderProfile; ids 4-8 are the LOG profiles. */
enum class ColorRenderProfile(val label: String, val nativeId: Int) {
    RawrBase("RAWR NTRL", 0),
    UserLut("Imported LUT", 1),
    SRgb("sRGB", 2),
    Rec709("Rec.709", 3),
    AgX("AgX", 9);

    /** AgX writes sRGB for stills and Rec.709 for video (native id 10). */
    fun nativeIdFor(video: Boolean): Int = if (this == AgX && video) AGX_REC709_NATIVE_ID else nativeId

    companion object {
        const val AGX_REC709_NATIVE_ID = 10
    }
}

enum class LutGamut(val label: String, val nativeId: Int) {
    SRgbRec709("Rec.709 / sRGB", 0),
    AcesCgAp1("ACEScg / AP1", 1),
    DaVinciWideGamut("DaVinci Wide Gamut", 2),
    Rec2020("Rec.2020", 3),
    ArriWideGamut3("ARRI Wide Gamut 3", 4),
    SonySGamut3Cine("Sony S-Gamut3.Cine", 5),
    PanasonicVGamut("Panasonic V-Gamut", 6),
    FujifilmFGamutC("FUJIFILM F-Gamut C", 7)
}

enum class LutTransfer(val label: String, val nativeId: Int) {
    Linear("Linear", 0),
    SRgb("sRGB", 1),
    DaVinciIntermediate("DaVinci Intermediate", 2),
    Rec2020("Rec.2020", 3),
    Gamma22("Gamma 2.2", 4),
    Gamma24("Gamma 2.4", 5),
    LogC3("ARRI LogC3 · EI 800", 6),
    SLog3("Sony S-Log3", 7),
    VLog("Panasonic V-Log", 8),
    FLog2C("FUJIFILM F-Log2 C", 9),
    Rec709("BT.709", 10)
}

enum class AfterLutAction(val label: String) {
    UseDirectly("Use directly"),
    ConvertToJpegSrgb("Convert to JPEG sRGB")
}

data class ImportedLutStage(val id: String, val fileName: String, val relativePath: String)

/**
 * Per-render-profile TONE controls.
 *
 * These are the 9 sliders shown in the Tone group (and the capture tone
 * drawer): render exposure, blacks, shadows, contrast, midtones, highlights,
 * whites, saturation and vibrance. Each render profile — RAWR NTRL, sRGB, Rec.709, and every
 * imported LUT profile — owns one [ProfileTone]; switching profiles switches
 * tone. All other [ImageToneState] fields (WB, JPEG, output spaces, color
 * rendering strength) stay global.
 */
data class ProfileTone(
    val renderExposure: Float = TonemapControlContract.EXPOSURE_NEUTRAL_EV,
    val blacks: Float = TonemapControlContract.TONE_UI_NEUTRAL,
    val shadows: Float = TonemapControlContract.TONE_UI_NEUTRAL,
    val contrast: Float = TonemapControlContract.TONE_UI_NEUTRAL,
    val midtones: Float = TonemapControlContract.TONE_UI_NEUTRAL,
    val highlights: Float = TonemapControlContract.TONE_UI_NEUTRAL,
    val whites: Float = TonemapControlContract.TONE_UI_NEUTRAL,
    val saturation: Float = TonemapControlContract.SATURATION_NEUTRAL,
    val vibrance: Float = TonemapControlContract.VIBRANCE_NEUTRAL
) {
    companion object {
        val Neutral = ProfileTone()
    }
}

/** True for the 9 tone parameters stored per-profile; false for global image params. */
fun ImageToneNumericParameter.isPerProfileTone(): Boolean = when (this) {
    ImageToneNumericParameter.RenderExposure,
    ImageToneNumericParameter.BlackToe,
    ImageToneNumericParameter.Shadows,
    ImageToneNumericParameter.Contrast,
    ImageToneNumericParameter.MidtonePivot,
    ImageToneNumericParameter.Highlights,
    ImageToneNumericParameter.ShoulderWhitePoint,
    ImageToneNumericParameter.Saturation,
    ImageToneNumericParameter.Vibrance -> true
    ImageToneNumericParameter.ColorRenderingStrength,
    ImageToneNumericParameter.WbTemperature,
    ImageToneNumericParameter.WbTint,
    ImageToneNumericParameter.JpegQuality -> false
}

fun ProfileTone.withNumeric(parameter: ImageToneNumericParameter, value: Float): ProfileTone =
    when (parameter) {
        ImageToneNumericParameter.RenderExposure -> copy(renderExposure = value)
        ImageToneNumericParameter.BlackToe -> copy(blacks = value)
        ImageToneNumericParameter.Shadows -> copy(shadows = value)
        ImageToneNumericParameter.Contrast -> copy(contrast = value)
        ImageToneNumericParameter.MidtonePivot -> copy(midtones = value)
        ImageToneNumericParameter.Highlights -> copy(highlights = value)
        ImageToneNumericParameter.ShoulderWhitePoint -> copy(whites = value)
        ImageToneNumericParameter.Saturation -> copy(saturation = value)
        ImageToneNumericParameter.Vibrance -> copy(vibrance = value)
        else -> this
    }

data class ImportedLutProfile(
    val id: String,
    val name: String,
    val stages: List<ImportedLutStage>,
    val inputGamut: LutGamut = LutGamut.SRgbRec709,
    val inputTransfer: LutTransfer = LutTransfer.SRgb,
    val outputGamut: LutGamut = LutGamut.SRgbRec709,
    val outputTransfer: LutTransfer = LutTransfer.SRgb,
    val afterLut: AfterLutAction = AfterLutAction.UseDirectly,
    val tone: ProfileTone = ProfileTone.Neutral
)

enum class DemosaicAlgorithm(val label: String) {
    Amaze("AMaZE"),
    Rcd("RCD"),
    Vng4("VNG4"),
    DualRcdVng4("RCD + VNG4"),
    DualAmazeVng4("AMaZE + VNG4")
}

/** RawTherapee capture sharpening strength: Richardson-Lucy iterations after demosaic. */
/** Viewfinder control groups that can be hidden. */
enum class VfControl { Look, Exposure, InfoBar }

fun SettingsValues.withVfControl(control: VfControl, visible: Boolean): SettingsValues = when (control) {
    VfControl.Look -> copy(vfShowLook = visible)
    VfControl.Exposure -> copy(vfShowExposure = visible)
    VfControl.InfoBar -> copy(vfShowInfoBar = visible)
}

enum class CaptureSharpeningLevel(val label: String, val iterations: Int) {
    Low("Low", 2),
    Medium("Medium", 3),
    Sharp("Sharp", 4),
    Sharpest("Sharpest", 6)
}

enum class MultiframeMergeAlgorithm(val nativeId: Int, val label: String) {
    HdrPlusQuality(2, "HDR+ Quality"),
    /** Rawr 17U fork: Steve's v26 patch (HDR+ Quality on an underexposed burst plus post-shutter frames). */
    HdrPlusBracketed(3, "HDR+ Bracketed"),
    /** Kernel-regression Bayer merge for equal-exposure bursts, native resolution only (native/multiframe/merge_rexture). */
    Rexture(4, "Rexture");

    /** False when this build hides the algorithm (see [FeatureFlags]). */
    val available: Boolean
        get() = this != HdrPlusBracketed || FeatureFlags.hdrPlusBracketed
}

enum class MultiframeBaseFrameMode(val nativeId: Int, val label: String) {
    Middle(0, "Middle"),
    Sharpest(1, "Sharpest")
}

enum class MultiframeNumericParameter {
    MaxFrames,
    BracketEv,
    BracketFrames,
    LongFrameEv,
    UnderFrames,
    UnderEv
}

data class MultiframeNumericSpec(
    val parameter: MultiframeNumericParameter,
    val label: String,
    val supportingText: String,
    val minimum: Float,
    val maximum: Float,
    val defaultValue: Float,
    val step: Float,
    val decimals: Int,
    val unit: String = ""
) {
    fun accepts(value: Float): Boolean = value.isFinite() && value in minimum..maximum
}

/**
 * HDR+ Quality alignment strength and tile size, fixed (tuned on the test bursts; the merge now gates out frames where
 * they moved, so a global strength no longer trades ghosting against noise). Still sent in the native wire slots.
 */
private const val HDR_PLUS_STRENGTH = 13f
private const val HDR_PLUS_TILE_SIZE = 32f

/** Frames one multiframe capture may merge (native capture journal limit). */
const val MAX_BURST_FRAMES = 30

object MultiframeSpecs {
    val maxFrames =
        MultiframeNumericSpec(
            MultiframeNumericParameter.MaxFrames,
            "Maximum Frames",
            "Lower: faster but noisier. Higher: cleaner flats yet slightly softer edges, slower with more motion risk.",
            2f,
            30f,
            17f,
            1f,
            0
        )

    // HDR+ Bracketed, shown in this order: Underexpose, Overexpose, then
    // Maximum Frames (the total) and After-Shutter Frames (its split).
    val bracketEv =
        MultiframeNumericSpec(
            MultiframeNumericParameter.BracketEv,
            "ZSL Underexposure (max)",
            "The furthest below auto exposure the ZSL burst (and the live viewfinder stream, brightened back) may go to keep bright areas (sky, lamps) from clipping. Metered per scene: it goes only as dark as needed, often 0, and at most -1.5 EV in low light. Lower: recovers brighter highlights, noisier shadows. Higher: cleaner shadows. 0: a neutral burst (the metering stays off); the after-shutter under frames then protect the highlights. Auto exposure only.",
            -4f,
            0f,
            -1f,
            .5f,
            1,
            "EV"
        )

    val longFrameEv =
        MultiframeNumericSpec(
            MultiframeNumericParameter.LongFrameEv,
            "After-Shutter Exposure",
            "Exposure of the after-shutter frames relative to auto exposure (your exposure in manual), whatever the ZSL burst used. 0: at auto exposure. Always at least 1 EV brighter than the burst. A longer shutter first (up to 1/15 s), then more ISO; limited by both. Lower: less motion blur. Higher: cleaner deep shadows, highlights clip in these frames (the burst keeps them).",
            0f,
            3f,
            0f,
            .5f,
            1,
            "EV"
        )

    val bracketFrames =
        MultiframeNumericSpec(
            MultiframeNumericParameter.BracketFrames,
            "After-Shutter Frames",
            "Of Maximum Frames, how many are brighter frames taken right after the shutter press (hold still until the tick); the rest come from just before it. 0: none. Lower: shorter hold. Higher: cleaner shadows, longer hold and more motion risk. When the scene is too dark for them to get brighter, they are taken before the press instead.",
            0f,
            16f,
            2f,
            1f,
            0
        )

    val underEv =
        MultiframeNumericSpec(
            MultiframeNumericParameter.UnderEv,
            "After-Shutter Under Exposure",
            "Exposure of the darker after-shutter frames relative to auto exposure. They are used only for the highlights: where the merged result reaches the clip point of the ZSL burst, the highlight is taken from them, and the file is scaled down by the extra range. Lower: recovers brighter highlights but coarsens the shadows (beyond about -3 EV). Higher: less highlight range recovered. Not used when the ZSL burst already goes that far down.",
            -4f,
            -1f,
            -3f,
            .5f,
            1,
            "EV"
        )

    val underFrames =
        MultiframeNumericSpec(
            MultiframeNumericParameter.UnderFrames,
            "After-Shutter Under Frames",
            "Darker frames taken right after the shutter press for highlight recovery (counted in the after-shutter total, ahead of the brighter frames). 0: none, the ZSL burst alone protects the highlights. Lower: a shorter hold. Higher: a cleaner highlight layer, a longer hold. One is enough unless the highlights move.",
            0f,
            2f,
            1f,
            1f,
            0
        )

    val all = listOf(maxFrames, bracketEv, bracketFrames, longFrameEv, underFrames, underEv)

    fun forParameter(parameter: MultiframeNumericParameter): MultiframeNumericSpec =
        all.first { it.parameter == parameter }
}

data class MultiframeTuning(
    val mergeAlgorithm: MultiframeMergeAlgorithm = MultiframeMergeAlgorithm.Rexture,
    val bracketEv: Float = -1f,
    val bracketFrames: Int = 2,
    val longFrameEv: Float = 0f,
    val maxFrames: Int = 17,
    val underFrames: Int = 1,
    val underEv: Float = -3f
) {
    fun value(parameter: MultiframeNumericParameter): Float = when (parameter) {
        MultiframeNumericParameter.MaxFrames -> maxFrames.toFloat()
        MultiframeNumericParameter.BracketEv -> bracketEv
        MultiframeNumericParameter.BracketFrames -> bracketFrames.toFloat()
        MultiframeNumericParameter.LongFrameEv -> longFrameEv
        MultiframeNumericParameter.UnderFrames -> underFrames.toFloat()
        MultiframeNumericParameter.UnderEv -> underEv
    }

    fun withValue(parameter: MultiframeNumericParameter, value: Float): MultiframeTuning = when (parameter) {
        MultiframeNumericParameter.MaxFrames -> copy(maxFrames = value.roundToInt())
        MultiframeNumericParameter.BracketEv -> copy(bracketEv = (value * 2f).roundToInt() / 2f)
        MultiframeNumericParameter.BracketFrames -> copy(bracketFrames = value.roundToInt())
        MultiframeNumericParameter.LongFrameEv -> copy(longFrameEv = (value * 2f).roundToInt() / 2f)
        MultiframeNumericParameter.UnderFrames -> copy(underFrames = value.roundToInt())
        MultiframeNumericParameter.UnderEv -> copy(underEv = (value * 2f).roundToInt() / 2f)
    }

    fun sanitized(defaults: MultiframeTuning = MultiframeTuning()): MultiframeTuning {
        var clean = this
        for (spec in MultiframeSpecs.all) {
            val candidate = clean.value(spec.parameter)
            clean =
                clean.withValue(
                    spec.parameter,
                    if (spec.accepts(candidate)) candidate else defaults.value(spec.parameter)
                )
        }
        return clean
    }

    // The contract slots of the native tuning that no merge reads any more, sent as their former defaults.
    fun nativeValues(): FloatArray = floatArrayOf(
        1f, 5f, 1e-10f, .15f, 5f, .71f, 1f, 2f, 8f, 1f, .15f, 1f, 1f, 3f, .01f, .04f, .08f, 1.5f, 9f, .65f, 4f, 2f
    )

    /** Most after-shutter frames HDR+ Bracketed can take: the total minus a 2-frame burst, at most 16. */
    fun maxPostShutterFrames(): Int = (maxFrames - 2).coerceIn(0, 16)

    /** ZSL frames to freeze at the shutter; HDR+ Bracketed leaves the rest of Maximum Frames to its after-shutter frames. */
    fun zslFrames(): Int =
        if (mergeAlgorithm == MultiframeMergeAlgorithm.HdrPlusBracketed) (maxFrames - allPostShutterFrames()).coerceAtLeast(2)
        else maxFrames

    /** Darker after-shutter frames HDR+ Bracketed takes for the highlights (0 for other merges): up to 2. */
    fun underShutterFrames(): Int =
        if (mergeAlgorithm == MultiframeMergeAlgorithm.HdrPlusBracketed) underFrames.coerceIn(0, minOf(2, maxPostShutterFrames()))
        else 0

    /** Every frame taken after the shutter: the under frames, then the long frames. */
    fun allPostShutterFrames(): Int = postShutterFrames() + underShutterFrames()

    /** Long frames HDR+ Bracketed requests after the shutter (0 for other merges). */
    fun postShutterFrames(): Int =
        if (mergeAlgorithm == MultiframeMergeAlgorithm.HdrPlusBracketed) bracketFrames.coerceIn(0, (maxPostShutterFrames() - underShutterFrames()).coerceAtLeast(0)) else 0

    // Appended after the Rexture detail flag (native indices 30-31): under frames and their exposure over AE.
    fun nativeUnderValues(): FloatArray = floatArrayOf(underShutterFrames().toFloat(), underEv)

    // Appended after the multiframe chroma-denoise flag (native indices 23-28). The bracket slots carry HDR+ Bracketed's
    // values; every other merge sends Ed's unused defaults (-3, 0, 0), as before.
    fun nativeMergeValues(): FloatArray {
        val bracketed = mergeAlgorithm == MultiframeMergeAlgorithm.HdrPlusBracketed
        return floatArrayOf(
            mergeAlgorithm.nativeId.toFloat(),
            HDR_PLUS_STRENGTH,
            HDR_PLUS_TILE_SIZE,
            if (bracketed) bracketEv else -3f,
            postShutterFrames().toFloat(),
            if (bracketed) longFrameEv else 0f
        )
    }
}

/** Multiframe tuning for [lensId]: the lens's own entry when it has one, else the global tuning. */
fun SettingsValues.multiframeTuningFor(lensId: String?): MultiframeTuning =
    lensId?.let { multiframeTuningByLens[it] } ?: multiframeTuning

/** Multiframe tuning of the lens in use ([SettingsValues.lastLensId]). */
val SettingsValues.activeMultiframeTuning: MultiframeTuning
    get() = multiframeTuningFor(lastLensId)

/** Whether the lens in use has its own multiframe tuning. */
val SettingsValues.activeLensHasOwnMultiframeTuning: Boolean
    get() = lastLensId?.let { it in multiframeTuningByLens } == true

/** Most full-size frames the lens in use may merge per its profile ([lenses] = device defaults); 0 = no limit. */
fun SettingsValues.activeLensMergeFrameLimit(lenses: List<LensProfile>): Int {
    val id = lastLensId ?: return 0
    return effectiveLensProfiles(lenses).firstOrNull { it.name.trim() == id }?.streams?.photo?.mergeFrameLimit ?: 0
}

/** Frames the next merged photo on the lens in use takes: the setting, capped by the lens profile. */
fun SettingsValues.activeMultiframeFrames(lenses: List<LensProfile>): Int {
    val wanted = activeMultiframeTuning.maxFrames
    val limit = activeLensMergeFrameLimit(lenses)
    return if (limit > 0) minOf(wanted, limit) else wanted
}

/** Applies [edit] to the tuning that the lens in use reads: its own entry, or the global one. */
fun SettingsValues.editActiveMultiframeTuning(edit: (MultiframeTuning) -> MultiframeTuning): SettingsValues {
    val lens = lastLensId
    val own = lens?.let { multiframeTuningByLens[it] }
    return if (lens != null && own != null) copy(multiframeTuningByLens = multiframeTuningByLens + (lens to edit(own)))
    else copy(multiframeTuning = edit(multiframeTuning))
}

data class SettingsValues(
    val saveLocationId: String,
    val falseColorPresetId: String,
    val peakingSensitivityId: String,
    val imageTone: ImageToneState,
    val selfTimer: SelfTimer = SelfTimer.Off,
    val locationTagging: Boolean = false,
    val oisEnabledPreference: Boolean = true,
    /** User lens configuration in capture-screen order; null = this device's built-in lenses. */
    val lensProfiles: List<LensProfile>? = null,
    /** Lens in use when the app last switched lenses; null = the profile's first lens. Shared by photo and video. */
    val lastLensId: String? = null,
    val antiFlicker: AntiFlicker = AntiFlicker.Auto,
    val exposureStep: ExposureStep = ExposureStep.Third,
    val highlightProtection: HighlightProtection = HighlightProtection.High,
    /** Adaptive shadow-lift strength for photos (when Photo tone mapping is Adaptive). */
    val autoKeyExposure: AutoKeyExposure = AutoKeyExposure.Medium,
    /** Adaptive shadow-lift strength for video (always adaptive). */
    val videoAutoKeyExposure: AutoKeyExposure = AutoKeyExposure.Medium,
    /** Photo tone mapping (JPEG/viewfinder only; video always Adaptive). */
    val photoToneMapping: PhotoToneMapping = PhotoToneMapping.Adaptive,
    /** Local tone mapping: how far shadows may be brightened (EV), noise-limited natively. */
    val localToneShadowsEv: Float = LocalToneLimits.DEFAULT_SHADOWS_EV,
    val localToneTuning: LocalToneTuning = LocalToneTuning(),
    val maxPostGainId: String = "gain.200",
    val autoMinFpsId: String = "fps.12",
    val controlSurfaceStyle: ControlSurfaceStyle = ControlSurfaceStyle.Basic,
    val captureControlLayout: CaptureControlLayout = CaptureControlLayout.Compact,
    val jpegEnabled: Boolean = true,
    val dngEnabled: Boolean = true,
    val dngCompressionId: String = "dng.lossless",
    val gridMode: GridMode = GridMode.Thirds,
    /** Capture-screen DCP shortcut next to the lens selector. */
    val showDcpQuickSelect: Boolean = true,
    /** Viewfinder control groups shown over the preview (Settings › Display, or the eye's VF CONTROLS). */
    val vfShowLook: Boolean = true,
    val vfShowExposure: Boolean = true,
    val vfShowInfoBar: Boolean = true,
    /** The eye (MONITORING) panel offers VF CONTROLS to show/hide those groups while shooting. */
    val vfEyeControls: Boolean = true,
    /** Horizon/level overlay on the capture viewfinder. */
    val levelMeterEnabled: Boolean = false,
    /** Rear light sensor readout (lux, EV, colour temperature, flicker) on the capture viewfinder. */
    val lightMeterEnabled: Boolean = false,
    // Preview-only look-stage (tonemap or film) downsample divisor, 1..4.
    // Film always runs at 2 or more. Never touches stills, video, or EXIF.
    val viewfinderDivisor: Int = 2,
    val lensBarGrouping: LensBarGrouping = LensBarGrouping.Auto,
    val lensBarSize: LensBarSize = LensBarSize.Normal,
    val armedOverlays: Set<OverlayMode> =
        setOf(OverlayMode.Peaking, OverlayMode.TonemapShadows, OverlayMode.RawHighlights),
    val falseColorManual: Boolean = false,
    val activeScopes: List<ScopeType> = listOf(ScopeType.Waveform),
    val waveformMode: WaveformMode = WaveformMode.RgbOverlay,
    val colorRenderProfile: ColorRenderProfile = ColorRenderProfile.RawrBase,
    val selectedUserLutProfileId: String? = null,
    val userLutProfiles: List<ImportedLutProfile> = emptyList(),
    /**
     * RAWR NTRL's own TONE set. Each [ImportedLutProfile] carries its own
     * [ProfileTone]; this is the regular profile's counterpart. The 9 tone
     * fields inside [imageTone] are legacy storage only — the applied tone is
     * always [activeImageTone].
     */
    val rawrBaseTone: ProfileTone = ProfileTone.Neutral,
    val srgbTone: ProfileTone = ProfileTone.Neutral,
    val rec709Tone: ProfileTone = ProfileTone.Neutral,
    /** AgX's render exposure (its other display tone fields are unused), shared by its presets. */
    val agxTone: ProfileTone = ProfileTone.Neutral,
    val agxPreset: AgxPreset = AgxPreset.Rawr,
    val videoAgxPreset: AgxPreset = AgxPreset.Rawr,
    /** Saved look origins survive edits; photo and video remember their own selection. */
    val selectedAgxLookId: String? = null,
    val videoAgxLookId: String? = null,
    /** Edited AgX controls per preset; a missing preset uses its defaults. */
    val agxValues: Map<AgxPreset, AgxValues> = emptyMap(),
    /** User-saved AgX looks; applying one writes its values into its base look. */
    val agxLooks: List<AgxLook> = emptyList(),
    val videoColorRenderProfile: ColorRenderProfile = ColorRenderProfile.RawrBase,
    val videoUserLutProfileId: String? = null,
    val videoLogEnabled: Boolean = false,
    /** Photo aspect ratio id (PhotoAspect); crops the JPEG only. */
    val photoAspectId: String = "native",
    /** Rawr 17U fork: the tele's optical zoom on the 4.3x lenses, 75 to 100 mm (TeleOpticalZoom). */
    val teleZoomMm: Int = 75,
    val videoLogProfile: VideoLogProfile = VideoLogProfile.LogC3,
    val pipelineDiagnosticsEnabled: Boolean = false,
    val experimentalZeroCopyEnabled: Boolean = true,
    val experimentalMultiframeEnabled: Boolean = false,
    val saveBaseDng: Boolean = true,
    val persistentEngineEnabled: Boolean = true,
    val filmSimEnabled: Boolean = false,
    val filmSimLook: FilmSimLook = FilmFactoryPresets.baseLook,
    val selectedFilmPresetId: String? = null,
    val filmPresets: List<FilmPreset> = emptyList(),
    val multiframeBaseFrameMode: MultiframeBaseFrameMode = MultiframeBaseFrameMode.Sharpest,
    // Chroma-only profiled wavelet on the merged image, fed the burst-fitted
    // noise / frame count. Independent of Image > Denoise (single frame only). Off by default.
    val multiframeChromaDenoise: Boolean = false,
    // Rexture only: render the JPEG from the merged RGB (no re-mosaic, no demosaic setting). The DNG is unaffected. On by
    // default: with site chroma it is as clean and sharp as re-mosaic + AMaZE and ~210 ms faster per 12 MP capture.
    val multiframeRextureNativeRgb: Boolean = true,
    /** Rexturation (Rexture's detail fit, native rx_fit pass; on by default): merged image, so it is in the DNG and the JPEG. */
    val multiframeRextureDetailRestore: Boolean = true,
    val multiframeTuning: MultiframeTuning = MultiframeTuning(),
    /** Per-lens multiframe tuning keyed by lens id (profile name); lenses without an entry use [multiframeTuning]. */
    val multiframeTuningByLens: Map<String, MultiframeTuning> = emptyMap(),
    // Per-lens look ("Own look for <lens>"): the look fields above always hold the active lens's look; these keep
    // the other lenses' looks and the shared one while a lens with its own look is active.
    val lookByLens: Map<String, LensLook> = emptyMap(),
    val sharedLook: LensLook? = null,
    val customGpuDriverEnabled: Boolean = false,
    val customGpuDriverName: String? = null,
    val persistZslRingEnabled: Boolean = false,
    val persistZslRingOnShutterEnabled: Boolean = false,
    val demosaicAlgorithm: DemosaicAlgorithm = DemosaicAlgorithm.Amaze,
    val dualAutoContrast: Boolean = true,
    val dualContrastPercent: Float = 20f,
    // Photo FCC steps (stills). Video FCC is an enable with a fixed single step.
    val photoFccSteps: Int = 2,
    val videoFccEnabled: Boolean = false,
    // Photo defringe (stills). Video strengths are independent.
    val photoDefringeEnabled: Boolean = true,
    val photoDefringeStrength: Float = 1f,
    val photoDefringeEdgeThreshold: Float = 0.02f,
    val photoDefringeLumaFloor: Float = 0.08f,
    val videoDefringeEnabled: Boolean = false,
    val videoDefringeStrength: Float = 1f,
    // Photo capture sharpening (render only: JPEG and Renderer, never the DNG).
    val photoCaptureSharpeningEnabled: Boolean = true,
    val photoCaptureSharpeningLevel: CaptureSharpeningLevel = CaptureSharpeningLevel.Low,
    // Photo-pipeline denoise, single source of truth (see DenoiseConfig).
    // Off by default; Wavelet is profiled (sensor noise model).
    val photoDenoise: DenoiseConfig = DenoiseConfig.Off(),
    // Video denoise has its own wavelet primitives.
    val videoDenoiseEnabled: Boolean = false,
    val videoDenoiseStrength: Float = 1f,
    val videoDenoiseDetail: Float = 1f,
    val videoDenoiseLuma: Float = 0.25f,
    val videoDenoiseScales: Int = 7,
    // Photo lens shading (preview + JPEG stills). Video is independent.
    val photoLensShadingEnabled: Boolean = true,
    val videoLensShadingEnabled: Boolean = true,
    // Photo (preview + stills + DNG warp) and video undistort, for lenses
    // whose profile carries a calibration.
    val distortionCorrectionEnabled: Boolean = true,
    val videoDistortionCorrectionEnabled: Boolean = true,
    // Photo highlight reconstruction (stills; live preview uses color
    // propagation). Video has its own method + controls below.
    val photoHighlightEnabled: Boolean = true,
    // 0 = existing propagation; 1 = RawTherapee Coloropp for still renders.
    val photoHighlightMethod: Int = 1,
    val photoHighlightThreshold: Float = 1f,
    val photoHighlightCompression: Float = 45f,
    val videoHighlightEnabled: Boolean = false,
    val videoHighlightMethod: Int = 0,
    val videoHighlightThreshold: Float = 1f,
    val videoHighlightCompression: Float = 100f,
    // UltraHDR (JPEG_R) still output: GPU gain map + MPF/XMP/ISO mux.
    // On by default; legacy SDR JPEG when off.
    val ultraHdrEnabled: Boolean = true,
    // Rawr 17U fork: the system's camera sounds (shutter, video start / stop). Off by default; where the law asks
    // for the shutter sound (MediaActionSound.mustPlayShutterSound) it plays regardless.
    val shutterSound: Boolean = false,
    // Where the system asks for the shutter sound, play it even when Shutter Sound is off (the default). Off: the switch decides.
    val shutterSoundFollowSystem: Boolean = true,
    // Take a photo by saying "Cheese" or "Capture" (offline speech recognizer, integration/VoiceShutter.kt). Off by default.
    val voiceShutter: Boolean = false,
    // Stream the viewfinder to the Rawr app on a paired Wear OS watch and take photos from it (integration/WatchRemote.kt).
    val watchRemote: Boolean = false,
    /** Rawr 17U fork: how the TELE cell's optical zoom control looks - "bar", "wheel", "dial" or "rocker". */
    val teleZoomStyle: String = "bar",
    /** Rawr 17U fork: the TELE control stays on screen while the tele (75-100 mm) is selected. Off by default. */
    val teleZoomAlwaysOn: Boolean = false,
    /** Rawr 17U fork: where the S/I priority switch is drawn (PriorityToggleStyle id: "off", "top_bar", "exposure_cell"). */
    val priorityToggleStyle: String = "off",
    /** Rawr 17U fork: the S/I switch's ISO for ISO priority, and shutter (ns) for shutter priority. */
    val priorityToggleIso: Int = com.rawr.camera.model.PriorityTogglePresets.DEFAULT_ISO,
    val priorityToggleShutterNs: Long = com.rawr.camera.model.PriorityTogglePresets.DEFAULT_SHUTTER_NS,
    // Ignore Camera2 shutter/ISO priority modes: locking SS or ISO always enters
    // full Manual. Off by default.
    val aePriorityDisabled: Boolean = false,
    val rawrAe: RawrAeSettings = RawrAeSettings(),
    // Photo/Video capture mode + video recording selectors. Plain primitives
    // keep the DataStore schema stable; validated through the helpers below.
    val captureModeId: String = "photo",
    val videoResolutionId: String = "1080p",
    val videoFps: Int = 30,
    val videoEncoder: VideoEncoderConfig = VideoEncoderConfig()
) {
    val isVideo: Boolean get() = captureModeId == "video"
    val isLogActive: Boolean get() = isVideo && videoLogEnabled
    fun regularRenderProfile(): ColorRenderProfile = if (isVideo) videoColorRenderProfile else colorRenderProfile
    fun regularLutProfileId(): String? = if (isVideo) videoUserLutProfileId else selectedUserLutProfileId
    fun effectiveVideoBitDepth(): Int = if (videoLogEnabled) 10 else videoEncoder.sanitized().bitDepth
    fun effectiveRenderProfileId(): Int =
        if (isLogActive) videoLogProfile.nativeId else regularRenderProfile().nativeIdFor(isVideo)
    fun regularAgxPreset(): AgxPreset = if (isVideo) videoAgxPreset else agxPreset
    fun agxValuesFor(preset: AgxPreset): AgxValues = agxValues[preset] ?: preset.defaults
    fun activeAgxValues(): AgxValues = agxValuesFor(regularAgxPreset())
    fun isAgxActive(): Boolean = !isLogActive && regularRenderProfile() == ColorRenderProfile.AgX

    fun captureRendererDisplayName(): String = activeProfileLabel()

    fun selectedLutProfile(): ImportedLutProfile? =
        if (regularRenderProfile() == ColorRenderProfile.UserLut)
            userLutProfiles.firstOrNull { it.id == regularLutProfileId() }
        else null

    /** Tone edits must not rebuild immutable LUT resources. */
    fun renderProfileSyncKey(): Triple<Int, String?, ImportedLutProfile?> = Triple(
        effectiveRenderProfileId(),
        regularLutProfileId().takeUnless { isLogActive },
        selectedLutProfile()?.copy(tone = ProfileTone.Neutral).takeUnless { isLogActive }
    )

    fun activeProfileTone(): ProfileTone = when {
        isLogActive -> ProfileTone.Neutral
        regularRenderProfile() == ColorRenderProfile.SRgb -> srgbTone
        regularRenderProfile() == ColorRenderProfile.Rec709 -> rec709Tone
        // AgX skips the display tone controls; only render exposure applies.
        regularRenderProfile() == ColorRenderProfile.AgX -> ProfileTone(renderExposure = agxTone.renderExposure)
        else -> selectedLutProfile()?.tone ?: rawrBaseTone
    }

    fun activeImageTone(): ImageToneState = TonemapCatalog.all.fold(imageTone) { acc, descriptor ->
        acc.withValue(descriptor, activeProfileTone().valueFor(descriptor))
    }

    fun activeProfileLabel(): String = when {
        isLogActive -> videoLogProfile.label
        isAgxActive() -> selectedAgxLook()?.name ?: regularAgxPreset().label
        else -> selectedLutProfile()?.name?.takeIf { it.isNotBlank() } ?: regularRenderProfile().label
    }

    /** Copy of [ProfileTone] derived from the legacy global tone (v22 migration source). */
    fun profileToneFromLegacyImageTone(): ProfileTone = ProfileTone(
        renderExposure = imageTone.renderExposure,
        blacks = imageTone.blacks,
        shadows = imageTone.shadows,
        contrast = imageTone.contrast,
        midtones = imageTone.midtones,
        highlights = imageTone.highlights,
        whites = imageTone.whites,
        saturation = imageTone.saturation,
        vibrance = imageTone.vibrance
    )
}

sealed interface SettingsDestination {
    data object Home : SettingsDestination

    data class Section(val section: SettingsSection) : SettingsDestination

    data class ChoiceSelector(val kind: ChoiceSelectorKind) : SettingsDestination

    data class FilmSimSubPage(val section: FilmSimSection) : SettingsDestination

    data class FilmSimDetailPage(val detail: FilmSimDetail) : SettingsDestination

    /** Lens profile editor; [lensName] null creates a new lens. */
    data class LensEditor(val lensName: String?) : SettingsDestination
}

enum class ChoiceSelectorKind {
    SaveLocation,
    FalseColorPreset,
    PeakingSensitivity,
    OutputColorSpace,
    TransferFunction,
    JpegChromaSubsampling,
    DngCompression,
    MaxPostGain,
    AutoMinFps,
    FilmOutputSpace
}

data class SettingsPresentationState(
    val destination: SettingsDestination = SettingsDestination.Home,
    // Temporal back history (most-recent-last). NavigateBack pops; empty
    // falls back to the logical-parent mapping in SettingsReducer.
    val backStack: List<SettingsDestination> = emptyList(),
    val imageToneTab: ImageToneTab = ImageToneTab.ExposureTonality,
    val pendingReset: ResetTarget? = null
)

data class SettingsEffectiveState(
    val saveLocationId: String? = null,
    val falseColorPresetId: String? = null,
    val peakingSensitivityId: String? = null,
    val outputColorSpaceId: String? = null,
    val transferFunctionId: String? = null,
    val jpegChromaSubsamplingId: String? = null,
    val maxPostGainId: String? = null,
    val autoMinFpsId: String? = null
)

enum class SettingApplicationStatus { Applied, Pending, Failed, Unsupported }

data class SettingApplicationState<T>(
    val effectiveValue: T? = null,
    val status: SettingApplicationStatus = SettingApplicationStatus.Pending
)

data class SettingsApplicationState(
    val ois: SettingApplicationState<Boolean> = SettingApplicationState(),
    val antiFlicker: SettingApplicationState<AntiFlicker> = SettingApplicationState(),
    val imageTone: SettingApplicationState<ImageToneState> = SettingApplicationState()
)

data class SettingsRuntimeState(
    val effective: SettingsEffectiveState = SettingsEffectiveState(),
    val application: SettingsApplicationState = SettingsApplicationState(),
    /**
     * Monotonic identity for the currently active camera/capability application context.
     * Backend acknowledgements must carry this generation so late results from a replaced
     * camera cannot repopulate effective application state.
     */
    val capabilityContextGeneration: Long = 0L
) {
    init {
        require(capabilityContextGeneration >= 0L)
    }
}

data class SettingsUiState(
    val capabilities: SettingsCapabilities,
    val values: SettingsValues,
    val runtime: SettingsRuntimeState = SettingsRuntimeState(),
    val presentation: SettingsPresentationState = SettingsPresentationState()
)

fun SettingsCapabilities.resolveEffective(values: SettingsValues): SettingsEffectiveState = SettingsEffectiveState(
    saveLocationId =
        values.saveLocationId.takeIf { id ->
            id.startsWith("storage.tree:") ||
                saveLocationChoices.any { it.id == id }
        },
    falseColorPresetId = values.falseColorPresetId.takeIf { id -> falseColorPresets.any { it.id == id } },
    peakingSensitivityId =
        values.peakingSensitivityId.takeIf { id ->
            peakingSensitivityChoices.any { it.id == id }
        },
    outputColorSpaceId = values.imageTone.outputColorSpaceId.takeIf { id ->
        outputColorSpaces.any { it.id == id }
    },
    transferFunctionId = values.imageTone.transferFunctionId.takeIf { id -> transferFunctions.any { it.id == id } },
    jpegChromaSubsamplingId =
        values.imageTone.jpegChromaSubsamplingId.takeIf { id ->
            jpegChromaSubsamplingChoices.any {
                it.id ==
                    id
            }
        },
    maxPostGainId = values.maxPostGainId.takeIf { id -> maxPostGainChoices.any { it.id == id } },
    autoMinFpsId = values.autoMinFpsId.takeIf { id -> autoMinFpsChoices.any { it.id == id } }
)

object ImageToneSpecs {
    // Values wired directly to TonemapEngine 1.0.0 public TonemapParams where supported.

    /** Builds a spec from [TonemapCatalog]; the catalog owns labels/ranges/defaults. */
    private fun toneSpec(parameter: ImageToneNumericParameter): NumericSettingSpec =
        parameter.tonemapDescriptor().let {
            NumericSettingSpec(parameter, it.longLabel, it.minimum, it.maximum, it.defaultValue, it.step, it.unit, it.decimals)
        }

    val renderExposure = toneSpec(ImageToneNumericParameter.RenderExposure)
    val blacks = toneSpec(ImageToneNumericParameter.BlackToe)
    val shadows = toneSpec(ImageToneNumericParameter.Shadows)
    val contrast = toneSpec(ImageToneNumericParameter.Contrast)
    val midtones = toneSpec(ImageToneNumericParameter.MidtonePivot)
    val highlights = toneSpec(ImageToneNumericParameter.Highlights)
    val whites = toneSpec(ImageToneNumericParameter.ShoulderWhitePoint)
    val saturation = toneSpec(ImageToneNumericParameter.Saturation)
    val vibrance = toneSpec(ImageToneNumericParameter.Vibrance)
    val colorRenderingStrength =
        NumericSettingSpec(
            ImageToneNumericParameter.ColorRenderingStrength,
            "Color Rendering Strength",
            0f,
            1f,
            1f,
            .01f,
            "",
            2
        )
    val wbTemperature =
        NumericSettingSpec(ImageToneNumericParameter.WbTemperature, "WB Temperature", -100f, 100f, 0f, 1f)
    val wbTint = NumericSettingSpec(ImageToneNumericParameter.WbTint, "WB Tint", -100f, 100f, 0f, 1f)
    val jpegQuality = NumericSettingSpec(ImageToneNumericParameter.JpegQuality, "JPEG Quality", 95f, 100f, 98f, 1f, "%")
}

/**
 * Maps the settings parameter enum onto the canonical [TonemapCatalog] entry.
 * Only the nine per-profile tone controls participate; global image params
 * (color rendering strength, WB, JPEG) keep their own specs.
 */
fun ImageToneNumericParameter.tonemapDescriptor(): TonemapParam = when (this) {
    ImageToneNumericParameter.RenderExposure -> TonemapCatalog.renderExposure
    ImageToneNumericParameter.BlackToe -> TonemapCatalog.forParameter(ToneParameter.Blacks)
    ImageToneNumericParameter.Shadows -> TonemapCatalog.forParameter(ToneParameter.Shadows)
    ImageToneNumericParameter.Contrast -> TonemapCatalog.forParameter(ToneParameter.Contrast)
    ImageToneNumericParameter.MidtonePivot -> TonemapCatalog.forParameter(ToneParameter.Midtones)
    ImageToneNumericParameter.Highlights -> TonemapCatalog.forParameter(ToneParameter.Highlights)
    ImageToneNumericParameter.ShoulderWhitePoint -> TonemapCatalog.forParameter(ToneParameter.Whites)
    ImageToneNumericParameter.Saturation -> TonemapCatalog.forParameter(ToneParameter.Saturation)
    ImageToneNumericParameter.Vibrance -> TonemapCatalog.forParameter(ToneParameter.Vibrance)
    else -> error("$this is not a tonemap tone control")
}

fun ImageToneNumericParameter.reviewSpec(): NumericSettingSpec = when (this) {
    ImageToneNumericParameter.RenderExposure -> ImageToneSpecs.renderExposure
    ImageToneNumericParameter.BlackToe -> ImageToneSpecs.blacks
    ImageToneNumericParameter.Shadows -> ImageToneSpecs.shadows
    ImageToneNumericParameter.Contrast -> ImageToneSpecs.contrast
    ImageToneNumericParameter.MidtonePivot -> ImageToneSpecs.midtones
    ImageToneNumericParameter.Highlights -> ImageToneSpecs.highlights
    ImageToneNumericParameter.ShoulderWhitePoint -> ImageToneSpecs.whites
    ImageToneNumericParameter.Saturation -> ImageToneSpecs.saturation
    ImageToneNumericParameter.Vibrance -> ImageToneSpecs.vibrance
    ImageToneNumericParameter.ColorRenderingStrength -> ImageToneSpecs.colorRenderingStrength
    ImageToneNumericParameter.WbTemperature -> ImageToneSpecs.wbTemperature
    ImageToneNumericParameter.WbTint -> ImageToneSpecs.wbTint
    ImageToneNumericParameter.JpegQuality -> ImageToneSpecs.jpegQuality
}

fun NumericSettingSpec.accepts(value: Float): Boolean = value.isFinite() && value in minimum..maximum

/** Photo/Video capture mode for a persisted ID. Unknown values fall back to Photo. */
fun captureModeForId(id: String): com.rawr.camera.model.CaptureMode =
    if (id == "video") com.rawr.camera.model.CaptureMode.Video else com.rawr.camera.model.CaptureMode.Photo

fun com.rawr.camera.model.CaptureMode.persistedId(): String =
    if (this == com.rawr.camera.model.CaptureMode.Video) "video" else "photo"

/** Video recording resolution for a persisted ID. Unknown values fall back to 1080p. */
fun videoResolutionForId(id: String): com.rawr.camera.video.VideoResolutionMode = when (id) {
    "4k" -> com.rawr.camera.video.VideoResolutionMode.UHD4K
    "open_gate" -> com.rawr.camera.video.VideoResolutionMode.OPEN_GATE
    else -> com.rawr.camera.video.VideoResolutionMode.HD1080
}

fun com.rawr.camera.video.VideoResolutionMode.persistedId(): String = when (this) {
    com.rawr.camera.video.VideoResolutionMode.UHD4K -> "4k"
    com.rawr.camera.video.VideoResolutionMode.OPEN_GATE -> "open_gate"
    com.rawr.camera.video.VideoResolutionMode.HD1080 -> "1080p"
}

/** Recording frame rate clamp; only 24 and 30 are supported by the native recorder. */
fun videoFpsOrDefault(fps: Int): Int = if (fps in com.rawr.camera.architecture.VIDEO_FPS_CHOICES) fps else 30

/** Linear AE post-gain cap for an ID (gain.100=1x, gain.200=2x, gain.400=4x). Defaults to 4x. */
fun maxPostGainForId(id: String): Float = when (id) {
    "gain.100" -> 1f
    "gain.200" -> 2f
    else -> 4f
}

/** Auto-AE FPS floor for an ID (fps.30 == off/template). Defaults to 15. */
fun autoMinFpsForId(id: String): Int = when (id) {
    "fps.30" -> 30
    "fps.24" -> 24
    "fps.20" -> 20
    "fps.12" -> 12
    "fps.8" -> 8
    else -> 15
}

/** NDK AE antibanding mode (OFF=0, 50HZ=1, 60HZ=2, AUTO=3). */
fun antibandingModeFor(value: AntiFlicker): Int = when (value) {
    AntiFlicker.Hz50 -> 1
    AntiFlicker.Hz60 -> 2
    AntiFlicker.Off -> 0
    AntiFlicker.Auto -> 3
}

/**
 * Auto follows the phone's flicker sensor when it has confirmed mains flicker (100 Hz -> 50HZ,
 * 120 Hz -> 60HZ) and otherwise leaves the decision to the camera's own detection. The explicit
 * choices always win.
 */
fun antibandingModeFor(value: AntiFlicker, sensedFlickerHz: Int?): Int = when {
    value != AntiFlicker.Auto -> antibandingModeFor(value)
    sensedFlickerHz == 100 -> 1
    sensedFlickerHz == 120 -> 2
    else -> 3
}

/** Post-gain soft-knee width in EV for a HighlightProtection level. */
fun postGainKneeWidthEvFor(value: HighlightProtection): Float = when (value) {
    HighlightProtection.Low -> 2f
    HighlightProtection.High -> 0.5f
    HighlightProtection.Normal -> 1f
}


/** What a lens can keep for itself: render profile / LUT, tone sliders, AgX choice and the film look. */
data class LensLook(
    val colorRenderProfile: ColorRenderProfile,
    val selectedUserLutProfileId: String?,
    val rawrBaseTone: ProfileTone,
    val srgbTone: ProfileTone,
    val agxTone: ProfileTone,
    val agxPreset: AgxPreset,
    val selectedAgxLookId: String?,
    val filmSimEnabled: Boolean,
    val filmSimLook: FilmSimLook,
    val selectedFilmPresetId: String?
)

val SettingsValues.currentLook: LensLook
    get() = LensLook(
        colorRenderProfile, selectedUserLutProfileId, rawrBaseTone, srgbTone, agxTone, agxPreset,
        selectedAgxLookId, filmSimEnabled, filmSimLook, selectedFilmPresetId
    )

fun SettingsValues.withLook(look: LensLook): SettingsValues = copy(
    colorRenderProfile = look.colorRenderProfile,
    selectedUserLutProfileId = look.selectedUserLutProfileId?.takeIf { id -> userLutProfiles.any { it.id == id } },
    rawrBaseTone = look.rawrBaseTone,
    srgbTone = look.srgbTone,
    agxTone = look.agxTone,
    agxPreset = look.agxPreset,
    selectedAgxLookId = look.selectedAgxLookId?.takeIf { id -> agxLooks.any { it.id == id && it.base == look.agxPreset } },
    filmSimEnabled = look.filmSimEnabled,
    filmSimLook = look.filmSimLook,
    selectedFilmPresetId = look.selectedFilmPresetId?.takeIf { id -> filmPresets.any { it.id == id } }
)

val SettingsValues.activeLensHasOwnLook: Boolean
    get() = lastLensId?.let { it in lookByLens } == true

/**
 * Lens switch: the outgoing lens's look is stored (its own slot, or the shared one) and the incoming lens's
 * look becomes the active one. Lenses without their own look all use the shared look.
 */
fun SettingsValues.withLensSelected(lensId: String?): SettingsValues {
    if (lensId == lastLensId) return this
    val current = currentLook
    val previous = lastLensId
    val stored = if (previous != null && previous in lookByLens) copy(lookByLens = lookByLens + (previous to current))
    else copy(sharedLook = current)
    val target = lensId?.let { stored.lookByLens[it] } ?: stored.sharedLook ?: current
    return stored.withLook(target).copy(lastLensId = lensId)
}

/** "Own look for <active lens>": on starts from the current look; off returns the lens to the shared look. */
fun SettingsValues.withOwnLookForActiveLens(own: Boolean): SettingsValues {
    val lens = lastLensId ?: return this
    val current = currentLook
    return if (own) {
        if (lens in lookByLens) this else copy(lookByLens = lookByLens + (lens to current), sharedLook = current)
    } else {
        if (lens !in lookByLens) this else copy(lookByLens = lookByLens - lens).withLook(sharedLook ?: current)
    }
}

/**
 * Rawr 17U fork: the 17 Ultra profile's tele lenses are "Tele DCG"... (were "3.2x DCG" fixed at 75 mm and "4.3x DCG"
 * moving the optics). The last lens and the per-lens multiframe tuning and look follow the new ids; "4.3x" entries win
 * over "3.2x" ones. Only with the built-in lens list: a list the user edited keeps its own ids. Idempotent.
 */
fun SettingsValues.withTeleLensIds(): SettingsValues {
    if (lensProfiles != null) return this
    fun <T> rename(map: Map<String, T>): Map<String, T> {
        if (map.keys.none { com.rawr.camera.model.TeleOpticalZoom.teleId(it) != it }) return map
        val out = LinkedHashMap<String, T>()
        map.filterKeys { it.startsWith("3.2x ") }.forEach { (k, v) -> out[com.rawr.camera.model.TeleOpticalZoom.teleId(k)] = v }
        map.filterKeys { !it.startsWith("3.2x ") }.forEach { (k, v) -> out[com.rawr.camera.model.TeleOpticalZoom.teleId(k)] = v }
        return out
    }
    val last = lastLensId?.let { com.rawr.camera.model.TeleOpticalZoom.teleId(it) }
    val tuning = rename(multiframeTuningByLens)
    val looks = rename(lookByLens)
    if (last == lastLensId && tuning === multiframeTuningByLens && looks === lookByLens) return this
    return copy(lastLensId = last, multiframeTuningByLens = tuning, lookByLens = looks)
}
