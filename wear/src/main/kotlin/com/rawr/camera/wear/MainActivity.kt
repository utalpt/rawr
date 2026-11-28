package com.rawr.camera.wear

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.rawr.camera.wear.WatchRemoteProtocol as P
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Rawr Remote: shows Rawr's viewfinder from the paired phone and works the camera with it (the phone side is
 * integration/WatchRemote.kt in :app, Settings > Photo > Capture > Watch Remote): the shutter, the Photo/Video mode and the
 * lens. In Video mode the shutter starts and stops a recording, with a timer.
 *
 * Every press gets an answer from the phone's own state: a white flash and a tick when a capture or recording started, the
 * counter on the button when a photo saved, a red flash, a double buzz and the reason when it did not start or failed. With
 * no live preview the buttons are disabled, so a press can never silently do nothing. Tap the status line to turn the
 * picture by 90 degrees if it ever comes out sideways (remembered).
 */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private val messages by lazy { Wearable.getMessageClient(this) }
    private val nodes by lazy { Wearable.getNodeClient(this) }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val ui = RemoteUi()
    private var phoneNode: String? = null
    private var connectJob: Job? = null
    private var nextShotId = 0
    private val awaitingAnswer = HashMap<Int, Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.rotationOffset = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_ROTATION, 0)
        ui.wifiDirect = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        setContent { RemoteScreen(ui, onShutter = ::shutter, onRotate = ::rotateOffset, onMode = ::toggleMode, onLens = ::nextLens) }
    }

    override fun onResume() {
        super.onResume()
        messages.addListener(this)
        connectJob = lifecycleScope.launch { connectLoop() }
    }

    override fun onPause() {
        connectJob?.cancel()
        messages.removeListener(this)
        phoneNode?.let { node -> messages.sendMessage(node, P.PATH_BYE, ByteArray(0)) }
        super.onPause()
    }

    /** Finds the phone, asks it to stream while no frames arrive, and keeps the status current. */
    private suspend fun connectLoop() {
        while (true) {
            val node = runCatching { nodes.connectedNodes.await() }.getOrNull()
                ?.let { list -> list.firstOrNull { it.isNearby } ?: list.firstOrNull() }
            val now = SystemClock.elapsedRealtime()
            if (node == null) {
                phoneNode = null
                ui.noPhone()
                delay(2000)
                continue
            }
            phoneNode = node.id
            if (now - ui.lastFrameAt > HELLO_AFTER_MS) {
                runCatching { messages.sendMessage(node.id, P.PATH_HELLO, ByteArray(0)).await() }
            }
            ui.tick(now)
            delay(1000)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            P.PATH_FRAME -> {
                val header = P.decodeHeader(event.data) ?: return
                messages.sendMessage(event.sourceNodeId, P.PATH_ACK, P.encodeInt(header.seq))
                val bitmap = BitmapFactory.decodeByteArray(event.data, P.HEADER_BYTES, event.data.size - P.HEADER_BYTES) ?: return
                ui.onFrame(bitmap.asImageBitmap(), header.rotationDegrees, event.data.size, SystemClock.elapsedRealtime())
            }
            P.PATH_STATUS -> P.decodeStatus(event.data)?.let { ui.onStatus(it, SystemClock.elapsedRealtime()) }
            P.PATH_SHOT -> runCatching { JSONObject(String(event.data)) }.getOrNull()?.let {
                onShot(it.optInt("id"), it.optString("result"), it.optString("reason"))
            }
        }
    }

    private fun shutter() {
        val node = phoneNode ?: return
        if (!ui.canShoot) return
        val id = ++nextShotId
        awaitingAnswer[id] = SystemClock.elapsedRealtime()
        ui.pressed()
        lifecycleScope.launch {
            val sent = runCatching { messages.sendMessage(node, P.PATH_SHUTTER, P.encodeInt(id)).await() }.isSuccess
            if (!sent) {
                onShot(id, P.RESULT_NOT_FIRED, "Could not reach the phone")
                return@launch
            }
            delay(ANSWER_TIMEOUT_MS)
            if (awaitingAnswer.containsKey(id)) onShot(id, P.RESULT_NOT_FIRED, "No answer from the phone")
        }
    }

    private fun sendCommand(command: String, value: String) {
        val node = phoneNode ?: return
        lifecycleScope.launch { runCatching { messages.sendMessage(node, P.PATH_COMMAND, P.encodeCommand(command, value)).await() } }
    }

    private fun toggleMode() {
        if (ui.recording || !ui.fresh) return
        vibrate(VibrationEffect.EFFECT_TICK)
        sendCommand(P.CMD_MODE, if (ui.mode == P.MODE_VIDEO) P.MODE_PHOTO else P.MODE_VIDEO)
    }

    /** Cycles through the phone's lenses, in the order of its lens bar. */
    private fun nextLens() {
        val lenses = ui.lenses
        if (ui.recording || !ui.fresh || lenses.size < 2) return
        vibrate(VibrationEffect.EFFECT_TICK)
        val current = lenses.indexOfFirst { it.selected }.coerceAtLeast(0)
        sendCommand(P.CMD_LENS, lenses[(current + 1) % lenses.size].id)
    }

    private fun onShot(id: Int, result: String, reason: String) {
        val now = SystemClock.elapsedRealtime()
        when (result) {
            P.RESULT_FIRED, P.RESULT_REC_STARTED -> {
                awaitingAnswer.remove(id)?.let { ui.lastShutterMs = now - it }
                ui.flash(Color.White, "")
                vibrate(VibrationEffect.EFFECT_TICK)
            }
            P.RESULT_REC_STOPPED -> {
                ui.flash(Color.Transparent, "Saving video…")
                vibrate(VibrationEffect.EFFECT_TICK)
            }
            P.RESULT_SAVED, P.RESULT_DONE -> {
                ui.saved++
                if (ui.mode == P.MODE_VIDEO) ui.flash(Color.Transparent, "Video saved")
            }
            P.RESULT_FAILED -> {
                ui.flash(RED, reason.ifEmpty { "The photo failed" })
                vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
            }
            P.RESULT_NOT_FIRED -> {
                awaitingAnswer.remove(id)
                ui.flash(RED, reason.ifEmpty { "Didn't fire" })
                vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
            }
        }
    }

    private fun vibrate(effect: Int) {
        runCatching { vibrator?.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    private fun rotateOffset() {
        ui.rotationOffset = (ui.rotationOffset + 90) % 360
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_ROTATION, ui.rotationOffset).apply()
    }

    private companion object {
        const val PREFS = "remote"
        const val KEY_ROTATION = "rotationOffset"
        const val HELLO_AFTER_MS = 1500L
        const val ANSWER_TIMEOUT_MS = 4000L
        val RED = Color(0xFFE53935)
    }
}

