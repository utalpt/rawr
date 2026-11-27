package com.rawr.camera.settings.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.rawr.camera.settings.architecture.*
import com.rawr.camera.settings.model.*

@Composable
internal fun CaptureSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val context = LocalContext.current
    fun locationPermitted() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var locationGranted by remember(context) { mutableStateOf(locationPermitted()) }
    LifecycleResumeEffect(context) {
        locationGranted = locationPermitted()
        onPauseOrDispose { }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        locationGranted = granted.values.any { it }
        if (locationGranted) dispatch.invoke(SetLocationTagging(true))
    }
    val lenses = state.values.effectiveLensProfiles(LocalLensHardware.current.deviceDefaults)
    val enabledLenses = lenses.filter { it.enabled }
    SettingsPageContainer {
        SettingsGroup {
            SettingsRow(
                title = "Lens",
                value = enabledLenses.joinToString(" · ") { it.name }.ifEmpty { null },
                testTag = SettingsTestTags.row("Capture", "lens"),
                onClick = { dispatch.invoke(OpenSection(SettingsSection.Lens)) }
            )
            SettingDivider()
            ToggleSubmenuRow(
                title = "Multiframe",
                checked = state.values.experimentalMultiframeEnabled,
                value =
                    if (state.values.experimentalMultiframeEnabled) {
                        state.values.activeMultiframeTuning.mergeAlgorithm.label
                    } else {
                        "Off"
                    },
                testTag = SettingsTestTags.row("Capture", "multiframe"),
                onOpen = { dispatch.invoke(OpenSection(SettingsSection.Multiframe)) },
                onCheckedChange = { dispatch.invoke(SetExperimentalMultiframeEnabled(it)) }
            )
            SettingDivider()
            SettingsRow(
                title = "Auto Exposure",
                value = if (state.values.rawrAe.enabled) "Rawr" else "Device",
                testTag = SettingsTestTags.row("Capture", "exposure"),
                onClick = { dispatch.invoke(OpenSection(SettingsSection.Exposure)) }
            )
            SettingDivider()
            SettingsSwitchRow(
                title = "OIS",
                checked = state.values.oisEnabledPreference,
                enabled = state.capabilities.oisSupported
            ) { dispatch.invoke(SetOisPreference(it)) }
            SettingDivider()
            // Rawr 17U fork: the system's camera sounds (asked for by @iG0Lka_ru in the Telegram group).
            // The system's word on the sound, shown so it is clear why the click can play with the switch off.
            val systemAsksForSound = androidx.compose.runtime.remember { com.rawr.camera.integration.CameraSounds.requiredBySystem() }
            SettingsSwitchRow(
                title = "Shutter Sound",
                checked = state.values.shutterSound,
                supportingText = when {
                    !systemAsksForSound -> "This phone's system does not ask for the shutter sound: this switch decides."
                    state.values.shutterSoundFollowSystem ->
                        "This phone's system asks for the shutter sound, so it plays even with this off. Turn off Follow System Shutter Rule to let this switch decide."
                    else -> "This phone's system asks for the shutter sound, but Follow System Shutter Rule is off: this switch decides."
                },
                testTag = SettingsTestTags.row("Capture", "shutter_sound")
            ) { dispatch.invoke(SetShutterSound(it)) }
            if (systemAsksForSound) {
                SettingDivider()
                SettingsSwitchRow(
                    title = "Follow System Shutter Rule",
                    checked = state.values.shutterSoundFollowSystem,
                    supportingText = "On: where the system asks for it (some regions require the sound), the click plays even when Shutter Sound is off. Off: Shutter Sound alone decides.",
                    testTag = SettingsTestTags.row("Capture", "shutter_sound_follow_system")
                ) { dispatch.invoke(SetShutterSoundFollowSystem(it)) }
            }
            SettingDivider()
            // Rawr 17U fork: take a photo by voice (offline recognizer; the viewfinder does the listening).
            SettingsSwitchRow(
                title = "Voice Shutter",
                checked = state.values.voiceShutter,
                supportingText = "Say \"Cheese\" or \"Capture\" to take a photo. It listens through the microphone only while the " +
                    "viewfinder is open in Photo mode (Android shows its microphone indicator), and the speech model runs on this " +
                    "phone: nothing is recorded or sent. English only. The first start unpacks a 68 MB speech model.",
                testTag = SettingsTestTags.row("Capture", "voice_shutter")
            ) { dispatch.invoke(SetVoiceShutter(it)) }
            SettingDivider()
            // Rawr 17U fork: the Wear OS companion (the watch app is installed separately).
            SettingsSwitchRow(
                title = "Watch Remote",
                checked = state.values.watchRemote,
                supportingText = "Lets the Rawr Remote app on your paired Wear OS watch show this viewfinder and take photos. " +
                    "Open Rawr here first, in Photo mode, then open Rawr Remote on the watch. It streams over Bluetooth only " +
                    "while both are open.",
                testTag = SettingsTestTags.row("Capture", "watch_remote")
            ) { dispatch.invoke(SetWatchRemote(it)) }
            SettingDivider()
            // Rawr 17U fork: the tele optical zoom control's look (bar above the exposure row, or a vertical
            // wheel / dial / rocker on the viewfinder's edge, ported from the Telezoom module).
            val teleStyle = com.rawr.camera.model.TeleZoomStyle.fromId(state.values.teleZoomStyle)
            SettingsRow(
                title = "Tele Zoom Control",
                value = teleStyle.label,
                testTag = SettingsTestTags.row("Capture", "tele_zoom_style"),
                onClick = { dispatch.invoke(SetTeleZoomStyle(teleStyle.next().id)) }
            )
            SettingDivider()
            // Rawr 17U fork: the LOFIC camera (both LOFIC exposures of the main camera as RAW, merged into a float
            // DNG), from Anton Tretiakov's X17U LOFIC RAW specification; needs his root module. Xiaomi 17 Ultra only.
            if (android.os.Build.DEVICE == "nezha") {
                SettingsRow(
                    title = "LOFIC Camera",
                    value = "Needs the X17U LOFIC RAW module",
                    testTag = SettingsTestTags.row("Capture", "lofic_camera"),
                    onClick = {
                        context.startActivity(android.content.Intent(context, com.rawr.camera.lofic.LoficActivity::class.java))
                    }
                )
                SettingDivider()
                // The "1x LOFIC" lens in the lens bar (viewfinder and photos from the LOFIC Long/Short pair).
                SettingsSwitchRow(
                    title = "LOFIC Lens (1x LOFIC)",
                    checked = com.rawr.camera.lofic.LoficLensSwitch.enabled,
                    testTag = SettingsTestTags.row("Capture", "lofic_lens")
                ) { com.rawr.camera.lofic.LoficLensSwitch.set(context, it) }
                SettingDivider()
            }
            SettingsSwitchRow(
                title = "Tele Zoom Always On",
                checked = state.values.teleZoomAlwaysOn,
                testTag = SettingsTestTags.row("Capture", "tele_zoom_always_on")
            ) { dispatch.invoke(SetTeleZoomAlwaysOn(it)) }
            SettingDivider()
            // Rawr 17U fork (asked for by iG0Lka): one tap between ISO priority and shutter priority, each with its value.
            val priorityStyle = com.rawr.camera.model.PriorityToggleStyle.fromId(state.values.priorityToggleStyle)
            val presets = com.rawr.camera.model.PriorityTogglePresets
            SettingsRow(
                title = "S/I Toggle",
                value = priorityStyle.label,
                supportingText = "One tap switches between ISO priority (static scenes) and shutter priority (motion), each " +
                    "with the value below. Top bar: a button next to the timer (long press: back to Auto). Exposure cell: " +
                    "an S or I tab on the fixed cell. Photo mode only.",
                testTag = SettingsTestTags.row("Capture", "priority_toggle_style"),
                onClick = { dispatch.invoke(SetPriorityToggleStyle(priorityStyle.next().id)) }
            )
            if (priorityStyle != com.rawr.camera.model.PriorityToggleStyle.OFF) {
                SettingDivider()
                SettingsRow(
                    title = "ISO Priority Value",
                    value = presets.isoLabel(state.values.priorityToggleIso),
                    testTag = SettingsTestTags.row("Capture", "priority_toggle_iso"),
                    onClick = { dispatch.invoke(SetPriorityToggleIso(presets.nextIso(state.values.priorityToggleIso))) }
                )
                SettingDivider()
                SettingsRow(
                    title = "Shutter Priority Value",
                    value = presets.shutterLabel(state.values.priorityToggleShutterNs),
                    testTag = SettingsTestTags.row("Capture", "priority_toggle_shutter"),
                    onClick = {
                        dispatch.invoke(SetPriorityToggleShutter(presets.nextShutter(state.values.priorityToggleShutterNs)))
                    }
                )
            }
            SettingDivider()
            SettingsSwitchRow(
                title = "Location Tagging",
                checked = state.values.locationTagging && locationGranted,
                testTag = SettingsTestTags.row("Capture", "location_tagging")
            ) { enabled ->
                locationGranted = locationPermitted()
                if (!enabled || locationGranted) {
                    dispatch.invoke(SetLocationTagging(enabled))
                } else {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
        }
    }
}

@Composable
internal fun OisSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    SettingsPageContainer {
        SettingsGroup {
            SettingsSwitchRow(
                title = "OIS",
                checked = state.values.oisEnabledPreference
            ) { dispatch.invoke(SetOisPreference(it)) }
        }
    }
}

