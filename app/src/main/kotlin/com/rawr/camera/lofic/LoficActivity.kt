package com.rawr.camera.lofic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.rawr.camera.BuildConfig
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Rawr 17U fork: LOFIC camera of the Xiaomi 17 Ultra main camera, a screen of its own (Camera2 on the Java API, like
 * the demo app of Anton Tretiakov's X17U LOFIC RAW v1.0, written here from his integration specification). The
 * viewfinder is the HAL's own preview stream; each shot merges the next Long + Short RAW pair of the frame and saves a
 * 16-bit float DNG to DCIM/Camera. The 4:3 setups, and a usable Short stream, need his root module.
 */
@SuppressLint("MissingPermission", "SetTextI18n", "UseSwitchCompatOrMaterialCode")
class LoficActivity : Activity() {
    private val appModuleKey = CaptureRequest.Key("xiaomi.app.module", IntArray::class.java)
    private val enableLoficKey = CaptureRequest.Key("com.xiaomi.sessionparams.enableLofic", IntArray::class.java)
    private val loficEnableKey = CaptureRequest.Key("com.xiaomi.camera.eiParams.loficEnable", ByteArray::class.java)
    private val currentModeKey =
        CaptureResult.Key("org.codeaurora.qcamera3.sensor_meta_data.current_mode", IntArray::class.java)
    private val exposureCountKey = CaptureResult.Key("com.qti.stats_control.ExposureCount", IntArray::class.java)

    private lateinit var texture: TextureView
    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private lateinit var exposureLabel: TextView
    private lateinit var isoLabel: TextView
    private lateinit var focusLabel: TextView
    private lateinit var exposureBar: SeekBar
    private lateinit var isoBar: SeekBar
    private lateinit var focusBar: SeekBar
    private lateinit var captureButton: Button

    private var cameraThread: HandlerThread? = null
    private var ioThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var ioHandler: Handler? = null
    private var processor: ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var previewSurface: Surface? = null
    private var dummyReader: ImageReader? = null
    private var videoReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawReaderA: ImageReader? = null
    private var rawReaderB: ImageReader? = null

    // Owned by the io thread.
    private var latestA: Image? = null
    private var latestB: Image? = null
    private var lastRowsCheckMs = 0L
    private var rowsLong = 0
    private var rowsShort = 0

    private val results = LinkedHashMap<Long, TotalCaptureResult>()

    @Volatile private var setup = LoficSetup.FOUR_THREE_30
    @Volatile private var autoExposure = true
    @Volatile private var exposureNs = 1_000_000L
    @Volatile private var iso = 100
    @Volatile private var autoFocus = true
    @Volatile private var focusDiopters = 0f
    @Volatile private var captureRequested = false
    @Volatile private var saving = false

    // Status, written by the camera thread.
    @Volatile private var mode = -1
    @Volatile private var exposureCount = -1
    @Volatile private var resultExposureNs = 0L
    @Volatile private var resultIso = 0
    @Volatile private var fps = 0.0
    private var lastTimestamp = 0L
    private var lastStatusMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        startThreads()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (texture.isAvailable) openCamera()
    }

    override fun onPause() {
        closeCamera()
        stopThreads()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && texture.isAvailable) openCamera()
        else message("Camera permission needed")
    }

    // ---- layout ----

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private fun label(text: String, size: Float = 14f): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun bar(max: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        this.max = max
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val frame = FrameLayout(this)
        texture = TextureView(this)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        frame.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeightPx()))
        statusView = label("", 12f).apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(28), dp(8), dp(4))
        }
        frame.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, LoficSetup.entries.map { it.label }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = LoficSetup.entries[position]
                if (next == setup) return
                setup = next
                iso = iso.coerceIn(next.isoMin, LoficSetup.ISO_MAX_ANALOG)
                texture.layoutParams = texture.layoutParams.also { it.height = previewHeightPx() }
                updateLabels()
                reopenCamera()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        controls.addView(spinner)

        val aeSwitch = Switch(this).apply {
            text = "Auto exposure"
            setTextColor(Color.WHITE)
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                autoExposure = checked
                updateLabels()
                applyRepeating()
            }
        }
        controls.addView(aeSwitch)
        exposureLabel = label("")
        controls.addView(exposureLabel)
        exposureBar = bar(1000) { p ->
            exposureNs = exposureFromProgress(p)
            updateLabels()
            applyRepeating()
        }
        controls.addView(exposureBar)
        isoLabel = label("")
        controls.addView(isoLabel)
        isoBar = bar(1000) { p ->
            iso = isoFromProgress(p)
            updateLabels()
            applyRepeating()
        }
        controls.addView(isoBar)

        val afSwitch = Switch(this).apply {
            text = "Autofocus"
            setTextColor(Color.WHITE)
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                autoFocus = checked
                updateLabels()
                applyRepeating()
            }
        }
        controls.addView(afSwitch)
        focusLabel = label("")
        controls.addView(focusLabel)
        focusBar = bar(1000) { p ->
            focusDiopters = p / 1000f * minFocusDiopters()
            updateLabels()
            applyRepeating()
        }
        controls.addView(focusBar)

        captureButton = Button(this).apply {
            text = "Capture DNG"
            setOnClickListener {
                if (!saving && session != null) {
                    saving = true
                    captureRequested = true
                    message("Capturing...")
                }
            }
        }
        controls.addView(captureButton)
        messageView = label("Rawr LOFIC (Xiaomi 17 Ultra main camera). Needs the X17U LOFIC RAW module by Anton Tretiakov for the 4:3 setups and a usable Short stream.", 12f)
        controls.addView(messageView)
        val scroll = ScrollView(this).apply { addView(controls) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        exposureBar.progress = progressFromExposure(exposureNs)
        isoBar.progress = progressFromIso(iso)
        updateLabels()
        return root
    }

    private fun previewHeightPx(): Int =
        resources.displayMetrics.widthPixels * setup.previewWidth / setup.previewHeight

    // Exposure 1/16000 s to 1/8 s and ISO from the setup's minimum to the analog maximum, both on log scales.
    private val exposureMinNs = 62_500.0
    private val exposureMaxNs = 125_000_000.0

    private fun exposureFromProgress(p: Int): Long =
        exp(ln(exposureMinNs) + (ln(exposureMaxNs) - ln(exposureMinNs)) * p / 1000.0).toLong()

    private fun progressFromExposure(ns: Long): Int =
        ((ln(ns.toDouble()) - ln(exposureMinNs)) / (ln(exposureMaxNs) - ln(exposureMinNs)) * 1000).roundToInt().coerceIn(0, 1000)

    private fun isoFromProgress(p: Int): Int {
        val lo = ln(setup.isoMin.toDouble())
        val hi = ln(LoficSetup.ISO_MAX_ANALOG.toDouble())
        return exp(lo + (hi - lo) * p / 1000.0).roundToInt()
    }

    private fun progressFromIso(v: Int): Int {
        val lo = ln(setup.isoMin.toDouble())
        val hi = ln(LoficSetup.ISO_MAX_ANALOG.toDouble())
        return ((ln(v.toDouble()) - lo) / (hi - lo) * 1000).roundToInt().coerceIn(0, 1000)
    }

    private fun minFocusDiopters(): Float =
        characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f

    private fun shutterText(ns: Long): String =
        if (ns <= 0) "-" else if (ns >= 1_000_000_000L) "%.1f s".format(Locale.ROOT, ns / 1e9) else "1/${(1e9 / ns).roundToInt()} s"

    private fun updateLabels() {
        exposureLabel.text = if (autoExposure) "Exposure auto" else "Exposure ${shutterText(exposureNs)}"
        isoLabel.text = if (autoExposure) "ISO auto" else "ISO $iso"
        focusLabel.text = if (autoFocus) "Focus auto" else "Focus %.2f dpt".format(Locale.ROOT, focusDiopters)
        exposureBar.isEnabled = !autoExposure
        isoBar.isEnabled = !autoExposure
        focusBar.isEnabled = !autoFocus
    }

    private fun message(text: String) {
        main.post { messageView.text = text }
    }

    // ---- camera ----

    private fun startThreads() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("lofic-camera").also { it.start(); cameraHandler = Handler(it.looper) }
        ioThread = HandlerThread("lofic-io").also { it.start(); ioHandler = Handler(it.looper) }
        processor = Executors.newSingleThreadExecutor()
    }

    private fun stopThreads() {
        cameraThread?.quitSafely()
        ioThread?.quitSafely()
        processor?.shutdown()
        cameraThread = null
        ioThread = null
        cameraHandler = null
        ioHandler = null
        processor = null
    }

    private fun openCamera() {
        val handler = cameraHandler ?: return
        if (camera != null) return
        val manager = getSystemService(CameraManager::class.java)
        try {
            characteristics = manager.getCameraCharacteristics(CAMERA_ID)
            manager.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    createSession(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    camera = null
                    message("Camera disconnected")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    camera = null
                    message("Camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "open", e)
            message("Open failed: ${e.message}")
        }
    }

    private fun reopenCamera() {
        closeCamera()
        openCamera()
    }

    private fun closeCamera() {
        try { session?.close() } catch (ignored: Exception) {}
        session = null
        try { camera?.close() } catch (ignored: Exception) {}
        camera = null
        val readers = listOfNotNull(dummyReader, videoReader, jpegReader, rawReaderA, rawReaderB)
        val held = listOfNotNull(latestA, latestB)
        latestA = null
        latestB = null
        dummyReader = null
        videoReader = null
        jpegReader = null
        rawReaderA = null
        rawReaderB = null
        val surface = previewSurface
        previewSurface = null
        val release = Runnable {
            held.forEach { it.close() }
            readers.forEach { it.close() }
            surface?.release()
        }
        ioHandler?.post(release) ?: release.run()
        synchronized(results) { results.clear() }
        captureRequested = false
    }

    private fun createSession(device: CameraDevice) {
        val s = setup
        val io = ioHandler ?: return
        val cam = cameraHandler ?: return
        val st = texture.surfaceTexture ?: return
        try {
            st.setDefaultBufferSize(s.previewWidth, s.previewHeight)
            val preview = Surface(st).also { previewSurface = it }
            val dummy = ImageReader.newInstance(LoficSetup.RAW_WIDTH, LoficSetup.RAW_HEIGHT, ImageFormat.RAW_SENSOR, 2)
            val video = ImageReader.newInstance(s.videoWidth, s.videoHeight, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_VIDEO_ENCODE)
            val jpeg = ImageReader.newInstance(s.jpegWidth, s.jpegHeight, ImageFormat.JPEG, 2)
            val rawA = ImageReader.newInstance(LoficSetup.RAW_WIDTH, LoficSetup.RAW_HEIGHT, ImageFormat.RAW_SENSOR, 4)
            val rawB = ImageReader.newInstance(LoficSetup.RAW_WIDTH, LoficSetup.RAW_HEIGHT, ImageFormat.RAW_SENSOR, 4)
            dummyReader = dummy
            videoReader = video
            jpegReader = jpeg
            rawReaderA = rawA
            rawReaderB = rawB
            video.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, io)
            rawA.setOnImageAvailableListener({ r -> onRaw(true, r) }, io)
            rawB.setOnImageAvailableListener({ r -> onRaw(false, r) }, io)
            // The order matters (specification): dummy RAW, viewfinder, video, JPEG (configured only), RAW A, RAW B.
            val outputs = listOf(dummy.surface, preview, video.surface, jpeg.surface, rawA.surface, rawB.surface)
                .map { OutputConfiguration(it) }
            val params = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            params.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(s.fps, s.fps))
            setKey(params, appModuleKey, intArrayOf(180))
            setKey(params, enableLoficKey, intArrayOf(1))
            val executor = Executor { cam.post(it) }
            val config = SessionConfiguration(s.sessionType, outputs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    applyRepeating()
                    message("Session ${s.label} running")
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    message("Session ${s.label} configure failed")
                }
            })
            config.sessionParameters = params.build()
            device.createCaptureSession(config)
        } catch (e: Exception) {
            Log.w(TAG, "session", e)
            message("Session failed: ${e.message}")
        }
    }

    private fun <T> setKey(builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
        try {
            builder.set(key, value)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "key ${key.name}: ${e.message}")
        }
    }

    private fun applyRepeating() {
        val cam = cameraHandler ?: return
        cam.post {
            val device = camera ?: return@post
            val sess = session ?: return@post
            val s = setup
            try {
                val b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                listOfNotNull(previewSurface, videoReader?.surface, rawReaderA?.surface, rawReaderB?.surface).forEach { b.addTarget(it) }
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(s.fps, s.fps))
                setKey(b, appModuleKey, intArrayOf(180))
                setKey(b, enableLoficKey, intArrayOf(1))
                setKey(b, loficEnableKey, byteArrayOf(1))
                if (autoExposure) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                } else {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs, s.minFrameNs))
                }
                if (autoFocus) {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                } else {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
                }
                sess.setRepeatingRequest(b.build(), captureCallback, cam)
            } catch (e: Exception) {
                Log.w(TAG, "repeating", e)
                message("Request failed: ${e.message}")
            }
        }
    }

    private fun <T> readResult(result: CaptureResult, key: CaptureResult.Key<T>): T? =
        try { result.get(key) } catch (ignored: IllegalArgumentException) { null }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            synchronized(results) {
                results[ts] = result
                while (results.size > 16) results.remove(results.keys.first())
            }
            mode = readResult(result, currentModeKey)?.firstOrNull() ?: -1
            exposureCount = readResult(result, exposureCountKey)?.firstOrNull() ?: -1
            resultExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            resultIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            if (lastTimestamp > 0 && ts > lastTimestamp) {
                val f = 1e9 / (ts - lastTimestamp)
                fps = if (fps <= 0) f else fps * 0.9 + f * 0.1
            }
            lastTimestamp = ts
            val now = System.currentTimeMillis()
            if (now - lastStatusMs > 200) {
                lastStatusMs = now
                postStatus()
            }
        }
    }

    private fun postStatus() {
        val s = setup
        val ok = mode == s.sensorMode && rowsLong == s.rows && rowsShort == s.rows
        val text = "sensor mode $mode · %.1f fps · ${shutterText(resultExposureNs)} · ISO $resultIso · exp $exposureCount · Long/Short rows $rowsLong/$rowsShort"
            .format(Locale.ROOT, fps)
        main.post {
            statusView.text = text
            statusView.setTextColor(if (ok) Color.WHITE else 0xFFFF6060.toInt())
        }
    }

    // ---- RAW pair (io thread) ----

    private fun onRaw(isA: Boolean, reader: ImageReader) {
        val img = try { reader.acquireLatestImage() } catch (ignored: IllegalStateException) { null } ?: return
        if (isA) {
            latestA?.close()
            latestA = img
        } else {
            latestB?.close()
            latestB = img
        }
        val a = latestA ?: return
        val b = latestB ?: return
        if (a.timestamp != b.timestamp) return
        val now = System.currentTimeMillis()
        if (now - lastRowsCheckMs > 1000) {
            lastRowsCheckMs = now
            checkRows(a, b)
        }
        if (captureRequested) grab(a, b)
    }

    private fun splitPair(a: Image, b: Image): Pair<Image, Image>? {
        val rows = setup.rows
        val aLong = LoficMerge.isLong(a.planes[0].buffer, rows)
        val bLong = LoficMerge.isLong(b.planes[0].buffer, rows)
        return when {
            aLong && !bLong -> a to b
            bLong && !aLong -> b to a
            else -> null
        }
    }

    private fun checkRows(a: Image, b: Image) {
        val pair = splitPair(a, b)
        if (pair == null) {
            rowsLong = 0
            rowsShort = 0
            return
        }
        rowsLong = LoficMerge.filledRows(pair.first.planes[0].buffer, LoficSetup.LONG_ROW_BYTES, LoficSetup.RAW_HEIGHT)
        rowsShort = LoficMerge.filledRows(pair.second.planes[0].buffer, LoficSetup.SHORT_ROW_BYTES, LoficSetup.RAW_HEIGHT)
    }

    private fun grab(a: Image, b: Image) {
        captureRequested = false
        val s = setup
        val pair = splitPair(a, b)
        if (pair == null) {
            saving = false
            message("Not a LOFIC pair: neither RAW holds ${s.rows} rows of Long data (sensor mode $mode, wanted ${s.sensorMode})")
            return
        }
        val longBuf = pair.first.planes[0].buffer
        val shortBuf = pair.second.planes[0].buffer
        val longBytes = ByteArray(s.rows * LoficSetup.LONG_ROW_BYTES)
        val shortBytes = ByteArray(s.rows * LoficSetup.SHORT_ROW_BYTES)
        if (longBuf.capacity() < longBytes.size || shortBuf.capacity() < shortBytes.size) {
            saving = false
            message("RAW buffers smaller than expected")
            return
        }
        longBuf.duplicate().also { it.position(0) }.get(longBytes)
        shortBuf.duplicate().also { it.position(0) }.get(shortBytes)
        val result = synchronized(results) { results[a.timestamp] }
        val frameMode = mode
        val work = processor
        if (work == null) {
            saving = false
            return
        }
        work.execute { save(s, frameMode, longBytes, shortBytes, result) }
    }

    // ---- merge and DNG (processor thread) ----

    private fun matrix(t: ColorSpaceTransform?): Array<Rational>? =
        t?.let { m -> Array(9) { i -> m.getElement(i % 3, i / 3) } }

    private fun dngOrientation(): Int = when (characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    private fun save(s: LoficSetup, frameMode: Int, longBytes: ByteArray, shortBytes: ByteArray, result: TotalCaptureResult?) {
        try {
            val frameIso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: resultIso
            val frameExposure = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: resultExposureNs
            val ratio = LoficMerge.estimateK(longBytes, shortBytes, s.rows, frameIso)
            val model = LoficSetup.modelK(frameIso)
            val baseline = log2(ratio.k * LoficMerge.SHORT_RANGE / LoficMerge.LONG_CLIP)
            val c = characteristics
            val writer = LoficDngWriter(
                width = LoficSetup.RAW_WIDTH,
                height = s.rows,
                make = Build.MANUFACTURER,
                model = Build.MODEL,
                uniqueModel = "${Build.MANUFACTURER} ${Build.MODEL} / LOFIC ${s.name} / camera $CAMERA_ID",
                software = "Rawr v${BuildConfig.VERSION_NAME} LOFIC",
                orientation = dngOrientation(),
                exposureNs = frameExposure,
                iso = frameIso,
                baselineExposure = baseline,
                colorMatrix1 = matrix(c?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
                colorMatrix2 = matrix(c?.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
                forwardMatrix1 = matrix(c?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)),
                forwardMatrix2 = matrix(c?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)),
                calibration1 = matrix(c?.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
                calibration2 = matrix(c?.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
                illuminant1 = c?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
                illuminant2 = c?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
                asShotNeutral = result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            )
            val name = "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date()) + "_LOFIC.dng"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("MediaStore insert failed")
            val l = IntArray(LoficSetup.RAW_WIDTH)
            val sh = IntArray(LoficSetup.RAW_WIDTH)
            (contentResolver.openOutputStream(uri) ?: error("no output stream")).use { raw ->
                BufferedOutputStream(raw, 1 shl 20).use { out ->
                    writer.write(out) { y, row ->
                        LoficMerge.unpackLong(longBytes, y, l)
                        LoficMerge.unpackShort(shortBytes, y, sh)
                        LoficMerge.mergeRow(l, sh, ratio.k, row)
                    }
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            val headroom = log2(LoficMerge.SHORT_RANGE * ratio.k / LoficMerge.LONG_CLIP)
            val module = ratio.measured && ratio.k > 0.75 * model && ratio.k < 1.25 * model
            Log.i(TAG, "LOFIC_SAVED name=$name setup=${s.name} mode=$frameMode rows=${s.rows} k=%.1f measured=${ratio.measured} samples=${ratio.samples} model=%.1f iso=$frameIso exposureNs=$frameExposure headroom=%.2f module=$module"
                .format(Locale.ROOT, ratio.k, model, headroom))
            message("Saved DCIM/Camera/$name\nk=%.0f (%s, model %.0f) · up to %.1f stops above the Long clip · module %s"
                .format(Locale.ROOT, ratio.k, if (ratio.measured) "measured" else "model", model, headroom,
                    if (module) "detected" else "not detected (k far from the model)"))
        } catch (e: Exception) {
            Log.w(TAG, "save", e)
            message("Save failed: ${e.message}")
        } finally {
            saving = false
        }
    }

    private companion object {
        const val TAG = "RawrLofic"
        const val CAMERA_ID = "2"
    }
}
