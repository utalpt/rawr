package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.AspectRatio
import com.rawr.camera.ui.icons.outlined.Hd
import com.rawr.camera.ui.icons.outlined.MovieFilter
import com.rawr.camera.ui.icons.outlined.HighQuality
import com.rawr.camera.ui.icons.outlined.Settings
import com.rawr.camera.ui.icons.outlined.SlowMotionVideo
import com.rawr.camera.ui.icons.outlined.Thermostat
import com.rawr.camera.ui.icons.outlined.Timer
import com.rawr.camera.ui.icons.outlined.TimerOff
import com.rawr.camera.ui.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CancelSelfTimer
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CycleOutputFormat
import com.rawr.camera.architecture.CycleSelfTimer
import com.rawr.camera.architecture.CycleVideoFps
import com.rawr.camera.architecture.CycleVideoResolution
import com.rawr.camera.architecture.ToggleMonitorPanel
import com.rawr.camera.architecture.ToggleWhiteBalancePanel
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.WhiteBalanceMode
import com.rawr.camera.settings.model.SelfTimer
import com.rawr.camera.video.VideoResolutionMode

@Composable
internal fun CaptureTopBar(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    onOpenSettings: () -> Unit,
    videoLocked: Boolean = false,
    /** Some viewfinder control group is hidden: the eye stays lit so it is easy to bring them back. */
    vfControlsHidden: Boolean = false
) {
    val haptics = LocalCaptureHaptics.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(CaptureColors.Surface)
            .height(CaptureDimens.TopBarHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.captureMode == CaptureMode.Video) {
            TopBarSlot {
                VideoTopBarChoice(
                    icon = state.videoResolution.topBarIcon(),
                    label = state.videoResolution.topBarLabel(),
                    testTag = CaptureTestTags.TOPBAR_VIDEO_RESOLUTION,
                    description = "Video resolution ${state.videoResolution.label}.",
                    enabled = !videoLocked && (state.videoModeAvailability
                        ?.resolutions(state.videoFps, state.videoAspect)?.size ?: 0) > 1,
                    orientation = state.orientation,
                    rotateLabel = false,
                    onClick = {
                        haptics.selection()
                        dispatch(CycleVideoResolution)
                    }
                )
            }
            TopBarSlot {
                VideoAspectTopBarChoice(state = state, dispatch = dispatch, enabled = !videoLocked)
            }
            TopBarSlot {
                VideoTopBarChoice(
                    icon = Icons.Outlined.SlowMotionVideo,
                    label = "${state.videoFps}",
                    testTag = CaptureTestTags.TOPBAR_VIDEO_FPS,
                    description = "Video frame rate ${state.videoFps} fps.",
                    enabled = !videoLocked && (state.videoModeAvailability
                        ?.rates(state.videoResolution, state.videoAspect)?.size ?: 0) > 1,
                    orientation = state.orientation,
                    onClick = {
                        haptics.selection()
                        dispatch(CycleVideoFps)
                    }
                )
            }
            TopBarSlot {
                TextButton(
                    onClick = { haptics.selection(); dispatch(com.rawr.camera.architecture.ToggleVideoLog) },
                    enabled = !videoLocked,
                    modifier = Modifier.size(48.dp).testTag(CaptureTestTags.TOPBAR_VIDEO_LOG)
                        .semantics {
                            contentDescription = "LOG recording"
                            role = Role.Switch
                            toggleableState = ToggleableState(state.videoLogEnabled)
                        },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("LOG", fontFamily = CaptureMono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (state.videoLogEnabled) CaptureColors.Accent else CaptureColors.Muted,
                        modifier = Modifier.uprightInLandscape(state.orientation))
                }
            }
            TopBarSlot {
                SelfTimerTopBarChoice(
                    state = state,
                    dispatch = dispatch,
                    orientation = state.orientation
                )
            }
        } else {
            TopBarSlot {
                // A DNG-only (200 MP) lens pins the button to DNG; the saved choice returns on other lenses.
                val dngOnly = state.capabilities.dngOnly
                val format = com.rawr.camera.model.CaptureOutputFormat.from(state.dngEnabled, state.jpegEnabled, dngOnly)
                TextButton(
                    onClick = {
                        haptics.selection()
                        dispatch(CycleOutputFormat)
                    },
                    enabled = !dngOnly,
                    modifier = Modifier.size(48.dp).testTag(CaptureTestTags.TOPBAR_JPEG)
                        .semantics {
                            contentDescription = if (dngOnly) "Output format: DNG only on this lens"
                            else "Output format: ${format.label}. Tap to cycle formats"
                        },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        when (format) {
                            com.rawr.camera.model.CaptureOutputFormat.Dng -> "DNG"
                            com.rawr.camera.model.CaptureOutputFormat.DngAndJpeg -> "DNG +\nJPEG"
                            com.rawr.camera.model.CaptureOutputFormat.Jpeg -> "JPEG"
                        },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 13.sp,
                        color = CaptureColors.Accent,
                        fontFamily = CaptureMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.uprightInLandscape(state.orientation)
                    )
                }
            }
            TopBarSlot {
                PhotoAspectTopBarChoice(state = state, dispatch = dispatch)
            }
            TopBarSlot {
                SelfTimerTopBarChoice(
                    state = state,
                    dispatch = dispatch,
                    orientation = state.orientation
                )
            }
        }
        TopBarSlot {
            IconToggleButton(
                checked =
                    state.whiteBalancePanelOpen ||
                        state.whiteBalanceMode != WhiteBalanceMode.Auto,
                onCheckedChange = {
                    haptics.selection()
                    dispatch(ToggleWhiteBalancePanel)
                },
                modifier = Modifier.testTag(CaptureTestTags.TOPBAR_WHITE_BALANCE)
                    .semantics { contentDescription = "White balance" },
                colors =
                    IconButtonDefaults.iconToggleButtonColors(
                        contentColor = CaptureColors.Muted,
                        checkedContentColor = CaptureColors.Accent
                    )
            ) {
                Icon(
                    Icons.Outlined.Thermostat,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).uprightInLandscape(state.orientation)
                )
            }
        }
        TopBarSlot {
            IconToggleButton(
                checked = state.monitorPanelOpen || state.armedOverlays.isNotEmpty() ||
                    state.falseColorManual || state.activeScopes.isNotEmpty() || vfControlsHidden,
                onCheckedChange = {
                    haptics.selection()
                    dispatch(ToggleMonitorPanel)
                },
                modifier = Modifier.testTag(CaptureTestTags.TOPBAR_MONITOR)
                    .semantics { contentDescription = "Monitoring and overlays" },
                colors =
                    IconButtonDefaults.iconToggleButtonColors(
                        contentColor = CaptureColors.Muted,
                        checkedContentColor = CaptureColors.Accent
                    )
            ) {
                Icon(
                    Icons.Outlined.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).uprightInLandscape(state.orientation)
                )
            }
        }
        TopBarSlot {
            IconButton(
                enabled = !videoLocked,
                onClick = {
                    haptics.selection()
                    onOpenSettings()
                },
                modifier = Modifier.testTag(CaptureTestTags.TOPBAR_SETTINGS)
                    .semantics { contentDescription = "Open Settings" },
                colors = IconButtonDefaults.iconButtonColors(contentColor = CaptureColors.Muted.copy(alpha = .82f))
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp).uprightInLandscape(state.orientation)
                )
            }
        }
    }
}

