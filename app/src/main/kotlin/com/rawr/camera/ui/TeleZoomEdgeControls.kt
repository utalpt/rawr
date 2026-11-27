package com.rawr.camera.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.SetTeleZoomMm
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.TeleOpticalZoom
import com.rawr.camera.model.TeleZoomStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/*
 * Rawr 17U fork: the TELE control on the viewfinder's right edge, vertical - Wheel, Dial and Rocker, as the Telezoom
 * module draws them (utalpt/x17u-telezoom, Bar.java: Wheel, Arc, Rocker), redrawn in Compose. The longer focal
 * lengths are above; the scale follows the finger. Detents every 5 mm.
 */

private val MIN = TeleOpticalZoom.MIN_MM.toFloat()
private val MAX = TeleOpticalZoom.MAX_MM.toFloat()
private val Panel = Color.Black.copy(alpha = .55f)
private val Dim = Color(0xFFD8F0FA).copy(alpha = .6f)

/** The chosen edge control, when the style is not the bar. */
@Composable
internal fun TeleEdgeControl(state: CaptureUiState, dispatch: CaptureDispatch, modifier: Modifier = Modifier) {
    val mm = state.teleZoomMm.coerceIn(TeleOpticalZoom.MIN_MM, TeleOpticalZoom.MAX_MM)
    val onSelect: (Int) -> Unit = { dispatch(SetTeleZoomMm(it)) }
    when (TeleZoomSheetState.style) {
        TeleZoomStyle.WHEEL -> TeleWheel(mm, onSelect, modifier)
        TeleZoomStyle.DIAL -> TeleDial(mm, onSelect, modifier)
        TeleZoomStyle.ROCKER -> TeleRocker(mm, onSelect, modifier)
        TeleZoomStyle.BAR -> Unit
    }
}

/** The shared position (fractional mm under the index) and selection, with a detent every 5 mm. */
private class FocalScale(val pos: Animatable<Float, AnimationVector1D>, private val select: (Int) -> Unit) {
    var dragging = false
    fun pick(v: Float) = select(v.coerceIn(MIN, MAX).roundToInt())
}

@Composable
private fun rememberFocalScale(mm: Int, onSelect: (Int) -> Unit): FocalScale {
    val haptics = LocalCaptureHaptics.current
    val latestMm by rememberUpdatedState(mm)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val scale = remember {
        val pos = Animatable(mm.toFloat()).apply { updateBounds(MIN, MAX) }
        FocalScale(pos) { v ->
            if (v != latestMm) {
                latestOnSelect(v)
                if (v % 5 == 0) haptics.detent()
            }
        }
    }
    LaunchedEffect(mm) { if (!scale.dragging && !scale.pos.isRunning) scale.pos.snapTo(mm.toFloat()) }
    return scale
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, style: TextStyle, center: Offset) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/**
 * A drag with fling: [delta] turns a finger move into mm (down / along the ring = longer focal lengths come to the
 * index). A flick keeps it turning a little (the speed in mm/s, smoothed as in Telezoom), then it settles on the
 * nearest mm.
 */
private fun Modifier.focalDrag(scale: FocalScale, scope: kotlinx.coroutines.CoroutineScope,
                               delta: (at: Offset, move: Offset) -> Float): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            scale.dragging = true
            scope.launch { scale.pos.stop() }
            var p = scale.pos.value
            var vel = 0f
            var lastT = down.uptimeMillis
            var lastP = p
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val d = delta(change.position, change.positionChange())
                if (d != 0f) {
                    change.consume()
                    p = (p + d).coerceIn(MIN, MAX)
                    scope.launch { scale.pos.snapTo(p) }
                    scale.pick(p)
                    val t = change.uptimeMillis
                    if (t > lastT) {
                        vel = .6f * vel + .4f * (p - lastP) * 1000f / (t - lastT)
                        lastP = p
                        lastT = t
                    }
                }
            }
            // Held still before letting go: no fling.
            val mmPerSec = if (currentEvent.changes.firstOrNull()?.uptimeMillis?.let { it - lastT > 80 } == true) 0f
                else vel.coerceIn(-40f, 40f)
            scope.launch {
                if (abs(mmPerSec) > 6f) {
                    scale.pos.animateDecay(mmPerSec, exponentialDecay(frictionMultiplier = 3f)) { scale.pick(value) }
                }
                val settle = scale.pos.value.roundToInt().toFloat()
                scale.pick(settle)
                scale.pos.animateTo(settle, spring(stiffness = 700f))
                scale.dragging = false
            }
        }
    }

