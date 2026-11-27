package com.rawr.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** What the horizontal slider bar edits: the value labels and what picking one, or AUTO, does. */
internal class SliderBarSpec(
    val tag: String,
    val title: String,
    val labels: List<String>,
    val autoLabel: String?,
    val onAuto: (() -> Unit)?,
    val onSelect: (Int) -> Unit,
    /** Rawr 17U fork: the indices that get a long tick and a label (the tele zoom's 75/85/90/100 mm); null = every
     *  Nth by the number of values. */
    val majors: Set<Int>? = null,
    /** Rawr 17U fork: finger travel per value (the tele zoom's 26 values use less, so 75 to 100 mm is one swipe). */
    val spacingDp: Float = 14f
)

/**
 * The bar a single tap on ISO / SS / EV / WB / focus opens above the exposure row (Settings › Control Style ›
 * "Tap opens slider bar"). One bar at a time; tapping the same control again closes it, and it hides itself a
 * few seconds after the last touch.
 */
internal object SliderBar {
    var spec by mutableStateOf<SliderBarSpec?>(null)
        private set
    var index by mutableIntStateOf(0)
    var touchSeq by mutableIntStateOf(0)
        private set
    var dragging = false

    fun toggle(next: SliderBarSpec, currentIndex: Int) {
        if (spec?.tag == next.tag) {
            close()
        } else {
            spec = next
            index = currentIndex.coerceIn(0, (next.labels.size - 1).coerceAtLeast(0))
            touch()
        }
    }

    /** The control's live value, while its bar is open and not being dragged. */
    fun sync(tag: String, currentIndex: Int) {
        val open = spec ?: return
        if (open.tag == tag && !dragging && currentIndex in open.labels.indices && index != currentIndex) {
            index = currentIndex
        }
    }

    fun touch() {
        touchSeq++
    }

    fun close() {
        spec = null
        dragging = false
    }
}

@Composable
internal fun SliderBarView(modifier: Modifier = Modifier) {
    val spec = SliderBar.spec ?: return
    val haptics = LocalCaptureHaptics.current
    LaunchedEffect(SliderBar.touchSeq, spec.tag) {
        kotlinx.coroutines.delay(4_000)
        if (!SliderBar.dragging && SliderBar.spec?.tag == spec.tag) SliderBar.close()
    }
    val shape = CapturePalette.shape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
            .padding(bottom = CaptureDimens.ControlGap)
            .height(56.dp)
            .background(Color.Black.copy(alpha = .62f), shape)
            .testTag("slider_bar_${spec.tag}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (spec.autoLabel != null && spec.onAuto != null) {
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .width(56.dp)
                    .height(40.dp)
                    .viewfinderChip()
                    .captureClickable(shape = CaptureTextRippleShape) {
                        haptics.selection()
                        SliderBar.touch()
                        spec.onAuto.invoke()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(spec.autoLabel, color = Color.White, fontFamily = CaptureMono, fontWeight = FontWeight.Bold,
                    fontSize = 11.sp, style = ViewfinderTextStyle)
            }
        }
        SliderRuler(spec, Modifier.weight(1f).fillMaxHeight()) { haptics.detent() }
    }
}

@Composable
private fun SliderRuler(spec: SliderBarSpec, modifier: Modifier, onDetent: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spacingPx = with(density) { spec.spacingDp.dp.toPx() }
    var dragPx by remember(spec.tag) { mutableFloatStateOf(0f) }
    val labelEvery = if (spec.labels.size <= 16) 1 else if (spec.labels.size <= 40) 3 else 5
    val accent = CaptureColors.Accent
    val labelStyle = TextStyle(color = Color.White.copy(alpha = .55f), fontSize = 9.sp, fontFamily = CaptureMono)
    val currentStyle = TextStyle(color = accent, fontSize = 13.sp, fontFamily = CaptureMono, fontWeight = FontWeight.Bold)
    BoxWithConstraints(
        modifier.pointerInput(spec.tag) {
            detectHorizontalDragGestures(
                onDragStart = {
                    SliderBar.dragging = true
                    dragPx = 0f
                    SliderBar.touch()
                },
                onDragEnd = {
                    SliderBar.dragging = false
                    dragPx = 0f
                    SliderBar.touch()
                },
                onDragCancel = {
                    SliderBar.dragging = false
                    dragPx = 0f
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dragPx += amount
                    // The scale follows the finger: dragging left brings higher values to the index.
                    while (abs(dragPx) >= spacingPx) {
                        val step = if (dragPx < 0) 1 else -1
                        val next = (SliderBar.index + step).coerceIn(0, spec.labels.lastIndex)
                        dragPx += step * spacingPx
                        if (next != SliderBar.index) {
                            SliderBar.index = next
                            spec.onSelect(next)
                            onDetent()
                        } else {
                            dragPx = 0f
                            break
                        }
                    }
                    SliderBar.touch()
                }
            )
        }
    ) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val cx = size.width / 2f
            val baseY = size.height * .70f
            val index = SliderBar.index
            val range = (size.width / spacingPx / 2f).toInt() + 2
            for (k in -range..range) {
                val i = index + k
                if (i !in spec.labels.indices) continue
                val x = cx + k * spacingPx + dragPx
                if (x < 0f || x > size.width) continue
                val major = spec.majors?.contains(i) ?: (i % labelEvery == 0)
                val tickTop = baseY - if (major) 10.dp.toPx() else 5.dp.toPx()
                drawLine(Color.White.copy(alpha = if (k == 0) 0f else .45f), Offset(x, tickTop), Offset(x, baseY), 1.5f)
                if (major && k != 0 && abs(k) * spacingPx > 26.dp.toPx()) {
                    val layout = measurer.measure(spec.labels[i], labelStyle)
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, baseY + 3.dp.toPx()))
                }
            }
            // Fixed index: the current value above a red tick and triangle.
            drawLine(accent, Offset(cx, baseY - 12.dp.toPx()), Offset(cx, baseY + 2.dp.toPx()), 2.5f)
            val tri = Path().apply {
                moveTo(cx - 5.dp.toPx(), baseY - 18.dp.toPx())
                lineTo(cx + 5.dp.toPx(), baseY - 18.dp.toPx())
                lineTo(cx, baseY - 11.dp.toPx())
                close()
            }
            drawPath(tri, accent)
            if (index in spec.labels.indices) {
                val current = measurer.measure(spec.labels[index], currentStyle)
                drawText(current, topLeft = Offset(cx - current.size.width / 2f, baseY - 20.dp.toPx() - current.size.height))
            }
        }
    }
}