/** What the screen shows; Compose state so the screen follows it. */
internal class RemoteUi {
    var frame by mutableStateOf<ImageBitmap?>(null)
    var rotation by mutableIntStateOf(0)
    var rotationOffset by mutableIntStateOf(0)
    var ready by mutableStateOf(false)
    var fresh by mutableStateOf(false)
    var reason by mutableStateOf("Connecting to the phone…")
    var mode by mutableStateOf(P.MODE_PHOTO)
    var recording by mutableStateOf(false)
    var recordingSeconds by mutableIntStateOf(0)
    var lenses by mutableStateOf<List<P.Lens>>(emptyList())
    var fps by mutableFloatStateOf(0f)
    var kbPerSecond by mutableFloatStateOf(0f)
    var lastShutterMs by mutableLongStateOf(-1L)
    var saved by mutableIntStateOf(0)
    var flashColor by mutableStateOf(Color.Transparent)
    var flashToken by mutableIntStateOf(0)
    var message by mutableStateOf("")
    var pressToken by mutableIntStateOf(0)
    var wifiDirect = false
    var lastFrameAt = 0L
    private var lastStatusAt = 0L
    private var recordingBase = 0L
    private val recent = ArrayDeque<LongArray>()

    val canShoot: Boolean get() = ready && fresh

    fun onFrame(image: ImageBitmap, rotationDegrees: Int, bytes: Int, now: Long) {
        frame = image
        rotation = rotationDegrees
        lastFrameAt = now
        fresh = true
        ready = true
        reason = ""
        recent.addLast(longArrayOf(now, bytes.toLong()))
        trim(now)
    }

    fun onStatus(status: P.Status, now: Long) {
        lastStatusAt = now
        ready = status.ready
        reason = if (status.ready) "" else status.reason
        mode = status.mode
        recording = status.recording
        if (status.recording) recordingBase = now - status.recordingMs
        if (status.lenses.isNotEmpty()) lenses = status.lenses
        recordingSeconds = if (recording) ((now - recordingBase) / 1000).toInt() else 0
    }

    fun noPhone() {
        ready = false
        fresh = false
        reason = "Phone not connected"
        fps = 0f
        kbPerSecond = 0f
    }

    fun tick(now: Long) {
        trim(now)
        fresh = now - lastFrameAt < FRESH_MS
        if (!fresh && now - lastStatusAt > STATUS_STALE_MS) {
            ready = false
            recording = false
            reason = "Open Rawr on the phone, with Watch Remote on"
        }
        recordingSeconds = if (recording) ((now - recordingBase) / 1000).toInt() else 0
        fps = recent.size / WINDOW_S
        kbPerSecond = recent.sumOf { it[1] } / 1024f / WINDOW_S
    }