/** Wheel: a lens focal ring seen from the side, the scale turning behind a fixed red index (Telezoom's Wheel). */
@Composable
private fun TeleWheel(mm: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val scale = rememberFocalScale(mm, onSelect)
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val accent = CaptureColors.Accent
    val numberStyle = TextStyle(color = Dim, fontSize = 9.sp, fontFamily = CaptureMono, fontWeight = FontWeight.Bold)
    val valueStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = CaptureMono, fontWeight = FontWeight.Bold)
    val span = 7
    val step = (PI / 2 * .92 / span).toFloat()
    val dragPx = with(LocalDensity.current) { 7.dp.toPx() }   // about 7 dp per mm, as in Telezoom
    Canvas(
        modifier.size(64.dp, 230.dp)
            .testTag("tele_wheel")
            .semantics { contentDescription = "Tele focal length, $mm millimetres. Drag the wheel." }
            .focalDrag(scale, scope) { _, move -> move.y / dragPx }
    ) {
        val w = size.width
        val cy = size.height / 2f
        val r = size.height / 2f - 4.dp.toPx()
        drawRoundRect(Panel, cornerRadius = CornerRadius(14.dp.toPx()))
        val pos = scale.pos.value
        for (v in TeleOpticalZoom.MIN_MM..TeleOpticalZoom.MAX_MM) {
            val th = (v - pos) * step
            if (abs(th) >= PI / 2) continue
            val y = cy - r * sin(th)
            val k = cos(th)
            val preset = v in TeleOpticalZoom.presets
            val mid = v % 5 == 0
            val len = w * (if (preset) .42f else if (mid) .3f else .18f)
            val color = if (preset) accent else Dim
            drawLine(color.copy(alpha = color.alpha * k * k), Offset(w - 4.dp.toPx() - len, y), Offset(w - 4.dp.toPx(), y),
                strokeWidth = (if (preset) 1.6f else 1f) * (.5f + k / 2) * density, cap = StrokeCap.Round)
            if ((preset || mid) && abs(v - pos) > .6f) {
                label(measurer, "$v", numberStyle.copy(color = color.copy(alpha = color.alpha * k * k),
                    fontSize = numberStyle.fontSize * (.6f + .4f * k)), Offset(w * .27f, y))
            }
        }
        drawLine(accent, Offset(w * .5f, cy), Offset(w, cy), strokeWidth = 2.dp.toPx())
        drawCircle(accent, radius = 2.2.dp.toPx(), center = Offset(w * .5f - 3.dp.toPx(), cy))
        label(measurer, "${pos.roundToInt()}", valueStyle, Offset(w * .25f, cy))
    }
}