@Composable
internal fun ExposureSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    // Optimistic until probed so a supporting camera never flashes disabled.
    val hardware = LocalLensHardware.current
    val prioritySupported by produceState(true, hardware) { value = hardware.aePrioritySupported() }
    val boostLimited = state.values.maxPostGainId != "gain.100"
    SettingsPageContainer {
        val ae = state.values.rawrAe
        val setAe: (RawrAeSettings) -> Unit = { dispatch.invoke(SetRawrAeSettings(it)) }
        SettingsGroup(
            title = "Auto Exposure",
            description =
                if (ae.enabled) {
                    "Rawr picks shutter and ISO in Auto, shutter priority and ISO priority, for photo and video."
                } else {
                    "Auto uses the camera's own exposure."
                }
        ) {
            SegmentedOptionRow(
                options = listOf("Device AE", "Rawr AE"),
                selected = if (ae.enabled) 1 else 0,
                onSelect = { setAe(ae.copy(enabled = it == 1)) }
            )
            if (ae.enabled) {
                RawrAeChoice(
                    "Metering", RawrMetering.entries, ae.metering, { it.label },
                    "Long-press the viewfinder to meter a spot. Matrix also leans toward the focus area",
                    {
                        when (it) {
                            RawrMetering.Matrix ->
                                "Whole frame, centre counts more. Darkens a little to save highlights; lets windows and very bright skies clip"
                            RawrMetering.CenterWeighted ->
                                "Average weighted to the centre. Bright areas pull it darker; no highlight protection"
                            RawrMetering.Average ->
                                "Whole-frame average. Bright areas pull it darker; no highlight protection"
                            RawrMetering.Highlight -> "Keeps the brightest areas just below white; the rest may go dark"
                        }
                    }
                ) { setAe(ae.copy(metering = it)) }
                RawrAeChoice(
                    "Night Look", RawrNightLook.entries, ae.nightLook, { it.label }, null,
                    {
                        when (it) {
                            RawrNightLook.Natural -> "Dim scenes stay dark, like they look"
                            RawrNightLook.Bright -> "Dim scenes only slightly darker than day"
                            RawrNightLook.Off -> "Every scene at day brightness"
                        }
                    }
                ) { setAe(ae.copy(nightLook = it)) }
                RawrAeChoice(
                    "Slowest Shutter",
                    RawrMaxShutter.entries,
                    ae.maxShutter,
                    { it.label },
                    "In dim light the shutter never goes slower than this; ISO makes up the rest. " +
                        "Auto is 1/15 s for photo and the frame time for video"
                ) { setAe(ae.copy(maxShutter = it)) }
                RawrAeChoice(
                    "Shutter / ISO Balance", RawrBalance.entries, ae.balance, { it.label },
                    "Shifts the shutter by whole stops and ISO makes up the difference, so brightness stays the same. " +
                        "No effect in bright light",
                    {
                        when (it) {
                            RawrBalance.LowIso -> "Shutter 1 stop slower, half the ISO: cleaner, more blur"
                            RawrBalance.Balanced -> "Shutter and ISO as the light needs"
                            RawrBalance.Faster -> "Shutter 1 stop faster, double the ISO"
                            RawrBalance.Fastest -> "Shutter 2 stops faster, four times the ISO"
                        }
                    }
                ) { setAe(ae.copy(balance = it)) }
                RawrAeChoice(
                    "Digital Gain",
                    RawrDigitalGain.entries,
                    ae.digitalGain,
                    { it.label },
                    "Brightening past the sensor's highest real ISO, applied after RAW; DNGs carry it as BaselineExposure. " +
                        "Maximum has no limit and gets noisy in the dark"
                ) { setAe(ae.copy(digitalGain = it)) }
                RawrAeChoice("Adaptation Speed", RawrAeSpeed.entries, ae.speed, { it.label }, null) {
                    setAe(ae.copy(speed = it))
                }
            }
            if (!ae.enabled) {
                // The camera's own AE ceiling; rawr AE uses Longest Shutter instead.
                SettingDivider()
                SelectorDropdownRow(state, ChoiceSelectorKind.AutoMinFps, dispatch)
            }
            SettingDivider()
            DropdownChoiceRow(
                title = "Anti-flicker",
                selected = state.values.antiFlicker,
                values = AntiFlicker.entries,
                label = { it.label },
                supportingText = "Keeps shutter speeds in step with mains lighting so it doesn't band. " +
                    "Applies to both auto exposures; no effect in Manual. Auto follows the phone's flicker " +
                    "sensor when it has one."
            ) { dispatch.invoke(SetAntiFlicker(it)) }
        }
        // Device AE only: rawr AE has its own Digital Gain and runs S/I itself.
        if (!ae.enabled) {
            SettingsGroup(
                title = "Brightness Boost",
                description = "Digital gain the camera adds in low light, applied evenly to the whole image."
            ) {
                SelectorDropdownRow(state, ChoiceSelectorKind.MaxPostGain, dispatch)
                if (boostLimited) {
                    SettingDivider()
                    DropdownChoiceRow(
                        title = "Above the Limit",
                        selected = state.values.highlightProtection,
                        values = HighlightProtection.entries,
                        label = { it.label },
                        optionText = { it.kneeSummary }
                    ) { dispatch.invoke(SetHighlightProtection(it)) }
                }
            }
            SettingsGroup(
                description =
                    if (prioritySupported) {
                        "Locking shutter or ISO enters full Manual instead of shutter/ISO priority, " +
                            "even when the camera supports priority modes."
                    } else {
                        "This camera has no shutter/ISO priority; locking shutter or ISO always enters full Manual."
                    }
            ) {
                SettingsSwitchRow(
                    title = "Full Manual Instead of Priority",
                    checked = state.values.aePriorityDisabled || !prioritySupported,
                    enabled = prioritySupported
                ) { dispatch.invoke(SetAePriorityDisabled(it)) }
            }
        }
    }
}

