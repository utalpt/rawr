package com.rawr.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.SetTeleZoomMm
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.TeleOpticalZoom
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Tune

internal const val COMPACT_TELE_ZOOM = "compact_tele_zoom"

/** Rawr 17U fork: the selected lens moves the tele's optics (the 4.3x lenses of the 17 Ultra). */
internal fun CaptureUiState.teleZoomAvailable(): Boolean =
    TeleOpticalZoom.supports(capabilities.lenses.firstOrNull { it.id == selectedLensId })

/**
 * Rawr 17U fork: the tele's optical zoom, 75 to 100 mm: a tap opens the TELE sheet above the row (TeleZoomSheet, in
 * the WB strip's style), and the cell scrubs too. Double tap: 100 mm.
 * The optics move per request (TeleOpticalZoom), with no new camera session.
 */
@Composable
internal fun CompactTeleZoomButton(state: CaptureUiState, dispatch: CaptureDispatch, modifier: Modifier = Modifier) {
    val haptics = LocalCaptureHaptics.current
    val steps = TeleOpticalZoom.steps
    val mm = state.teleZoomMm.coerceIn(TeleOpticalZoom.MIN_MM, TeleOpticalZoom.MAX_MM)
    val index = steps.indexOf(mm)
    val latestIndex by rememberUpdatedState(index)
    var dragging by remember { mutableStateOf(false) }
    val labels = steps.map { it.toString() }
    val majors = steps.indices.filter { steps[it] in TeleOpticalZoom.presets }.toSet()
    fun select(i: Int) {
        steps.getOrNull(i)?.let { dispatch(SetTeleZoomMm(it)) }
    }
    fun openBar() {
        // Rawr 17U fork: the TELE sheet (WB strip style) instead of the slider bar.
        TeleZoomSheetState.open = !TeleZoomSheetState.open
        if (TeleZoomSheetState.open) {
            // One sheet above the exposure row at a time.
            SliderBar.close()
            LookSheetState.open = false
            if (state.whiteBalancePanelOpen) dispatch(com.rawr.camera.architecture.ToggleWhiteBalancePanel)
        }
    }
    val valueText = TeleOpticalZoom.label(mm)
    CompactParamShell(
        icon = Icons.Outlined.Tune,
        title = "TELE",
        valueText = valueText,
        locked = mm != TeleOpticalZoom.MAX_MM || TeleZoomSheetState.open,
        testTag = COMPACT_TELE_ZOOM,
        semanticsDescription = "Tele optical zoom $valueText. Tap for the zoom sheet, scrub to adjust, " +
            "double tap for ${TeleOpticalZoom.MAX_MM} mm.",
        scaleLabels = labels,
        scaleIndex = index,
        modifier = modifier
            .onGloballyPositioned { TeleZoomSheetState.cellBounds = it.boundsInRoot() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (!dragging) {
                            haptics.selection()
                            openBar()
                        }
                    },
                    onDoubleTap = {
                        if (!dragging) {
                            haptics.selection()
                            dispatch(SetTeleZoomMm(TeleOpticalZoom.MAX_MM))
                        }
                    }
                )
            }
            .pointerInput(state.orientation) {
                // The cell: 1 mm per 5 dp, so 75 to 100 mm fits in one stroke.
                val stepPx = 5.dp.toPx()
                var current = 0
                var residual = 0f
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        current = latestIndex
                        residual = 0f
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        // Same axes as EV: right/up = more zoom.
                        residual += drag.x - drag.y
                        val moved = (residual / stepPx).toInt()
                        if (moved == 0) return@detectDragGestures
                        residual -= moved * stepPx
                        val next = (current + moved).coerceIn(0, steps.lastIndex)
                        if (next == current) {
                            residual = 0f
                            return@detectDragGestures
                        }
                        val crossedPreset = (minOf(current, next) + 1..maxOf(current, next)).any { it in majors }
                        current = next
                        select(next)
                        if (crossedPreset) haptics.detent()
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                )
            }
    )
}

/** Rawr 17U fork: the TELE sheet above the exposure row, open or not (one sheet there at a time, like LookSheetState). */
internal object TeleZoomSheetState {
    var open by androidx.compose.runtime.mutableStateOf(false)

    /** The control's look (Settings > Capture > Tele Zoom Control). */
    var style by androidx.compose.runtime.mutableStateOf(com.rawr.camera.model.TeleZoomStyle.BAR)

    /** The TELE cell in root coordinates: a tap there while the WB strip (modal, with its scrim) is open opens this. */
    var cellBounds: androidx.compose.ui.geometry.Rect? = null
}

/**
 * Rawr 17U fork: the tele's optical zoom in the white balance strip's style (WhiteBalanceSheet): a row of chips
 * (✕ and the 75 / 85 / 90 / 100 mm presets) and a FOCAL tape (FocalRail).
 */
