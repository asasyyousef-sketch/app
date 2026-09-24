package com.camstream.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class StreamQuality(val label: String, val w: Int, val h: Int, val rec: Quality) {
    HD("HD 720p", 1280, 720, Quality.HD),
    FHD("Full HD 1080p (موصى به)", 1920, 1080, Quality.FHD),
    UHD("4K (يحتاج جهاز وشبكة قويين)", 3840, 2160, Quality.UHD)
}

class CamEntry(val id: String, val label: String, val facing: Int)

class CameraService : LifecycleService(), StreamController {

    companion object {
        const val PORT = 3420
        const val TLS_PORT = 3421
        const val ACTION_STOP = "com.camstream.app.STOP"
        private const val CHANNEL = "camstream"

        @Volatile var instance: CameraService? = null
        @Volatile var quality: StreamQuality = StreamQuality.FHD
        @Volatile var accessKey: String = ""
        @Volatile var adminKey: String = ""

        fun localIps(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni ->
                    ni.inetAddresses.toList()
                        .filter { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                        .mapNotNull { it.hostAddress }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- حالة عامة تقرأها الواجهة ----
    @Volatile var lastError: String? = null
    @Volatile var torchOn = false
    @Volatile var recordingActive = false
    @Volatile var recordingSupported = false
    @Volatile var lastSaved: String? = null
    @Volatile var tlsOk = false
    @Volatile var camIndex = 0
    @Volatile var cameras: List<CamEntry> = emptyList()
    var recordingStartMs = 0L

    @Volatile private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var server: HttpStreamServer? = null
    private var tlsServer: HttpStreamServer? = null
    private var orientationListener: OrientationEventListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = ArrayList<WifiManager.WifiLock>()
    private lateinit var analysisExecutor: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    @Volatile private var focusManual = false
    @Volatile private var focusVal = 0f

    // ترميز الصور: نعيد استخدام المصفوفات لتقليل GC (سبب تقطيع شائع)
    private var nv21 = ByteArray(0)
    private val jpegOut = ByteArrayOutputStream(512 * 1024)
    private var lastEncodeMs = 0L
    @Volatile private var lastAnalyzerMs = 0L
    private var lastBindMs = 0L
    private var fpsCount = 0
    private var fpsStart = 0L

    // صوت
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null
    private var track: AudioTrack? = null
    private val talkLock = Any()

    override fun onCreate() {
        super.onCreate()
        instance = this
        analysisExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            Adaptive.reset()
            goForeground()
            acquireLocks()
            startServers()
            startAudioThread()
            initCameras()
            main.postDelayed(watchdog, 3000)
        }
        return START_NOT_STICKY
    }

    // ---------------- خدمة المقدمة ----------------
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "بث الكاميرا", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("البث المباشر يعمل")
            .setContentText("الكاميرا تُبث على المنفذ $PORT")
            .setContentIntent(open)
            .addAction(0, "إيقاف", stop)
            .setOngoing(true)
            .build()

        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasPerm(Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        ServiceCompat.startForeground(this, 1, n, type)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camstream:wl").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        // قفلان للواي فاي: أحدهما يعمل والشاشة مطفأة والآخر بزمن استجابة منخفض
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val modes = ArrayList<Int>()
            modes.add(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
            if (Build.VERSION.SDK_INT >= 29) modes.add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
            for ((i, m) in modes.withIndex()) {
                try {
                    val l = wm.createWifiLock(m, "camstream:wifi$i")
                    l.setReferenceCounted(false)
                    l.acquire()
                    wifiLocks.add(l)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startServers() {
        ViewerStats.reset()
        val page = try {
            assets.open("index.html").use { it.readBytes() }
        } catch (e: Exception) {
            "index.html missing".toByteArray()
        }
        try {
            server = HttpStreamServer(PORT, null, this, page, { accessKey }, { adminKey })
                .also { it.start() }
        } catch (e: Exception) {
            lastError = "تعذر تشغيل السيرفر على المنفذ $PORT: ${e.message}"
        }
        try {
            val ctx = TlsUtil.createContext()
            tlsServer = HttpStreamServer(TLS_PORT, ctx, this, page, { accessKey }, { adminKey })
                .also { it.start() }
            tlsOk = true
        } catch (e: Exception) {
            tlsOk = false      // التحدث فقط لن يعمل؛ باقي الميزات لا تتأثر
        }
    }

    // ---------------- الكاميرات ----------------
    private fun initCameras() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                cameras = p.availableCameraInfos.mapNotNull { info ->
                    try {
                        val c2 = Camera2CameraInfo.from(info)
                        val id = c2.cameraId
                        val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ?: -1
                        val name = when (facing) {
                            CameraCharacteristics.LENS_FACING_BACK -> "خلفية"
                            CameraCharacteristics.LENS_FACING_FRONT -> "أمامية"
                            else -> "خارجية"
                        }
                        CamEntry(id, "كاميرا $name (رقم $id)", facing)
                    } catch (e: Exception) {
                        null
                    }
                }.sortedBy { if (it.facing == CameraCharacteristics.LENS_FACING_BACK) 0 else 1 }
                camIndex = 0
                bind()
            } catch (e: Exception) {
                lastError = "خطأ في تهيئة الكاميرا: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val entry = cameras.getOrNull(camIndex)
        if (entry == null) {
            lastError = "لم يتم العثور على كاميرات"
            return
        }
        stopRecordingInternal()
        p.unbindAll()
        torchOn = false
        focusManual = false
        FrameBroker.reset()
        lastBindMs = SystemClock.elapsedRealtime()
        lastAnalyzerMs = lastBindMs

        val selector = CameraSelector.Builder()
            .addCameraFilter { list ->
                list.filter { Camera2CameraInfo.from(it).cameraId == entry.id }
            }
            .build()

        val q = quality
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(q.w, q.h),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        val ia = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        ia.setAnalyzer(analysisExecutor) { img -> onFrame(img) }
        analysis = ia

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(q.rec, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        val vc = VideoCapture.withOutput(recorder)

        lastError = null
        try {
            camera = p.bindToLifecycle(this, selector, ia, vc)
            videoCapture = vc
            recordingSupported = true
        } catch (e: Exception) {
            // بعض الأجهزة لا تدعم بث + تسجيل بنفس الوقت بهذه الدقة — نكمل بالبث فقط
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(this, selector, ia)
                videoCapture = null
                recordingSupported = false
            } catch (e2: Exception) {
                camera = null
                lastError = "تعذر تشغيل الكاميرا: ${e2.message}"
            }
        }
        setupOrientation()
    }

    /** إصلاح ذاتي: إذا توقفت الكاميرا عن تسليم الصور (أو فشل تشغيلها) نعيد ربطها تلقائياً. */
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                if (provider != null && cameras.isNotEmpty() && now - lastBindMs > 5000) {
                    val dead = camera == null || now - lastAnalyzerMs > 6000
                    if (dead) bind()
                }
            } catch (_: Exception) {
            }
            main.postDelayed(this, 3000)
        }
    }

    private fun setupOrientation() {
        orientationListener?.disable()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when {
                    o >= 315 || o < 45 -> Surface.ROTATION_0
                    o < 135 -> Surface.ROTATION_270
                    o < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                analysis?.targetRotation = r
                videoCapture?.targetRotation = r
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    // ---------------- معالجة الإطارات ----------------
    private fun fillNv21(img: ImageProxy): ByteArray {
        val w = img.width
        val h = img.height
        val size = w * h * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)
        val out = nv21
        val yp = img.planes[0]
        val up = img.planes[1]
        val vp = img.planes[2]
        val yb = yp.buffer
        val ub = up.buffer
        val vb = vp.buffer

        val yRs = yp.rowStride
        if (yRs == w) {
            yb.position(0)
            yb.get(out, 0, w * h)
        } else {
            for (r in 0 until h) {
                yb.position(r * yRs)
                yb.get(out, r * w, w)
            }
        }
        var pos = w * h
        val uRs = up.rowStride
        val uPs = up.pixelStride
        val vRs = vp.rowStride
        val vPs = vp.pixelStride
        val cw = w / 2
        val ch = h / 2
        for (r in 0 until ch) {
            var vi = r * vRs
            var ui = r * uRs
            for (c in 0 until cw) {
                out[pos++] = vb.get(vi)
                out[pos++] = ub.get(ui)
                vi += vPs
                ui += uPs
            }
        }
        return out
    }

    private fun onFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        lastAnalyzerMs = now
        try {
            if (!FrameBroker.needed()) return
            if (now - lastEncodeMs < 33) return           // سقف 30 إطار/ثانية
            lastEncodeMs = now
            val w = image.width
            val h = image.height
            val data = fillNv21(image)
            jpegOut.reset()
            YuvImage(data, ImageFormat.NV21, w, h, null)
                .compressToJpeg(Rect(0, 0, w, h), Adaptive.jpegQuality, jpegOut)
            FrameBroker.publish(jpegOut.toByteArray(), image.imageInfo.rotationDegrees, w, h)

            fpsCount++
            if (now - fpsStart >= 1000) {
                FrameBroker.fps = fpsCount
                fpsCount = 0
                fpsStart = now
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    // ---------------- أوامر الكاميرا ----------------
    fun cameraLabel(): String = cameras.getOrNull(camIndex)?.label ?: "-"

    fun nextCamera() {
        if (cameras.size < 2) return
        camIndex = (camIndex + 1) % cameras.size
        bind()
    }

    private fun selectCamera(i: Int) {
        if (i < 0 || i >= cameras.size || i == camIndex) return
        camIndex = i
        bind()
    }

    fun applyQuality() {
        bind()
    }

    fun setTorch(on: Boolean): Boolean {
        val cam = camera ?: return false
        if (!cam.cameraInfo.hasFlashUnit()) return false
        cam.cameraControl.enableTorch(on)
        torchOn = on
        return true
    }

    private fun setZoom(r: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(r.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
    }

    private fun minFocusDist(): Float = try {
        val ci = camera?.cameraInfo
        if (ci == null) 0f
        else Camera2CameraInfo.from(ci)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    } catch (e: Exception) {
        0f
    }

    private fun tapFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val ia = analysis ?: return
        if (focusManual) setFocusMode(false)
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f, ia)
        val pt = factory.createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val action = FocusMeteringAction.Builder(
            pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun setFocusMode(manual: Boolean) {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        if (manual && minFocusDist() > 0f) {
            focusManual = true
            setManualFocus(focusVal)
        } else {
            focusManual = false
            c2.clearCaptureRequestOptions()
        }
    }

    private fun setManualFocus(v: Float) {
        val cam = camera ?: return
        val min = minFocusDist()
        if (min <= 0f) return
        focusVal = v.coerceIn(0f, 1f)
        val dist = focusVal * min          // 0 = بعيد جداً، min = أقرب نقطة
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF
                )
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, dist)
                .build()
        )
        focusManual = true
    }

    // ---------------- StreamController (يستدعيه السيرفر من خيوط أخرى) ----------------
    override fun statusJson(admin: Boolean): String {
        val o = JSONObject()
        o.put("viewers", ViewerStats.count())
        o.put("width", FrameBroker.width)
        o.put("height", FrameBroker.height)
        o.put("fps", FrameBroker.fps)
        o.put("live", camera != null)
        o.put("frameAge", SystemClock.elapsedRealtime() - lastAnalyzerMs)
        o.put("error", lastError ?: "")
        o.put("admin", admin)
        if (admin) {
            val cam = camera
            val zs = cam?.cameraInfo?.zoomState?.value
            o.put("cameras", JSONArray(cameras.map { it.label }))
            o.put("camIndex", camIndex)
            o.put("hasFlash", cam?.cameraInfo?.hasFlashUnit() == true)
            o.put("torch", torchOn)
            o.put("zoomMin", (zs?.minZoomRatio ?: 1f).toDouble())
            o.put("zoomMax", (zs?.maxZoomRatio ?: 1f).toDouble())
            o.put("zoomCur", (zs?.zoomRatio ?: 1f).toDouble())
            o.put("minFocus", minFocusDist().toDouble())
            o.put("focusManual", focusManual)
            o.put("focusVal", focusVal.toDouble())
            o.put("quality", quality.ordinal)
            o.put("qualities", JSONArray(StreamQuality.values().map { it.label }))
            o.put("jpegQ", Adaptive.jpegQuality)
            o.put("tls", tlsOk)
            o.put("tlsPort", TLS_PORT)
            o.put("viewerIps", JSONArray(ViewerStats.ips()))
        }
        return o.toString()
    }

    override fun command(name: String, params: Map<String, String>): Boolean {
        when (name) {
            "switch" -> main.post { nextCamera() }
            "camera" -> {
                val i = params["i"]?.toIntOrNull() ?: return false
                main.post { selectCamera(i) }
            }
            "torch" -> {
                val on = params["on"] == "1"
                main.post { setTorch(on) }
            }
            "zoom" -> {
                val r = params["r"]?.toFloatOrNull() ?: return false
                main.post { setZoom(r) }
            }
            "focus" -> {
                val x = params["x"]?.toFloatOrNull() ?: return false
                val y = params["y"]?.toFloatOrNull() ?: return false
                main.post { tapFocus(x, y) }
            }
            "focusmode" -> {
                val manual = params["m"] == "manual"
                main.post { setFocusMode(manual) }
            }
            "focusdist" -> {
                val v = params["v"]?.toFloatOrNull() ?: return false
                main.post { setManualFocus(v) }
            }
            "quality" -> {
                val i = params["i"]?.toIntOrNull() ?: return false
                val all = StreamQuality.values()
                if (i < 0 || i >= all.size) return false
                main.post {
                    if (all[i] != quality) {
                        quality = all[i]
                        applyQuality()
                    }
                }
            }
            else -> return false
        }
        return true
    }

    // ---------------- التسجيل على الهاتف ----------------
    /** يرجع رسالة خطأ أو null إذا نجح. */
    fun toggleRecording(): String? {
        if (recordingActive) {
            recording?.stop()
            recordingActive = false
            return null
        }
        return startRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(): String? {
        val vc = videoCapture
            ?: return "التسجيل غير مدعوم بهذه الجودة/الكاميرا، جرّب جودة أقل"
        val name = "CamStream_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/CamStream")
            }
        }
        val opts = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(cv).build()

        var pending = vc.output.prepareRecording(this, opts)
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                if (ev.hasError()) {
                    recording = null
                    recordingActive = false
                    lastError = "فشل التسجيل (رمز ${ev.error})"
                } else {
                    lastSaved = "تم الحفظ في: Movies/CamStream/$name.mp4"
                }
            }
        }
        recordingActive = true
        recordingStartMs = SystemClock.elapsedRealtime()
        return null
    }

    private fun stopRecordingInternal() {
        try { recording?.stop() } catch (_: Exception) {}
        recording = null
        recordingActive = false
    }

    // ---------------- صوت المايك (استماع) ----------------
    private fun startAudioThread() {
        audioRunning = true
        audioThread = Thread { audioLoop() }.apply {
            isDaemon = true
            name = "camstream-audio"
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        var rec: AudioRecord? = null
        val minBuf = AudioRecord.getMinBufferSize(
            AudioBroker.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val buf = ByteArray(1600)     // 50ms
        fun release() {
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            rec = null
        }
        while (audioRunning) {
            try {
                if (AudioBroker.count() == 0) {          // لا مستمعين = المايك مغلق
                    if (rec != null) release()
                    Thread.sleep(200)
                    continue
                }
                if (rec == null) {
                    if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                        Thread.sleep(1000)
                        continue
                    }
                    val r = AudioRecord(
                        MediaRecorder.AudioSource.MIC, AudioBroker.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, 8192)
                    )
                    if (r.state != AudioRecord.STATE_INITIALIZED) {
                        r.release()
                        Thread.sleep(1000)
                        continue
                    }
                    r.startRecording()
                    rec = r
                }
                val n = rec!!.read(buf, 0, buf.size)
                if (n > 0) AudioBroker.publish(buf.copyOf(n))
                else if (n < 0) {
                    release()
                    Thread.sleep(500)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                release()
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }
        release()
    }

    // ---------------- التحدث عبر سماعة الهاتف ----------------
    override fun talkData(buf: ByteArray, len: Int) {
        synchronized(talkLock) {
            var t = track
            if (t == null) {
                val min = AudioTrack.getMinBufferSize(
                    AudioBroker.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AudioBroker.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(min, 8192) * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                t.play()
                track = t
            }
            t.write(buf, 0, len)
        }
    }

    override fun talkEnded() {
        synchronized(talkLock) {
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            track = null
        }
    }

    // ---------------- إحصائيات للواجهة ----------------
    fun viewerCount(): Int = ViewerStats.count()
    fun viewerIps(): List<String> = ViewerStats.ips()
    fun uniqueVisitors(): Int = ViewerStats.uniqueEver()

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        stopRecordingInternal()
        orientationListener?.disable()
        audioRunning = false
        audioThread?.interrupt()
        talkEnded()
        try { provider?.unbindAll() } catch (_: Exception) {}
        server?.stop()
        tlsServer?.stop()
        ViewerStats.reset()
        FrameBroker.reset()
        try { wakeLock?.release() } catch (_: Exception) {}
        wifiLocks.forEach { try { it.release() } catch (_: Exception) {} }
        analysisExecutor.shutdown()
        instance = null
        super.onDestroy()
    }
}