private fun VideoResolutionMode.topBarIcon(): ImageVector = when (this) {
    VideoResolutionMode.HD1080 -> Icons.Outlined.Hd
    VideoResolutionMode.UHD4K -> Icons.Outlined.HighQuality
    VideoResolutionMode.OPEN_GATE -> Icons.Outlined.AspectRatio
}

private fun VideoResolutionMode.topBarLabel(): String = when (this) {
    VideoResolutionMode.HD1080 -> "1080p"
    VideoResolutionMode.UHD4K -> "4K"
    VideoResolutionMode.OPEN_GATE -> "OPEN"
}

internal data class AspectChoice(val id: String, val label: String, val shortLabel: String, val group: String)

@Composable
private fun VideoAspectTopBarChoice(state: CaptureUiState, dispatch: CaptureDispatch, enabled: Boolean) {
    val options = com.rawr.camera.video.VideoAspect.entries
    AspectTopBarChoice(
        state = state,
        enabled = enabled,
        icon = Icons.Outlined.MovieFilter,
        kind = "Video",
        testPrefix = "topbar_video_aspect",
        current = state.videoAspect.let { AspectChoice(it.id, it.label, it.shortLabel, it.group) },
        options = options.map { AspectChoice(it.id, it.label, it.shortLabel, it.group) },
        groups = com.rawr.camera.video.VideoAspect.groups
    ) { id -> options.firstOrNull { it.id == id }?.let { dispatch(com.rawr.camera.architecture.SetVideoAspect(it)) } }
}