@Composable
internal fun JpegSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val tone = state.values.imageTone
    SettingsPageContainer {
        SettingsGroup {
            NumericSliderRow(ImageToneSpecs.jpegQuality, tone.jpegQuality) {
                dispatch.invoke(SetNumericValue(ImageToneNumericParameter.JpegQuality, it))
            }
            SettingDivider()
            SelectorDropdownRow(state, ChoiceSelectorKind.JpegChromaSubsampling, dispatch)
            SettingDivider()
            SettingsSwitchRow(
                title = "Ultra HDR",
                checked = state.values.ultraHdrEnabled,
                onCheckedChange = { dispatch.invoke(SetUltraHdrEnabled(it)) }
            )
        }
    }
}

@Composable
internal fun DngSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    SettingsPageContainer {
        SettingsGroup(
            description = "Lossless writes compressed DNGs at roughly half the size. Uncompressed keeps the legacy byte layout."
        ) {
            SelectorDropdownRow(state, ChoiceSelectorKind.DngCompression, dispatch, title = "Compression")
        }
    }
}

@Composable
private fun <T> RawrAeChoice(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    summary: String?,
    optionText: ((T) -> String?)? = null,
    onSelected: (T) -> Unit
) {
    SettingDivider()
    DropdownChoiceRow(
        title = title,
        selected = selected,
        values = values,
        label = label,
        supportingText = summary,
        optionText = optionText,
        onSelect = onSelected
    )
}
