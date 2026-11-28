package com.rawr.camera.integration

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rawr 17U fork: the phone <-> watch remote, over Wear OS Data Layer messages (Bluetooth when the watch is connected).
 * The watch app keeps an identical copy (wear/src/main/kotlin/com/rawr/camera/wear/WatchRemoteProtocol.kt); both modules
 * test the same bytes, so a change on one side that the other does not follow fails a test.
 *
 * Flow: the watch sends HELLO; the phone streams FRAMEs (a 12-byte header + a small JPEG of the viewfinder) and sends the next
 * one only after the watch ACKs, at most WINDOW ahead, so the rate follows what the link carries. SHUTTER carries a shot id;
 * the phone answers SHOT with that id: fired (a capture started), then saved or failed; or not_fired with a reason. In Video
 * mode the same press starts or stops a recording (rec_started / rec_stopped, then saved or failed once the file is done).
 * STATUS carries the mode, the recording, and the lenses; COMMAND (watch to phone) switches the mode or the lens.
 */
internal object WatchRemoteProtocol {
    const val PATH_HELLO = "/rawr/remote/hello"
    const val PATH_BYE = "/rawr/remote/bye"
    const val PATH_ACK = "/rawr/remote/ack"
    const val PATH_SHUTTER = "/rawr/remote/shutter"
    const val PATH_FRAME = "/rawr/remote/frame"
    const val PATH_STATUS = "/rawr/remote/status"
    const val PATH_SHOT = "/rawr/remote/shot"
    const val PATH_COMMAND = "/rawr/remote/command"

    const val RESULT_FIRED = "fired"
    const val RESULT_SAVED = "saved"
    const val RESULT_DONE = "done"
    const val RESULT_FAILED = "failed"
    const val RESULT_NOT_FIRED = "not_fired"
    const val RESULT_REC_STARTED = "rec_started"
    const val RESULT_REC_STOPPED = "rec_stopped"

    const val MODE_PHOTO = "photo"
    const val MODE_VIDEO = "video"
    const val CMD_MODE = "mode"
    const val CMD_LENS = "lens"

    const val HEADER_BYTES = 12
    private const val VERSION: Byte = 1

    class FrameHeader(val seq: Int, val rotationDegrees: Int, val ready: Boolean)

    /**
     * The turn, clockwise in degrees, that stands the viewfinder picture upright on the watch. The phone's device rotation uses
     * the display-rotation convention its UI is turned by (90 and 270 are the two landscape postures, which turn a UI element
     * by +90 and -90), and the picture has to be turned the opposite way: sending the device rotation itself left the watch
     * upside down in both landscape postures.
     */
    fun watchTurnDegrees(deviceRotationDegrees: Int): Int = (360 - ((deviceRotationDegrees % 360) + 360) % 360) % 360

    /** Header: 'R' 'W', version, flags (bit 0: ready), frame number (int, big-endian), turn clockwise in degrees (short), 0 (short). */
    fun encodeFrame(seq: Int, rotationDegrees: Int, ready: Boolean, jpeg: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + jpeg.size)
        out[0] = 'R'.code.toByte()
        out[1] = 'W'.code.toByte()
        out[2] = VERSION
        out[3] = if (ready) 1 else 0
        putInt(out, 4, seq)
        val rotation = ((rotationDegrees % 360) + 360) % 360
        out[8] = (rotation shr 8).toByte()
        out[9] = rotation.toByte()
        jpeg.copyInto(out, HEADER_BYTES)
        return out
    }

    fun decodeHeader(bytes: ByteArray): FrameHeader? {
        if (bytes.size < HEADER_BYTES || bytes[0] != 'R'.code.toByte() || bytes[1] != 'W'.code.toByte() || bytes[2] != VERSION) return null
        val rotation = ((bytes[8].toInt() and 0xff) shl 8) or (bytes[9].toInt() and 0xff)
        return FrameHeader(getInt(bytes, 4), rotation, (bytes[3].toInt() and 1) != 0)
    }

    /** One entry per group of lenses on the same optics, as the phone's lens bar shows them: [label] on top, [hint] below. */
    class Lens(val id: String, val label: String, val hint: String, val selected: Boolean)

    class Status(
        val ready: Boolean,
        val reason: String,
        val mode: String,
        val recording: Boolean,
        val recordingMs: Long,
        val lenses: List<Lens>
    )

    fun encodeStatus(status: Status): ByteArray = JSONObject()
        .put("ready", status.ready)
        .put("reason", status.reason)
        .put("mode", status.mode)
        .put("rec", status.recording)
        .put("recMs", status.recordingMs)
        .put(
            "lenses",
            JSONArray().also { array ->
                status.lenses.forEach {
                    array.put(JSONObject().put("id", it.id).put("label", it.label).put("hint", it.hint).put("sel", it.selected))
                }
            }
        )
        .toString().toByteArray()

    fun decodeStatus(bytes: ByteArray): Status? = runCatching {
        val json = JSONObject(String(bytes))
        val array = json.optJSONArray("lenses") ?: JSONArray()
        Status(
            ready = json.optBoolean("ready"),
            reason = json.optString("reason"),
            mode = json.optString("mode", MODE_PHOTO),
            recording = json.optBoolean("rec"),
            recordingMs = json.optLong("recMs"),
            lenses = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Lens(item.getString("id"), item.optString("label"), item.optString("hint"), item.optBoolean("sel"))
            }
        )
    }.getOrNull()

    fun encodeCommand(command: String, value: String): ByteArray = JSONObject().put("cmd", command).put("value", value).toString().toByteArray()

    fun decodeCommand(bytes: ByteArray): Pair<String, String>? = runCatching {
        val json = JSONObject(String(bytes))
        json.getString("cmd") to json.getString("value")
    }.getOrNull()

    fun encodeInt(value: Int): ByteArray = ByteArray(4).also { putInt(it, 0, value) }

    fun decodeInt(bytes: ByteArray): Int? = if (bytes.size == 4) getInt(bytes, 0) else null

    private fun putInt(out: ByteArray, at: Int, value: Int) {
        out[at] = (value shr 24).toByte()
        out[at + 1] = (value shr 16).toByte()
        out[at + 2] = (value shr 8).toByte()
        out[at + 3] = value.toByte()
    }

    private fun getInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
}
