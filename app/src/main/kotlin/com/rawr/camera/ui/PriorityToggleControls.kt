package com.rawr.camera.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.TogglePriority
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.ExposureMode
import com.rawr.camera.model.ExposureParameter
import com.rawr.camera.model.PriorityToggleStyle
import com.rawr.camera.model.PriorityTogglePresets

/** Rawr 17U fork: the S/I priority switch's settings (Settings > Photo > Capture > S/I Toggle), for the capture UI. */
internal object PriorityToggleState {
    var style by mutableStateOf(PriorityToggleStyle.OFF)
    var isoValue by mutableStateOf(PriorityTogglePresets.DEFAULT_ISO)
    var shutterNs by mutableStateOf(PriorityTogglePresets.DEFAULT_SHUTTER_NS)
}

/** The switch can run here: Photo mode and both priority modes available on this lens. */
internal fun CaptureUiState.priorityToggleAvailable(): Boolean =
    captureMode == CaptureMode.Photo &&
        ExposureMode.ShutterPriority in capabilities.supportedExposureModes &&
        ExposureMode.IsoPriority in capabilities.supportedExposureModes

/** Top bar: "S" or "I" with the side's value (A when neither). Tap switches S <-> I, long press goes back to Auto. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PriorityToggleTopBarButton(state: CaptureUiState, dispatch: CaptureDispatch) {
    val haptics = LocalCaptureHaptics.current
    val mode = state.exposureControl.mode
    val active = mode == ExposureMode.ShutterPriority || mode == ExposureMode.IsoPriority
    val (letter, value) = when (mode) {
        ExposureMode.ShutterPriority -> "S" to PriorityTogglePresets.shutterLabel(PriorityToggleState.shutterNs)
        ExposureMode.IsoPriority -> "I" to PriorityTogglePresets.isoLabel(PriorityToggleState.isoValue)
        else -> "S⇄I" to "AUTO"
    }
    val color = if (active) CaptureColors.Accent else CaptureColors.Muted
    Box(
        Modifier
            .size(48.dp)
            .combinedClickable(
                onClick = {
                    haptics.selection()
                    dispatch(TogglePriority())
                },
                onLongClick = {
                    haptics.longPress()
                    dispatch(TogglePriority(toAuto = true))
                }
            )
            .testTag(CaptureTestTags.TOPBAR_PRIORITY_TOGGLE)
            .semantics {
                contentDescription = "Priority switch, now $letter $value. Tap to switch between shutter and ISO " +
                    "priority, long press for Auto."
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.uprightInLandscape(state.orientation)
        ) {
            Text(letter, color = color, fontFamily = CaptureMono, fontSize = 14.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text(value, color = color, fontFamily = CaptureMono, fontSize = 8.sp, lineHeight = 9.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * Exposure cell: an "S" (shutter cell, in S) or "I" (ISO cell, in I) tab on the cell's top-left corner, or "S⇄I" on the
 * shutter cell in Auto or Manual. Tap switches to the other side (from Auto or Manual: to S); the rest of the cell keeps its
 * own gestures (tap: slider, double tap: auto, long press: lock).
 */
@Composable
internal fun PriorityToggleBadge(
    parameter: ExposureParameter,
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    modifier: Modifier = Modifier
) {
    if (PriorityToggleState.style != PriorityToggleStyle.EXPOSURE_CELL || !state.priorityToggleAvailable()) return
    val mode = state.exposureControl.mode
    // In S or I the tab sits on the fixed cell; in Auto or Manual an "S⇄I" tab on the shutter cell starts it (to S).
    val letter = when {
        parameter == ExposureParameter.Shutter && mode == ExposureMode.ShutterPriority -> "S"
        parameter == ExposureParameter.Iso && mode == ExposureMode.IsoPriority -> "I"
        parameter == ExposureParameter.Shutter && (mode == ExposureMode.Auto || mode == ExposureMode.Manual) -> "S⇄I"
        else -> return
    }
    val haptics = LocalCaptureHaptics.current
    // A touch area of at least 26 dp in the cell's top-left corner ("S⇄I" on one line, so it may be wider) (inside the cell, so nothing clips it); the rest of the cell keeps
    // its own gestures.
    Box(
        modifier
            .offset(x = 1.dp, y = 1.dp)
            .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
            .combinedClickableTap {
                haptics.selection()
                dispatch(TogglePriority())
            }
            .testTag(CaptureTestTags.PRIORITY_TOGGLE_BADGE)
            .semantics {
                contentDescription = when (letter) {
                    "S" -> "Shutter priority. Tap for ISO priority."
                    "I" -> "ISO priority. Tap for shutter priority."
                    else -> "Tap for shutter priority."
                }
            },
        contentAlignment = Alignment.TopStart
    ) {
        Box(
            Modifier
                .background(if (letter.length == 1) CaptureColors.Accent else Color.Black, RoundedCornerShape(5.dp))
                .border(1.dp, Color.Black.copy(alpha = .6f), RoundedCornerShape(5.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
                .uprightInLandscape(state.orientation)
        ) {
            Text(letter, color = if (letter.length == 1) Color.White else CaptureColors.Accent, fontFamily = CaptureMono,
                fontSize = if (letter.length == 1) 11.sp else 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableTap(onTap: () -> Unit): Modifier = combinedClickable(onClick = onTap)
