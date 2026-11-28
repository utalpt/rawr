package com.rawr.camera.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rawr.camera.settings.architecture.*
import com.rawr.camera.settings.model.*

@Composable
internal fun MultiframeSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val enabled = state.values.experimentalMultiframeEnabled
    val tuning = state.values.activeMultiframeTuning
    val lensId = state.values.lastLensId
    val ownTuning = state.values.activeLensHasOwnMultiframeTuning
    val lensFrameLimit = state.values.activeLensMergeFrameLimit(LocalLensHardware.current.deviceDefaults)
    val spec = { parameter: MultiframeNumericParameter -> MultiframeSpecs.forParameter(parameter) }
    val rexture = tuning.mergeAlgorithm == MultiframeMergeAlgorithm.Rexture
    val bracketed = tuning.mergeAlgorithm == MultiframeMergeAlgorithm.HdrPlusBracketed
    SettingsPageContainer {
        SettingsGroup(
            description = "Multiframe still pipeline. Off leaves the existing single-frame capture path untouched."
        ) {
            SettingsSwitchRow(
                title = "Multiframe",
                checked = enabled
            ) { dispatch.invoke(SetExperimentalMultiframeEnabled(it)) }
        }
        if (lensId != null) {
            SettingsGroup(
                title = "Lens",
                description = "Off: this lens uses the shared settings below, like every lens without its own. " +
                    "On: the algorithm and sliders below apply to $lensId only (starting from the shared values). " +
                    "Switch lenses on the capture screen to set up another one."
            ) {
                SettingsSwitchRow(
                    title = "Own settings for $lensId",
                    checked = ownTuning,
                    enabled = enabled
                ) { dispatch.invoke(SetMultiframeLensOverride(it)) }
            }
        }
        SettingsGroup {
            SettingsSwitchRow(
                title = "Save Base DNG",
                checked = state.values.saveBaseDng,
                enabled = enabled && state.values.dngEnabled
            ) { dispatch.invoke(SetSaveBaseDng(it)) }
        }
        SettingsGroup(
            title = "Merge Algorithm",
            description = "Every merge uses the sharpest frame of the last quarter second before the shutter as the base. " +
                "HDR+ Quality: per-frequency merge, cleaner with mild sharpening; a frame is left out of the tiles where it moved, so moving subjects stay sharp. " +
                (if (MultiframeMergeAlgorithm.HdrPlusBracketed.available) "HDR+ Bracketed: HDR+ Quality on a burst metered darker to keep bright areas (sky, lamps) from clipping, brightened back, plus a few longer frames right after the shutter for cleaner shadows; hold still a moment longer. " else "") +
                "Rexture: kernel-regression merge of the whole burst at native resolution, aligned and weighted per pixel. Equal-exposure bursts. Up to about 56 MP it merges the whole frame; above that it merges in overlapping strips and the JPEG is developed at half size. " +
                "(Native resolution only.)"
        ) {
            MultiframeMergeAlgorithm.entries.filter { it.available }.forEachIndexed { index, candidate ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = candidate.label,
                    selected = tuning.mergeAlgorithm == candidate,
                    enabled = enabled
                ) { dispatch.invoke(SetMultiframeMergeAlgorithm(candidate)) }
            }
        }
        SettingsGroup(
            title = "Output",
            description = "Double-tap any parameter label to restore its default."
        ) {
            // Needs the burst noise fit, which both merges run; JPEG only: the merged DNG is written before rendering.
            SettingsSwitchRow(
                title = "Chroma Denoise",
                checked = state.values.multiframeChromaDenoise,
                supportingText =
                    "JPEG only. Removes leftover colour blotches from the merged image (chroma only, luma untouched) using the noise measured from the burst. Separate from Image > Denoise, which applies to single frames.",
                enabled = enabled
            ) { dispatch.invoke(SetMultiframeChromaDenoise(it)) }
            SettingDivider()
            if (rexture) {
                SettingsSwitchRow(
                    title = "Rexture Native RGB",
                    checked = state.values.multiframeRextureNativeRgb,
                    supportingText = "JPEG only. Renders the JPEG from Rexture's own merged colour instead of re-mosaicking it and running the Demosaic setting. The DNG is unaffected.",
                    enabled = enabled
                ) { dispatch.invoke(SetMultiframeRextureNativeRgb(it)) }
                SettingDivider()
                SettingsSwitchRow(
                    title = "Rexturation",
                    checked = state.values.multiframeRextureDetailRestore,
                    supportingText = "Recovers fine detail the merge softens. Slightly more grain.",
                    enabled = enabled,
                    testTag = SettingsTestTags.row("Multiframe", "rexturation")
                ) { dispatch.invoke(SetMultiframeRextureDetailRestore(it)) }
            }
            // HDR+ Bracketed sets its own frame counts in its group below.
            if (!bracketed) {
                SettingDivider()
                MultiframeSlider(spec(MultiframeNumericParameter.MaxFrames), tuning, enabled, dispatch)
                if (lensFrameLimit in 1 until tuning.maxFrames) {
                    androidx.compose.material3.Text(
                        if (lensFrameLimit == 1) "Limited to 1 frame by this lens profile (single photo)."
                        else "Limited to $lensFrameLimit frames by this lens profile.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = SettingsRowHorizontalPadding, vertical = SettingsSliderVerticalPadding)
                    )
                }
            }
        }
        if (bracketed) {
            SettingsGroup(
                title = "HDR+ Bracketed",
                description = "Exposures are relative to auto exposure (AE). The ZSL burst from before the shutter goes darker to protect highlights; the frames after it are brighter to clean up shadows."
            ) {
                MultiframeSlider(spec(MultiframeNumericParameter.BracketEv), tuning, enabled, dispatch)
                if (state.values.rawrAe.enabled) {
                    androidx.compose.material3.Text(
                        "Rawr AE: the headroom comes from Rawr AE itself, up to this setting. It only underexposes when the " +
                            "highlights need it, and Highlight-weighted metering adds none (it already places them at white). " +
                            "Spot and the other metering modes work as usual.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = SettingsRowHorizontalPadding, vertical = SettingsSliderVerticalPadding)
                    )
                }
                SettingDivider()
                MultiframeSlider(
                    spec(MultiframeNumericParameter.LongFrameEv), tuning, enabled && tuning.bracketFrames > 0, dispatch
                )
                SettingDivider()
                MultiframeSlider(
                    spec(MultiframeNumericParameter.UnderEv), tuning, enabled && tuning.underShutterFrames() > 0, dispatch
                )
                SettingDivider()
                MultiframeSlider(spec(MultiframeNumericParameter.MaxFrames), tuning, enabled, dispatch)
                SettingDivider()
                // Its range follows Maximum Frames (a 2-frame burst stays before the press).
                val afterMax = tuning.maxPostShutterFrames()
                MultiframeSlider(
                    spec(MultiframeNumericParameter.UnderFrames), tuning, enabled && afterMax > 0, dispatch,
                    maximum = afterMax.coerceIn(1, 2).toFloat(), value = tuning.underShutterFrames().toFloat()
                )
                SettingDivider()
                MultiframeSlider(
                    spec(MultiframeNumericParameter.BracketFrames), tuning, enabled && afterMax > 0, dispatch,
                    maximum = (afterMax - tuning.underShutterFrames()).coerceAtLeast(1).toFloat(),
                    value = tuning.postShutterFrames().toFloat()
                )
                androidx.compose.material3.Text(
                    "Uses ${tuning.zslFrames()} frames from before the shutter + ${tuning.allPostShutterFrames()} after (${tuning.underShutterFrames()} darker, ${tuning.postShutterFrames()} brighter). Hold still until the tick.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = SettingsRowHorizontalPadding, vertical = SettingsSliderVerticalPadding)
                )
            }
        }
    }
}

@Composable
private fun MultiframeSlider(
    spec: MultiframeNumericSpec,
    tuning: MultiframeTuning,
    enabled: Boolean,
    dispatch: SettingsDispatch,
    maximum: Float = spec.maximum,
    value: Float = tuning.value(spec.parameter)
) {
    StandaloneNumericSliderRow(
        identity = "mf_${spec.parameter.name}",
        label = spec.label,
        supportingText = spec.supportingText,
        minimum = spec.minimum,
        maximum = maximum,
        step = spec.step,
        decimals = spec.decimals,
        value = value.coerceIn(spec.minimum, maximum),
        enabled = enabled,
        defaultValue = spec.defaultValue,
        unit = spec.unit,
        valueFormatter =
            if (spec.parameter == MultiframeNumericParameter.LongFrameEv) {
                { value -> if (value <= 0f) "Auto exposure" else String.format(java.util.Locale.US, "AE +%.1fEV", value) }
            } else if (spec.parameter == MultiframeNumericParameter.BracketEv) {
                { value -> String.format(java.util.Locale.US, "up to AE %.1fEV", value) }
            } else {
                null
            }
    ) { dispatch.invoke(SetMultiframeNumericValue(spec.parameter, it)) }
}