@Composable
internal fun TeleZoomSheet(state: CaptureUiState, dispatch: CaptureDispatch, modifier: Modifier = Modifier) {
    val mm = state.teleZoomMm.coerceIn(TeleOpticalZoom.MIN_MM, TeleOpticalZoom.MAX_MM)
    Column(
        modifier
            .background(Color.Black.copy(alpha = .66f), RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }  // taps inside never reach the viewfinder
            .testTag("tele_zoom_sheet")
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // ✕ at the start, like the WB strip; the presets centred over the FOCAL rail, like its mark.
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.CenterStart)) {
                SheetTabChip("✕", active = false, tag = "tele_sheet_close") { TeleZoomSheetState.open = false }
            }
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TeleOpticalZoom.presets.sorted().forEach { preset ->
                    SheetTabChip("${preset}mm", active = mm == preset, tag = "tele_sheet_$preset") {
                        dispatch(SetTeleZoomMm(preset))
                    }
                }
            }
        }
        FocalRail(
            mm = mm,
            onSelect = { dispatch(SetTeleZoomMm(it)) },
            modifier = Modifier.fillMaxWidth().height(CaptureDimens.ExposureControlHeight)
        )
    }
}

/**
 * Rawr 17U fork: the FOCAL tape, at the WB rails' height and with their ticks. A scale from 75 to 100 mm (a tick per
 * mm, long and numbered every 5 mm) slides under a fixed red mark in the middle: it follows the finger smoothly (drag
 * left for more zoom, like a lens ring) and settles on the nearest mm when you let go. The focal length and zoom sit
 * above the mark. A tap left or right of the mark steps 1 mm. Detents every 5 mm.
 */
@Composable
private fun FocalRail(mm: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalCaptureHaptics.current
    val latestMm by rememberUpdatedState(mm)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The scale position under the mark, in mm (fractional while dragging or settling).
    val pos = remember { androidx.compose.animation.core.Animatable(mm.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(mm) { if (!dragging && !pos.isRunning) pos.snapTo(mm.toFloat()) }
    val measurer = rememberTextMeasurer()
    val accent = CaptureColors.Accent
    val numberStyle = TextStyle(color = Color.White.copy(alpha = .5f), fontSize = 8.sp, fontFamily = CaptureMono)
    val valueStyle = TextStyle(color = Color.White.copy(alpha = .98f), fontSize = 10.sp, fontFamily = CaptureMono,
        fontWeight = FontWeight.SemiBold)
    val shape = RoundedCornerShape(CaptureDimens.ExposureCornerRadius)
    val min = TeleOpticalZoom.MIN_MM.toFloat()
    val max = TeleOpticalZoom.MAX_MM.toFloat()
    // 75 to 100 mm spans about 60 % of the rail: a short swipe goes end to end.
    fun spacing(width: Float) = width * .6f / (max - min)
    fun select(v: Int) {
        if (v != latestMm) {
            latestOnSelect(v)
            if (v % 5 == 0) haptics.detent()
        }
    }
    Box(
        modifier
            .clip(shape)
            .captureControlSurface(shape = shape, textureStrength = .9f)
            .testTag("tele_sheet_focal")
            .pointerInput(Unit) {
                detectTapGestures(onTap = { at ->
                    val step = if (at.x > size.width / 2f) 1 else -1
                    val next = (latestMm + step).coerceIn(TeleOpticalZoom.MIN_MM, TeleOpticalZoom.MAX_MM)
                    select(next)
                    scope.launch { pos.animateTo(next.toFloat()) }
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        scope.launch { pos.stop(); pos.snapTo(latestMm.toFloat()) }
                    },
                    onDragEnd = {
                        dragging = false
                        val settle = pos.value.roundToInt().coerceIn(TeleOpticalZoom.MIN_MM, TeleOpticalZoom.MAX_MM)
                        select(settle)
                        scope.launch { pos.animateTo(settle.toFloat(), androidx.compose.animation.core.spring(stiffness = 700f)) }
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        val next = (pos.value - amount / spacing(size.width.toFloat())).coerceIn(min, max)
                        scope.launch { pos.snapTo(next) }
                        select(next.roundToInt())
                    }
                )
            }
            .semantics { contentDescription = "Tele focal length, $mm millimetres. Drag the scale to adjust." }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val stepPx = spacing(size.width)
            val tickY = size.height * .58f
            val shown = pos.value
            for (v in TeleOpticalZoom.MIN_MM..TeleOpticalZoom.MAX_MM) {
                val x = cx + (v - shown) * stepPx
                if (x < -4f || x > size.width + 4f) continue
                val major = v % 5 == 0
                val h = (if (major) CaptureDimens.ExposureMajorTickHeight else CaptureDimens.ExposureMinorTickHeight).toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = if (major) .72f else .38f),
                    topLeft = Offset(x - .45.dp.toPx(), tickY - h / 2f),
                    size = Size(.9.dp.toPx(), h),
                    cornerRadius = CornerRadius(.45.dp.toPx())
                )
                if (major && kotlin.math.abs(x - cx) > 10.dp.toPx()) {
                    val n = measurer.measure("$v", numberStyle)
                    drawText(n, topLeft = Offset(x - n.size.width / 2f, tickY + h / 2f + 1.dp.toPx()))
                }
            }
            val markerH = 16.dp.toPx()
            drawRoundRect(accent, topLeft = Offset(cx - 1.dp.toPx(), tickY - markerH / 2f), size = Size(2.dp.toPx(), markerH),
                cornerRadius = CornerRadius(1.dp.toPx()))
            val shownMm = shown.roundToInt()
            val value = measurer.measure("${TeleOpticalZoom.label(shownMm)} · ${TeleOpticalZoom.zoomLabel(shownMm)}", valueStyle)
            drawText(value, topLeft = Offset(cx - value.size.width / 2f, 1.dp.toPx()))
        }
    }
}
