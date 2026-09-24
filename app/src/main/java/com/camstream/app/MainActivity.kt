package com.camstream.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.security.SecureRandom
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    @Volatile private var decoding = false
    private var lastSeq = 0L

    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvUrls: TextView
    private lateinit var tvViewers: TextView
    private lateinit var tvCamera: TextView
    private lateinit var tvRec: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var btnStart: Button
    private lateinit var btnCopy: Button
    private lateinit var btnCopyAdmin: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnTorch: Button
    private lateinit var btnRecord: Button
    private lateinit var btnBattery: Button
    private lateinit var cbKeepOn: CheckBox
    private lateinit var spQuality: Spinner
    private lateinit var etKey: EditText

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPerm(Manifest.permission.CAMERA)) startCamService()
            else toast("صلاحية الكاميرا مطلوبة لتشغيل البث")
        }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)
        tvUrls = findViewById(R.id.tvUrls)
        tvViewers = findViewById(R.id.tvViewers)
        tvCamera = findViewById(R.id.tvCamera)
        tvRec = findViewById(R.id.tvRec)
        ivPreview = findViewById(R.id.ivPreview)
        btnStart = findViewById(R.id.btnStart)
        btnCopy = findViewById(R.id.btnCopy)
        btnCopyAdmin = findViewById(R.id.btnCopyAdmin)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnTorch = findViewById(R.id.btnTorch)
        btnRecord = findViewById(R.id.btnRecord)
        btnBattery = findViewById(R.id.btnBattery)
        cbKeepOn = findViewById(R.id.cbKeepOn)
        spQuality = findViewById(R.id.spQuality)
        etKey = findViewById(R.id.etKey)

        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        etKey.setText(prefs.getString("key", ""))

        // مفتاح التحكم: يُولَّد تلقائياً مرة واحدة ويُحفظ
        var ak = prefs.getString("akey", "") ?: ""
        if (ak.isEmpty()) {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            ak = (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            prefs.edit().putString("akey", ak).apply()
        }
        CameraService.adminKey = ak
        CameraService.accessKey = prefs.getString("key", "") ?: ""

        val qs = StreamQuality.values()
        spQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, qs.map { it.label }
        )
        val savedQ = prefs.getInt("q", StreamQuality.FHD.ordinal).coerceIn(0, qs.size - 1)
        spQuality.setSelection(savedQ)
        CameraService.quality = qs[savedQ]
        spQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val newQ = qs[pos]
                prefs.edit().putInt("q", pos).apply()
                if (newQ != CameraService.quality) {
                    CameraService.quality = newQ
                    CameraService.instance?.applyQuality()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnStart.setOnClickListener {
            if (CameraService.instance != null) {
                stopService(Intent(this, CameraService::class.java))
            } else {
                val key = etKey.text.toString().replace(Regex("[^A-Za-z0-9]"), "")
                etKey.setText(key)
                prefs.edit().putString("key", key).apply()
                CameraService.accessKey = key
                requestAndStart()
            }
        }

        btnSwitch.setOnClickListener { CameraService.instance?.nextCamera() }

        btnTorch.setOnClickListener {
            val svc = CameraService.instance ?: return@setOnClickListener
            if (!svc.setTorch(!svc.torchOn)) toast("هذه الكاميرا لا تحتوي على فلاش")
        }

        btnRecord.setOnClickListener {
            val msg = CameraService.instance?.toggleRecording()
            if (msg != null) toast(msg)
        }

        btnCopy.setOnClickListener { copyFirst(urls(false)) }
        btnCopyAdmin.setOnClickListener { copyFirst(urls(true)) }

        cbKeepOn.isChecked = prefs.getBoolean("keepOn", true)
        cbKeepOn.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("keepOn", checked).apply()
            applyKeepOn(checked)
        }
        applyKeepOn(cbKeepOn.isChecked)

        btnBattery.setOnClickListener {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("التطبيق مستثنى من توفير البطارية مسبقاً ✓")
            } else {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }

    /** الشاشة تبقى شغالة بأقل سطوع: يمنع الهاتف من إدخال الواي فاي في وضع التوفير أثناء البث. */
    private fun applyKeepOn(on: Boolean) {
        val lp = window.attributes
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lp.screenBrightness = 0.01f
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun urls(admin: Boolean): List<String> {
        val parts = ArrayList<String>()
        if (CameraService.accessKey.isNotEmpty()) parts.add("key=" + CameraService.accessKey)
        if (admin) parts.add("akey=" + CameraService.adminKey)
        val qs = if (parts.isEmpty()) "" else "/?" + parts.joinToString("&")
        return CameraService.localIps().map { "http://$it:${CameraService.PORT}$qs" }
    }

    private fun secureUrls(): List<String> {
        val parts = ArrayList<String>()
        if (CameraService.accessKey.isNotEmpty()) parts.add("key=" + CameraService.accessKey)
        parts.add("akey=" + CameraService.adminKey)
        return CameraService.localIps()
            .map { "https://$it:${CameraService.TLS_PORT}/?" + parts.joinToString("&") }
    }

    private fun copyFirst(list: List<String>) {
        val url = list.firstOrNull()
        if (url == null) {
            toast("لا يوجد اتصال واي فاي")
        } else {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("stream", url))
            toast("تم نسخ الرابط")
        }
    }

    private fun requestAndStart() {
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = need.filter { !hasPerm(it) }
        if (missing.isEmpty()) startCamService() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startCamService() {
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
    }

    private fun refresh() {
        val svc = CameraService.instance
        val running = svc != null
        btnStart.text = if (running) "إيقاف البث" else "بدء البث"
        btnSwitch.isEnabled = running
        btnTorch.isEnabled = running
        btnRecord.isEnabled = running
        btnCopy.isEnabled = running
        btnCopyAdmin.isEnabled = running
        etKey.isEnabled = !running

        if (svc == null) {
            tvStatus.text = "⏹ البث متوقف"
            tvUrls.text = ""
            tvViewers.text = ""
            tvCamera.text = ""
            tvRec.text = ""
            tvInfo.text = ""
            ivPreview.setImageDrawable(null)
            lastSeq = 0L
            return
        }

        FrameBroker.touch()
        val err = svc.lastError
        tvStatus.text = if (err != null) "⚠ $err" else "🔴 البث يعمل على المنفذ ${CameraService.PORT}"

        val viewer = urls(false)
        val admin = urls(true)
        tvUrls.text = if (viewer.isEmpty()) {
            "⚠ لا يوجد اتصال واي فاي / هوتسبوت"
        } else {
            "رابط المشاهدين (شاركه):\n" + viewer.joinToString("\n") +
                    "\n\nرابط التحكم (لك فقط - فيه الفلاش والفوكس والتحدث):\n" + admin.joinToString("\n") +
                    (if (svc.tlsOk) "\n\nرابط التحكم الآمن (للتحدث بالمايك):\n" + secureUrls().joinToString("\n")
                    else "")
        }

        val ips = svc.viewerIps()
        tvViewers.text = "👁 المشاهدون الآن: ${svc.viewerCount()}\n" +
                "إجمالي الأجهزة منذ التشغيل: ${svc.uniqueVisitors()}" +
                (if (ips.isNotEmpty()) "\n" + ips.joinToString(" ، ") else "")

        tvCamera.text = "الكاميرا الحالية: " + svc.cameraLabel() +
                (if (svc.cameras.size > 1) "  (${svc.cameras.size} كاميرات متاحة)" else "")

        btnTorch.text = if (svc.torchOn) "إطفاء الفلاش 🔦" else "تشغيل الفلاش 🔦"
        btnRecord.text = if (svc.recordingActive) "⏹ إيقاف التسجيل" else "⏺ بدء التسجيل"

        tvRec.text = if (svc.recordingActive) {
            val s = (SystemClock.elapsedRealtime() - svc.recordingStartMs) / 1000
            "🔴 يسجّل: %02d:%02d".format(s / 60, s % 60)
        } else {
            (svc.lastSaved ?: "") +
                    (if (!svc.recordingSupported) "\nالتسجيل غير مدعوم بهذه الجودة، جرّب جودة أقل" else "")
        }

        tvInfo.text = "${FrameBroker.width}x${FrameBroker.height} • ${FrameBroker.fps} fps" +
                " • جودة JPEG تلقائية: ${Adaptive.jpegQuality}"

        // الجودة قد تتغير من صفحة الويب
        if (spQuality.selectedItemPosition != CameraService.quality.ordinal) {
            spQuality.setSelection(CameraService.quality.ordinal)
        }
        updatePreview()
    }

    private fun updatePreview() {
        val f = FrameBroker.latestFrame() ?: return
        if (f.seq == lastSeq || decoding) return
        decoding = true
        lastSeq = f.seq
        decoder.execute {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            var bmp = BitmapFactory.decodeByteArray(f.data, 0, f.data.size, opts)
            if (bmp != null && f.rot != 0) {
                val m = Matrix().apply { postRotate(f.rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val shown = bmp
            runOnUiThread {
                if (shown != null) ivPreview.setImageBitmap(shown)
                decoding = false
            }
        }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