    fun pressed() {
        pressToken++
    }

    fun flash(color: Color, text: String) {
        flashColor = color
        message = text
        flashToken++
    }

    private fun trim(now: Long) {
        while (recent.isNotEmpty() && now - recent.first()[0] > WINDOW_S * 1000) recent.removeFirst()
    }

    private companion object {
        const val FRESH_MS = 2000L
        const val STATUS_STALE_MS = 3500L
        const val WINDOW_S = 2f
    }
}

private val RecordRed = Color(0xFFE53935)

@Composable
internal fun RemoteScreen(ui: RemoteUi, onShutter: () -> Unit, onRotate: () -> Unit, onMode: () -> Unit, onLens: () -> Unit) {
    LaunchedEffect(ui.flashToken) {
        if (ui.message.isNotEmpty()) {
            delay(3000)
            ui.message = ""
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val image = ui.frame
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(0.94f).graphicsLayer {
                    rotationZ = ((ui.rotation + ui.rotationOffset) % 360).toFloat()
                    alpha = if (ui.fresh) 1f else 0.35f
                }
            )
        }
        FlashOverlay(ui.flashColor, ui.flashToken)
        val recording = ui.recording && ui.fresh
        val status = when {
            ui.message.isNotEmpty() -> ui.message
            recording -> "● REC %d:%02d".format(ui.recordingSeconds / 60, ui.recordingSeconds % 60)
            !ui.ready || !ui.fresh -> ui.reason.ifEmpty { "Waiting for the phone…" }
            else -> buildString {
                append(String.format("%.0f fps · %.0f KB/s", ui.fps, ui.kbPerSecond))
                if (ui.lastShutterMs >= 0) append(" · ${ui.lastShutterMs} ms")
            }
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 22.dp, start = 34.dp, end = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = status,
                style = TextStyle(
                    color = if (recording && ui.message.isEmpty()) Color(0xFFFF6E6E) else Color.White,
                    fontSize = 11.sp,
                    fontWeight = if (recording) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .background(Color(0x88000000), RoundedCornerShape(8.dp))
                    .clickable(onClick = onRotate)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (!ui.fresh) {
                BasicText(
                    text = "Wi-Fi Direct on this watch: " + if (ui.wifiDirect) "yes" else "no",
                    style = TextStyle(color = Color(0xFF9E9E9E), fontSize = 9.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val idle = ui.fresh && !ui.recording
            ChipButton(
                text = if (ui.mode == P.MODE_VIDEO) "VIDEO" else "PHOTO",
                enabled = idle,
                onClick = onMode,
                modifier = Modifier.offset(y = (-8).dp)
            )
            ShutterButton(
                enabled = ui.canShoot,
                video = ui.mode == P.MODE_VIDEO,
                recording = ui.recording,
                saved = ui.saved,
                pressToken = ui.pressToken,
                onClick = onShutter
            )
            ChipButton(
                text = ui.lenses.firstOrNull { it.selected }?.label ?: "–",
                enabled = idle && ui.lenses.size > 1,
                onClick = onLens,
                modifier = Modifier.offset(y = (-8).dp)
            )
        }
    }
}

@Composable
private fun FlashOverlay(color: Color, token: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(token) {
        if (token > 0) {
            alpha.snapTo(0.85f)
            alpha.animateTo(0f, tween(450))
        }
    }
    if (alpha.value > 0f) Box(Modifier.fillMaxSize().background(color.copy(alpha = alpha.value)))
}

@Composable
private fun ChipButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(if (enabled) 0xCC202020 else 0x88202020))
            .border(1.5.dp, if (enabled) Color.White else Color(0xFF666666), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = if (enabled) Color.White else Color(0xFF888888),
                fontSize = if (text.length > 4) 7.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, video: Boolean, recording: Boolean, saved: Int, pressToken: Int, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressToken) {
        if (pressToken > 0) {
            scale.snapTo(0.82f)
            scale.animateTo(1f, tween(180))
        }
    }
    val ring = if (enabled) Color.White else Color(0xFF666666)
    Box(
        Modifier
            .size(58.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(if (video && enabled && !recording) RecordRed else if (video) Color(0xCC202020) else if (enabled) Color.White else Color(0xFF555555))
            .border(3.dp, ring, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            recording -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(RecordRed))
            !video && saved > 0 -> BasicText("✓$saved", style = TextStyle(color = Color.Black, fontSize = 14.sp))
        }
    }
}