/** Dial: half a ring on the screen edge turning behind a fixed red index, the value in its middle (Telezoom's Arc). */
@Composable
private fun TeleDial(mm: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val scale = rememberFocalScale(mm, onSelect)
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val accent = CaptureColors.Accent
    val numberStyle = TextStyle(color = Dim, fontSize = 10.sp)
    val valueStyle = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val unitStyle = TextStyle(color = Dim, fontSize = 9.sp)
    val stepRad = (7.0 * PI / 180).toFloat()
    // The dial's centre, set where it is drawn (on the right edge), read by the drag.
    val center = remember { arrayOf(Offset.Zero) }
    Canvas(
        modifier.size(107.dp, 210.dp)
            .testTag("tele_dial")
            .semantics { contentDescription = "Tele focal length, $mm millimetres. Turn the dial." }
            // Along the ring: the angle around the dial's centre, 7 degrees per mm.
            .focalDrag(scale, scope) { at, move ->
                val c = center[0]
                val before = at - move
                val a0 = atan2(before.y - c.y, before.x - c.x)
                val a1 = atan2(at.y - c.y, at.x - c.x)
                var da = a1 - a0
                if (da > PI) da -= (2 * PI).toFloat()
                if (da < -PI) da += (2 * PI).toFloat()
                -da / stepRad
            }
    ) {
        center[0] = Offset(size.width - 1.dp.toPx(), size.height / 2f)
        val cx = center[0].x
        val cy = center[0].y
        val big = size.height / 2f - 2.dp.toPx()
        val disc = Path().apply {
            for (i in 0..60) {
                val phi = PI / 2 + PI * i / 60
                val x = cx + big * cos(phi).toFloat()
                val y = cy + big * sin(phi).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(disc, Color(0xE60A0E20))
        drawPath(disc, Color.White.copy(alpha = .2f), style = Stroke(width = 1.dp.toPx()))
        val pos = scale.pos.value
        for (v in TeleOpticalZoom.MIN_MM..TeleOpticalZoom.MAX_MM) {
            val phi = PI + (v - pos) * stepRad
            if (phi <= PI / 2 + .08 || phi >= 3 * PI / 2 - .08) continue
            val k = abs(cos(phi)).toFloat()
            val a = .35f + .65f * k
            val major = v % 5 == 0 || v in TeleOpticalZoom.presets
            val co = cos(phi).toFloat()
            val si = sin(phi).toFloat()
            val r1 = big - 5.dp.toPx()
            val r2 = r1 - big * (if (major) .13f else .07f)
            drawLine((if (major) Color.White else Dim).copy(alpha = a), Offset(cx + r1 * co, cy + r1 * si),
                Offset(cx + r2 * co, cy + r2 * si), strokeWidth = (if (major) 1.9f else 1.2f) * density, cap = StrokeCap.Round)
            if (v % 5 == 0 && abs(v - pos) > 1.2f) {
                val rt = big * .7f
                label(measurer, "$v", numberStyle.copy(color = Dim.copy(alpha = Dim.alpha * a)), Offset(cx + rt * co, cy + rt * si))
            }
        }
        drawLine(accent, Offset(cx - big + 5.dp.toPx(), cy), Offset(cx - big + 5.dp.toPx() + big * .13f, cy),
            strokeWidth = 2.6.dp.toPx())
        val mx = cx - big * .42f
        val shown = pos.roundToInt()
        label(measurer, "$shown", valueStyle, Offset(mx, cy))
        label(measurer, "mm · ${TeleOpticalZoom.zoomLabel(shown)}", unitStyle, Offset(mx, cy + 20.dp.toPx()))
    }
}

/**
 * Rocker: the zoom rocker of a video camera. Hold the thumb away from the middle - the further, the faster it glides
 * (up to about 12 mm/s, finer near the middle); let go and it springs back and stops. T (tele) on top, W below
 * (Telezoom's Rocker).
 */
@Composable
private fun TeleRocker(mm: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val scale = rememberFocalScale(mm, onSelect)
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val accent = CaptureColors.Accent
    val letterStyle = TextStyle(color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val thumbStyle = TextStyle(color = Color(0xFF0A0E20), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    var off by remember { mutableFloatStateOf(0f) }
    var half by remember { mutableFloatStateOf(1f) }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        var last = withFrameNanos { it }
        var p = scale.pos.value
        while (holding) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1e9f
            last = now
            var k = off / half
            if (abs(k) < .12f) k = 0f
            val rate = -k * abs(k) * 12f   // mm/s, towards T (up) = longer
            if (rate != 0f) {
                p = (p + rate * dt).coerceIn(MIN, MAX)
                scale.pos.snapTo(p)
                scale.pick(p)
            }
        }
    }
    Canvas(
        modifier.size(48.dp, 190.dp)
            .testTag("tele_rocker")
            .semantics { contentDescription = "Tele focal length, $mm millimetres. Hold towards T or W." }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    half = size.height / 2f - size.width / 2f
                    scale.dragging = true
                    off = (down.position.y - size.height / 2f).coerceIn(-half, half)
                    holding = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        off = (change.position.y - size.height / 2f).coerceIn(-half, half)
                    }
                    holding = false
                    off = 0f
                    scope.launch {
                        val settle = scale.pos.value.roundToInt().toFloat()
                        scale.pick(settle)
                        scale.pos.animateTo(settle, spring(stiffness = 700f))
                        scale.dragging = false
                    }
                }
            }
    ) {
        val w = size.width
        val cx = w / 2f
        val cy = size.height / 2f
        val r = w / 2f - 2.dp.toPx()
        drawRoundRect(Panel, cornerRadius = CornerRadius(r))
        drawRoundRect(accent.copy(alpha = .7f), topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
            size = Size(w - 4.dp.toPx(), size.height - 4.dp.toPx()), cornerRadius = CornerRadius(r),
            style = Stroke(width = 1.5.dp.toPx()))
        drawLine(Dim, Offset(cx - r * .5f, cy), Offset(cx + r * .5f, cy), strokeWidth = 1.dp.toPx())
        label(measurer, "T", letterStyle, Offset(cx, r + 4.dp.toPx()))
        label(measurer, "W", letterStyle, Offset(cx, size.height - r - 4.dp.toPx()))
        drawCircle(accent, radius = r * .78f, center = Offset(cx, cy + off))
        val k = off / half
        label(measurer, if (abs(k) < .12f) "·" else if (off < 0) "+" else "−", thumbStyle, Offset(cx, cy + off))
    }
}