/** Photo aspect ratio: crops the JPEG only (the DNG stays full); the viewfinder shows it. */
@Composable
private fun PhotoAspectTopBarChoice(state: CaptureUiState, dispatch: CaptureDispatch) {
    val options = com.rawr.camera.model.PhotoAspect.entries
    AspectTopBarChoice(
        state = state,
        enabled = true,
        icon = Icons.Outlined.AspectRatio,
        kind = "Photo",
        testPrefix = "topbar_photo_aspect",
        current = state.photoAspect.let { AspectChoice(it.id, it.label, it.shortLabel, it.group) },
        options = options.map { AspectChoice(it.id, it.label, it.shortLabel, it.group) },
        groups = com.rawr.camera.model.PhotoAspect.groups
    ) { id -> options.firstOrNull { it.id == id }?.let { dispatch(com.rawr.camera.architecture.SetPhotoAspect(it)) } }
}

/**
 * Aspect ratio picker: tap for the formats, grouped. Portrait opens a dropdown;
 * landscape a panel turned like the other landscape controls.
 */
@Composable
private fun AspectTopBarChoice(
    state: CaptureUiState,
    enabled: Boolean,
    icon: ImageVector,
    kind: String,
    testPrefix: String,
    current: AspectChoice,
    options: List<AspectChoice>,
    groups: List<String>,
    onSelect: (String) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val aspect = current
    Box {
        VideoTopBarChoice(
            icon = icon,
            label = aspect.shortLabel,
            testTag = testPrefix,
            description = "$kind aspect ratio ${aspect.label}. Tap to choose.",
            enabled = enabled,
            orientation = state.orientation,
            // "2.39"/"XPan" are wider than the slot: turn the label's layout box, not just its pixels.
            labelTurnsLayout = true,
            onClick = {
                haptics.selection()
                open = true
            }
        )
        if (state.orientation == com.rawr.camera.model.Orientation.Landscape) {
            // The Activity stays portrait: a dropdown would read sideways. The app's own panel, turned
            // like the rest of the landscape controls, opens beside the bar instead.
            if (open && enabled) {
                val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 52.dp.roundToPx() }
                androidx.compose.ui.window.Popup(
                    alignment = Alignment.TopStart,
                    offset = androidx.compose.ui.unit.IntOffset(0, gap),
                    onDismissRequest = { open = false },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = true)
                ) {
                    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    // Turned, the panel's height runs along the screen's width: cap it there and scroll,
                    // with the groups in two columns so it rarely needs to.
                    val maxAlong = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 24.dp
                                        Row(
                        Modifier
                            .uprightPanelInLandscape(state.orientation)
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .85f), shape)
                            .border(1.dp, CaptureColors.Outline, shape)
                            .heightIn(max = maxAlong)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(vertical = 4.dp)
                    ) {
                        listOf(groups.take(3), groups.drop(3)).forEach { columnGroups ->
                            Column(Modifier.width(IntrinsicSize.Max)) {
                                columnGroups.forEach { group ->
                                    val items = options.filter { it.group == group }
                                    if (items.isEmpty()) return@forEach
                                    Text(group, color = CaptureColors.Muted, fontSize = 10.sp,
                                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp))
                                    items.forEach { option ->
                                        val selected = option.id == aspect.id
                                        Text(
                                            option.label,
                                            color = if (selected) CaptureColors.Accent else androidx.compose.ui.graphics.Color.White,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    haptics.selection()
                                                    open = false
                                                    onSelect(option.id)
                                                }
                                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                                .testTag("${testPrefix}_${option.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            groups.forEach { group ->
                val items = options.filter { it.group == group }
                if (items.isEmpty()) return@forEach
                Text(
                    group,
                    color = CaptureColors.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                items.forEach { option ->
                    val selected = option.id == aspect.id
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.label,
                                color = if (selected) CaptureColors.Accent else androidx.compose.ui.graphics.Color.Unspecified,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            haptics.selection()
                            open = false
                            onSelect(option.id)
                        },
                        modifier = Modifier.testTag("${testPrefix}_${option.id}")
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoTopBarChoice(
    icon: ImageVector,
    label: String,
    testTag: String,
    description: String,
    enabled: Boolean,
    orientation: com.rawr.camera.model.Orientation,
    rotateLabel: Boolean = true,
    /** Turn the label's measured box too (uprightPanelInLandscape), so a longer label is not clipped. */
    labelTurnsLayout: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).testTag(testTag)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) CaptureColors.Accent else CaptureColors.Muted,
                modifier = Modifier.size(20.dp).uprightInLandscape(orientation)
            )
            Text(
                label,
                color = if (enabled) CaptureColors.Accent else CaptureColors.Muted,
                fontFamily = CaptureMono,
                fontSize = 8.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = when {
                    !rotateLabel -> Modifier
                    labelTurnsLayout -> Modifier.wrapContentWidth(unbounded = true).uprightPanelInLandscape(orientation)
                    else -> Modifier.uprightInLandscape(orientation)
                }
            )
        }
    }
}

/**
 * Self-timer slot, immediately left of white balance in both modes. Cycles
 * Off → 3s → 5s → 10s; while a countdown runs it shows the remaining whole
 * seconds and a tap cancels instead of cycling.
 */
@Composable
private fun SelfTimerTopBarChoice(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    orientation: com.rawr.camera.model.Orientation
) {
    val haptics = LocalCaptureHaptics.current
    val remaining = state.selfTimerRemainingMs
    val counting = remaining != null
    val label = if (counting) {
        "${((remaining + 999) / 1000).toInt().coerceAtLeast(1)}s"
    } else {
        state.selfTimer.shortLabel
    }
    val armed = counting || state.selfTimer != SelfTimer.Off
    val description = if (counting) {
        "Self timer counting down $label. Tap to cancel."
    } else {
        "Self timer ${state.selfTimer.label}. Tap to change."
    }
    TextButton(
        onClick = {
            haptics.selection()
            dispatch(if (counting) CancelSelfTimer else CycleSelfTimer)
        },
        modifier = Modifier.size(48.dp).testTag(CaptureTestTags.TOPBAR_SELF_TIMER)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (armed) Icons.Outlined.Timer else Icons.Outlined.TimerOff,
                contentDescription = null,
                tint = if (armed) CaptureColors.Accent else CaptureColors.Muted,
                modifier = Modifier.size(20.dp).uprightInLandscape(orientation)
            )
            Text(
                label,
                color = if (armed) CaptureColors.Accent else CaptureColors.Muted,
                fontFamily = CaptureMono,
                fontSize = 8.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.uprightInLandscape(orientation)
            )
        }
    }
}

@Composable
private fun RowScope.TopBarSlot(content: @Composable () -> Unit) {    Box(
        Modifier.weight(1f).fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
