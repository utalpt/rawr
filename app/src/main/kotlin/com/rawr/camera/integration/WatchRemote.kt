package com.rawr.camera.integration

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.rawr.camera.integration.WatchRemoteProtocol as P
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.model.CaptureSavePhase
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.PendingCapture
import com.rawr.camera.model.TeleOpticalZoom
import com.rawr.camera.ui.PreviewSurfaceRegistry
import com.rawr.camera.ui.splitLensName
import com.rawr.camera.video.RecordingPhase
import com.rawr.camera.video.RecordingState
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Rawr 17U fork: Watch Remote (Settings > Photo > Capture). Streams the viewfinder to the Rawr Remote app on a paired Wear OS
 * watch and works the camera from it: the shutter (a photo, or start and stop of a recording in Video mode), the Photo/Video
 * mode and the lens. The shutter goes through [primaryAction], the very function the on-screen shutter uses.
 *
 * It answers each shutter press from the capture state itself: in Photo mode fired once a new capture shows up in
 * pendingCaptures, then saved or failed when that capture ends; in Video mode rec_started or rec_stopped once the recorder
 * has changed phase, then saved when the file is done; not_fired (with the reason) when nothing happened.
 *
 * MainActivity starts it while the viewfinder is in front with the setting on, and stops it otherwise; the watch learns why
 * from the status messages. Frames are a PixelCopy of the viewfinder's SurfaceView, scaled to [TARGET_LONG_SIDE] px.
 */
internal class WatchRemote(
    context: Context,
    private val scope: CoroutineScope,
    private val captureState: StateFlow<CaptureUiState>,
    private val recordingState: StateFlow<RecordingState>,
    /** null when the camera can be worked now, otherwise what the watch should say. */
    private val readiness: () -> String?,
    /** The device rotation (0/90/180/270); the frame header carries the turn that stands the picture upright (watchTurnDegrees). */
    private val rotationDegrees: () -> Int,
    /** What the on-screen shutter does now: take a photo, or start or stop a recording. Runs on the main thread. */
    private val primaryAction: () -> Unit,
    private val setMode: (CaptureMode) -> Unit,
    private val selectLens: (String) -> Unit
) : MessageClient.OnMessageReceivedListener {
    private val messages = Wearable.getMessageClient(context)
    private var running = false
    private var watchNode: String? = null
    private var streamJob: Job? = null
    private var statusJob: Job? = null
    private var changeJob: Job? = null
    private var sentSeq = 0
    private var ackedSeq = 0
    private var lastAckAt = 0L
    private var lastCopyError: Int? = null
    private var recordingSince: Long? = null
    private var bitmap: Bitmap? = null
    private val copyThread by lazy { HandlerThread("RawrWatchCopy").apply { start() } }
    private val copyHandler by lazy { Handler(copyThread.looper) }

    fun start() {
        if (running) return
        running = true
        messages.addListener(this)
        Log.i(TAG, "listening for the watch")
    }

    fun stop() {
        if (!running) return
        running = false
        messages.removeListener(this)
        streamJob?.cancel()
        statusJob?.cancel()
        changeJob?.cancel()
        watchNode?.let { node ->
            runCatching { messages.sendMessage(node, P.PATH_STATUS, notReady("Open Rawr's viewfinder on the phone")) }
        }
        watchNode = null
        Log.i(TAG, "stopped")
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (!running) return
        when (event.path) {
            P.PATH_HELLO -> {
                val fresh = watchNode != event.sourceNodeId || streamJob?.isActive != true
                watchNode = event.sourceNodeId
                lastAckAt = SystemClock.elapsedRealtime()
                if (fresh) startStreaming()
            }
            P.PATH_BYE -> if (event.sourceNodeId == watchNode) {
                streamJob?.cancel()
                statusJob?.cancel()
                changeJob?.cancel()
                watchNode = null
            }
            P.PATH_ACK -> P.decodeInt(event.data)?.let { seq ->
                if (seq > ackedSeq) ackedSeq = seq
                lastAckAt = SystemClock.elapsedRealtime()
            }
            P.PATH_SHUTTER -> P.decodeInt(event.data)?.let { id -> shutter(event.sourceNodeId, id) }
            P.PATH_COMMAND -> P.decodeCommand(event.data)?.let { (command, value) -> command(command, value) }
        }
    }

    private fun startStreaming() {
        streamJob?.cancel()
        statusJob?.cancel()
        changeJob?.cancel()
        sentSeq = 0
        ackedSeq = 0
        statusJob = scope.launch {
            while (isActive) {
                sendStatus()
                delay(1000)
            }
        }
        // A change of mode, lens or recording reaches the watch at once, not at the next second.
        changeJob = scope.launch {
            combine(
                captureState.map { Triple(it.captureMode, it.selectedLensId, it.teleZoomMm) }.distinctUntilChanged(),
                recordingState.map { it.phase }.distinctUntilChanged()
            ) { _, phase ->
                recordingSince = if (phase == RecordingPhase.Recording) recordingSince ?: SystemClock.elapsedRealtime() else null
            }.collect { sendStatus() }
        }
        streamJob = scope.launch {
            var lastFrameAt = 0L
            while (isActive) {
                val node = watchNode ?: break
                val now = SystemClock.elapsedRealtime()
                if (now - lastAckAt > WATCH_TIMEOUT_MS) {
                    Log.i(TAG, "watch went quiet")
                    watchNode = null
                    break
                }
                if (readiness() != null) {
                    delay(250)
                    continue
                }
                if (sentSeq - ackedSeq >= WINDOW) {
                    delay(8)
                    continue
                }
                val wait = MIN_FRAME_INTERVAL_MS - (now - lastFrameAt)
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                val jpeg = copyPreviewJpeg()
                if (jpeg == null) {
                    delay(200)
                    continue
                }
                lastFrameAt = SystemClock.elapsedRealtime()
                sentSeq++
                val payload = P.encodeFrame(sentSeq, P.watchTurnDegrees(rotationDegrees()), true, jpeg)
                runCatching { messages.sendMessage(node, P.PATH_FRAME, payload).await() }
                    .onFailure { Log.w(TAG, "frame not sent", it) }
            }
        }
    }

    private fun currentReason(): String? =
        readiness() ?: lastCopyError?.let { "Preview copy failed (PixelCopy $it)" }

    private fun sendStatus() {
        val node = watchNode ?: return
        runCatching { messages.sendMessage(node, P.PATH_STATUS, buildStatus()) }
    }

    private fun buildStatus(): ByteArray {
        val state = captureState.value
        val reason = currentReason()
        val recording = recordingState.value.phase == RecordingPhase.Recording
        return P.encodeStatus(
            P.Status(
                ready = reason == null,
                reason = reason ?: "",
                mode = if (state.captureMode == CaptureMode.Video) P.MODE_VIDEO else P.MODE_PHOTO,
                recording = recording,
                recordingMs = recordingSince?.takeIf { recording }?.let { SystemClock.elapsedRealtime() - it } ?: 0L,
                lenses = watchLenses(state)
            )
        )
    }

    private fun notReady(reason: String): ByteArray = P.encodeStatus(
        P.Status(ready = false, reason = reason, mode = P.MODE_PHOTO, recording = false, recordingMs = 0L, lenses = emptyList())
    )

    /** One entry per group of lenses on the same optics, as the phone's lens bar shows them (the selected member of a group). */
    private fun watchLenses(state: CaptureUiState): List<P.Lens> =
        state.capabilities.lenses.groupBy { it.group.ifBlank { "lens:" + it.id } }.values.map { members ->
            val shown = members.firstOrNull { it.id == state.selectedLensId } ?: members.first()
            val (top, bottom) = splitLensName(TeleOpticalZoom.displayName(shown, state.teleZoomMm))
            P.Lens(shown.id, top, bottom, members.any { it.id == state.selectedLensId })
        }

    /** Mode and lens changes come from the watch; neither is changed while a recording is under way. */
    private fun command(command: String, value: String) {
        if (recordingState.value.phase != RecordingPhase.Idle) {
            Log.i(TAG, "command $command ignored while recording")
            return
        }
        when (command) {
            P.CMD_MODE -> scope.launch { setMode(if (value == P.MODE_VIDEO) CaptureMode.Video else CaptureMode.Photo) }
            P.CMD_LENS -> if (captureState.value.capabilities.lenses.any { it.id == value }) scope.launch { selectLens(value) }
        }
    }

    private suspend fun copyPreviewJpeg(): ByteArray? {
        val view = PreviewSurfaceRegistry.current() ?: return null
        if (!view.holder.surface.isValid || view.width <= 0 || view.height <= 0) return null
        val scale = TARGET_LONG_SIDE.toFloat() / max(view.width, view.height)
        val w = max(2, (view.width * scale).roundToInt())
        val h = max(2, (view.height * scale).roundToInt())
        val target = bitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
        val result = suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(view, target, { code -> if (cont.isActive) cont.resume(code) }, copyHandler)
            }.onFailure { if (cont.isActive) cont.resume(PixelCopy.ERROR_UNKNOWN) }
        }
        if (result != PixelCopy.SUCCESS) {
            if (lastCopyError != result) Log.w(TAG, "PixelCopy failed: $result")
            lastCopyError = result
            return null
        }
        lastCopyError = null
        return withContext(Dispatchers.Default) {
            ByteArrayOutputStream(16 * 1024).also { target.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        }
    }

    private fun shutter(node: String, id: Int) {
        val reason = readiness()
        if (reason != null) {
            sendShot(node, id, P.RESULT_NOT_FIRED, reason)
            return
        }
        if (captureState.value.captureMode == CaptureMode.Video) videoShutter(node, id) else photoShutter(node, id)
    }

    private fun photoShutter(node: String, id: Int) {
        val before = captureState.value.pendingCaptures.map { it.id }.toSet()
        scope.launch {
            primaryAction()
            val started = withTimeoutOrNull(FIRE_TIMEOUT_MS) {
                captureState.map { state -> state.pendingCaptures.firstOrNull { it.id !in before } }.filterNotNull().first()
            }
            if (started == null) {
                sendShot(node, id, P.RESULT_NOT_FIRED, "The phone did not start a capture")
                return@launch
            }
            sendShot(node, id, P.RESULT_FIRED, "")
            var last: PendingCapture = started
            val ended = withTimeoutOrNull(OUTCOME_TIMEOUT_MS) {
                captureState.first { state ->
                    val capture = state.pendingCaptures.firstOrNull { it.id == started.id }
                    if (capture != null) last = capture
                    capture == null || capture.terminal || capture.phase.isFailure()
                }
            }
            val result = when {
                last.phase.isFailure() -> P.RESULT_FAILED
                last.phase == CaptureSavePhase.JpegSaved || (!last.jpegEnabled && last.phase == CaptureSavePhase.DngSaved) -> P.RESULT_SAVED
                ended == null -> P.RESULT_FAILED
                else -> P.RESULT_DONE
            }
            sendShot(node, id, result, if (ended == null) "No result from the phone" else "")
        }
    }

    /** Starts or stops a recording like the on-screen shutter (microphone permission included), and says which happened. */
    private fun videoShutter(node: String, id: Int) {
        val before = recordingState.value.phase
        if (before == RecordingPhase.Starting || before == RecordingPhase.Finalizing) {
            sendShot(node, id, P.RESULT_NOT_FIRED, "Rawr is busy with the video")
            return
        }
        val starting = before != RecordingPhase.Recording
        scope.launch {
            primaryAction()
            val changed = withTimeoutOrNull(RECORDER_TIMEOUT_MS) {
                recordingState.first {
                    if (starting) it.phase == RecordingPhase.Starting || it.phase == RecordingPhase.Recording || it.phase == RecordingPhase.Failed
                    else it.phase != RecordingPhase.Recording
                }
            }
            if (changed == null || changed.phase == RecordingPhase.Failed) {
                val why = if (starting) "Rawr did not start recording (microphone permission on the phone?)" else "Rawr did not stop recording"
                sendShot(node, id, P.RESULT_NOT_FIRED, why)
                return@launch
            }
            sendShot(node, id, if (starting) P.RESULT_REC_STARTED else P.RESULT_REC_STOPPED, "")
            if (!starting) {
                val done = withTimeoutOrNull(OUTCOME_TIMEOUT_MS) { recordingState.first { it.phase == RecordingPhase.Idle || it.phase == RecordingPhase.Failed } }
                val ok = done != null && done.phase == RecordingPhase.Idle
                sendShot(node, id, if (ok) P.RESULT_SAVED else P.RESULT_FAILED, if (ok) "" else "The video did not finish")
            }
        }
    }

    private fun CaptureSavePhase.isFailure() = this == CaptureSavePhase.DngFailed || this == CaptureSavePhase.JpegFailed

    private fun sendShot(node: String, id: Int, result: String, reason: String) {
        Log.i(TAG, "shot $id: $result ${reason.ifEmpty { "" }}")
        val json = JSONObject().put("id", id).put("result", result).put("reason", reason).toString().toByteArray()
        scope.launch { runCatching { messages.sendMessage(node, P.PATH_SHOT, json).await() } }
    }

    private companion object {
        const val TAG = "RawrWatchRemote"
        const val TARGET_LONG_SIDE = 320
        const val JPEG_QUALITY = 55
        const val WINDOW = 2
        const val MIN_FRAME_INTERVAL_MS = 66L     // at most about 15 fps
        const val WATCH_TIMEOUT_MS = 4000L
        const val FIRE_TIMEOUT_MS = 1200L
        const val RECORDER_TIMEOUT_MS = 2500L
        const val OUTCOME_TIMEOUT_MS = 60_000L
    }
}
