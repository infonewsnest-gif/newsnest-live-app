package bd.newsnest.live

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.encoder.input.gl.render.filters.CropFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.rtmp.RtmpFromFile
import com.pedro.library.view.GlInterface
import com.pedro.library.view.OpenGlView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : Activity(), ConnectChecker, VideoDecoderInterface, AudioDecoderInterface {
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("nn", MODE_PRIVATE) }
    private var videoUri: Uri? = null
    private var gen: RtmpFromFile? = null
    private var cam: RtmpCamera2? = null
    private var glI: GlInterface? = null
    private var liveId: String? = null
    private var pageToken = ""
    private var live = false
    private var startMs = 0L
    private var bitrate = 1_500_000
    private var qIdx = 1
    private val qWH = listOf(640 to 360, 1280 to 720, 1920 to 1080)

    private lateinit var col: LinearLayout
    private lateinit var pageId: EditText
    private lateinit var longTok: EditText
    private lateinit var shortTok: EditText
    private lateinit var title: EditText
    private lateinit var status: TextView
    private lateinit var videoName: TextView
    private lateinit var videoDur: TextView
    private lateinit var playBtn: Button
    private lateinit var soundBtn: Button
    private lateinit var fullBtn: Button
    private lateinit var pcCard: LinearLayout
    private var full = false
    private var autoRot = false
    private lateinit var rotBtn: TextView
    private lateinit var seekBar: SeekBar
    private var seeking = false
    private var mp: MediaPlayer? = null
    private var previewSound = true
    private var durTotal = "--:--"
    private var durMs = 0
    private lateinit var timer: TextView
    private lateinit var liveBtn: Button
    private lateinit var preview: VideoView
    private lateinit var pageLogo: ImageView
    private lateinit var pageName: TextView
    private lateinit var pageFans: TextView
    private lateinit var statsView: TextView
    private lateinit var commentsView: TextView

    // ক্যামেরা
    private lateinit var glView: OpenGlView
    private lateinit var camRow: LinearLayout
    private lateinit var micBtn: Button
    private var srcCamera = false
    private var micOn = true
    private var surfaceReady = false
    private var fileAspect = 16f / 9f

    // লোগো ও ক্যাপশন
    private lateinit var logoPrev: ImageView
    private lateinit var capBox: FrameLayout
    private lateinit var ticker: TextView
    private lateinit var capText: EditText
    private lateinit var capColor: EditText
    private lateinit var capBg: EditText
    private var logoBmp: Bitmap? = null
    private var tAnim: ValueAnimator? = null
    private var logoPx = 96
    private var logoXPct = 2f
    private var logoYPct = 3f
    private var speedSec = 18
    private var dirRtl = true   // true = ডান → বাম

    // ফ্রেম, জুম, শব্দ
    private lateinit var previewBox: FrameLayout
    private lateinit var barLeftV: View
    private lateinit var barRightV: View
    private lateinit var barTopV: View
    private lateinit var barBotV: View
    private lateinit var frameInfo: TextView
    private lateinit var listenCb: CheckBox
    private var videoAspect = 16f / 9f
    private var frameAspect = 0f   // 0 = মূল (কোনো বার নেই)
    private var zoom = 1f

    // স্ট্রিমের ওভারলে
    private var cropFilter: CropFilterRender? = null
    private var barL: ImageObjectFilterRender? = null
    private var barR: ImageObjectFilterRender? = null
    private var barT: ImageObjectFilterRender? = null
    private var barB: ImageObjectFilterRender? = null
    private var capFilter: TextObjectFilterRender? = null
    private var logoFilter: ImageObjectFilterRender? = null
    private var timeFilter: TextObjectFilterRender? = null
    private var streamW = 1280
    private var streamH = 720
    private var capX = 0f
    private var capY = 90f

    // ওভারলে স্ট্রিম চালুর পরে জোড়া হবে, তাই এখানে রাখা হয়
    private var pendingGl: GlInterface? = null
    private var pendingW = 1280
    private var pendingH = 720

    private var pfdV: android.os.ParcelFileDescriptor? = null
    private var pfdA: android.os.ParcelFileDescriptor? = null

    private val GREEN = 0xFF10B981.toInt()
    private val DARK = 0xFF1F2937.toInt()

    // ---------- UI সাহায্যকারী ----------
    private fun round(c: Int, r: Float = 28f, stroke: Int = 0) = GradientDrawable().apply {
        setColor(c); cornerRadius = r
        if (stroke != 0) setStroke(2, stroke)
    }

    private fun et(hint: String, v: String, pw: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(v); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        background = round(0xFF060B18.toInt(), 24f, 0xFF1F2937.toInt())
        setPadding(36, 28, 36, 28)
        if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun tv(t: String, size: Float = 14f) = TextView(this).apply { text = t; textSize = size; setTextColor(Color.WHITE) }

    private fun btn(t: String, c: Int = GREEN, f: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); background = round(c, 24f); isAllCaps = false
        setOnClickListener { f() }
    }

    private fun colr(s: String, d: Int) = try { Color.parseColor(s.trim()) } catch (_: Exception) { d }

    private fun gap(h: Int = 14) = View(this).also { it.layoutParams = LinearLayout.LayoutParams(-1, h) }

    private fun card(heading: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(0xFF0F172A.toInt(), 40f, 0xFF1F2937.toInt())
            setPadding(36, 32, 36, 32)
        }
        if (heading.isNotEmpty()) c.addView(tv(heading, 18f))
        col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 24, 0, 0) })
        return c
    }

    // নির্বাচিত বোতাম সবুজ, বাকিগুলো কালো। perRow = এক সারিতে কয়টা বোতাম
    private fun toggles(labels: List<String>, init: Int, perRow: Int, onPick: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bs = mutableListOf<Button>()
        fun paint(sel: Int) { bs.forEachIndexed { i, b -> b.background = round(if (i == sel) GREEN else DARK, 24f) } }
        var row: LinearLayout? = null
        labels.forEachIndexed { i, l ->
            if (i % perRow == 0) { row = LinearLayout(this); box.addView(row) }
            val b = Button(this).apply {
                text = l; setTextColor(Color.WHITE); isAllCaps = false
                setOnClickListener { paint(i); onPick(i) }
            }
            bs.add(b)
            row!!.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 8, 6, 8) })
        }
        paint(init)
        return box
    }

    private fun seek(min: Int, max: Int, init: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max - min; progress = init - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { onChange(p + min) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 60, 36, 60) }
        val sv = ScrollView(this).apply { setBackgroundColor(0xFF0B1020.toInt()); addView(col) }
        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 9) } catch (_: Exception) {}
        }

        logoPx = prefs.getInt("logopx", 96)
        logoXPct = prefs.getFloat("lx", 2f)
        logoYPct = prefs.getFloat("ly", 3f)
        speedSec = prefs.getInt("speed", 18)
        dirRtl = prefs.getBoolean("rtl", true)

        status = tv("● অফলাইন", 16f)
        videoName = tv("কোনো ভিডিও নির্বাচিত নয়")
        videoDur = tv("সময়: --:--", 16f)
        timer = tv("00:00", 28f)
        pageId = et("পেইজ আইডি", prefs.getString("pid", "") ?: "")
        longTok = et("লং-লিভড টোকেন", prefs.getString("ltok", "") ?: "", true)
        shortTok = et("শর্ট-লিভড টোকেন", prefs.getString("stok", "") ?: "", true)
        title = et("টাইটেল", prefs.getString("title", "NewsNest Live — সরাসরি সম্প্রচার") ?: "")

        // হেডার (অটো রোটেট আইকন + অবস্থা)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val headCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headCol.addView(tv("NewsNest Live", 24f))
        headCol.addView(tv("Facebook লাইভ স্ট্রিমিং স্টুডিও", 13f).apply { setTextColor(Color.GRAY) })
        head.addView(headCol, LinearLayout.LayoutParams(0, -2, 1f))
        status.background = round(0xFF1F2937.toInt(), 60f)
        status.setPadding(36, 16, 36, 16)
        rotBtn = tv("🔒", 22f).apply {
            background = round(0xFF1F2937.toInt(), 60f)
            setPadding(30, 14, 30, 14)
            setOnClickListener { toggleAuto() }
        }
        head.addView(rotBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 16, 0) })
        head.addView(status)
        col.addView(head)

        // ---------- প্রিভিউ কার্ড ----------
        val pc = card("")
        pcCard = pc
        previewBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = VideoView(this)
        previewBox.addView(preview, FrameLayout.LayoutParams(-1, -1))

        // ক্যামেরার লাইভ প্রিভিউ (ভিডিও ফাইল মোডে লুকানো থাকে)
        glView = OpenGlView(this).apply { visibility = View.GONE }
        previewBox.addView(glView, FrameLayout.LayoutParams(-1, -1))
        glView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                surfaceReady = true
                if (srcCamera && cam != null) startCamPreview()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfaceReady = false
                stopCamPreview()
            }
        })

        barLeftV = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        barRightV = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        barTopV = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        barBotV = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        previewBox.addView(barLeftV, FrameLayout.LayoutParams(10, -1, Gravity.START))
        previewBox.addView(barRightV, FrameLayout.LayoutParams(10, -1, Gravity.END))
        previewBox.addView(barTopV, FrameLayout.LayoutParams(-1, 10, Gravity.TOP))
        previewBox.addView(barBotV, FrameLayout.LayoutParams(-1, 10, Gravity.BOTTOM))
        logoPrev = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true }
        previewBox.addView(logoPrev, FrameLayout.LayoutParams(logoPx, -2, Gravity.TOP or Gravity.START))
        capBox = FrameLayout(this)
        ticker = TextView(this).apply { textSize = 18f; setSingleLine(true); setHorizontallyScrolling(true); setPadding(20, 6, 20, 6) }
        capBox.addView(ticker, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
        previewBox.addView(capBox, FrameLayout.LayoutParams(-1, 90, Gravity.BOTTOM))
        pc.addView(previewBox, LinearLayout.LayoutParams(-1, 600))
        pc.addView(videoName)
        pc.addView(videoDur)

        // ভিডিও সিক বার: টেনে যেকোনো মিনিটে যাওয়া যায়
        seekBar = SeekBar(this).apply {
            max = 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        preview.seekTo(p)
                        videoDur.text = "সময়: ${fmtMs(p)} / $durTotal মিনিট"
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    seeking = false
                    seekStream(seekBar.progress)
                }
            })
        }
        pc.addView(seekBar)

        playBtn = btn("▶ প্লে", DARK) { togglePlay() }
        soundBtn = btn("🔊 শব্দ চালু", DARK) { previewSound = !previewSound; applySound() }
        fullBtn = btn("⛶ ফুলস্ক্রিন", DARK) { toggleFull() }
        val prow2 = LinearLayout(this)
        prow2.addView(playBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 8, 6, 8) })
        prow2.addView(soundBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 8, 6, 8) })
        prow2.addView(fullBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 8, 6, 8) })
        pc.addView(prow2)

        // ক্যামেরা বোতাম (শুধু ক্যামেরা মোডে দেখায়)
        camRow = LinearLayout(this).apply { visibility = View.GONE }
        camRow.addView(btn("🔄 ক্যামেরা বদল", DARK) { switchCam() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 8, 6, 8) })
        micBtn = btn("🎤 মাইক চালু", DARK) { toggleMic() }
        camRow.addView(micBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 8, 6, 8) })
        pc.addView(camRow)
        ui.post(posTick)

        // দুই আঙুলে জুম
        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(zoom * d.scaleFactor); return true }
        })
        previewBox.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            sgd.onTouchEvent(e); true
        }

        // লোগো আঙুল দিয়ে টেনে সরানো
        var ox = 0f; var oy = 0f
        logoPrev.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ox = v.translationX - e.rawX; oy = v.translationY - e.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (previewBox.width - v.width).toFloat().coerceAtLeast(0f)
                    val maxY = (previewBox.height - v.height).toFloat().coerceAtLeast(0f)
                    val nx = (e.rawX + ox).coerceIn(0f, maxX)
                    val ny = (e.rawY + oy).coerceIn(0f, maxY)
                    v.translationX = nx; v.translationY = ny
                    logoXPct = nx * 100f / previewBox.width
                    logoYPct = ny * 100f / previewBox.height
                    logoFilter?.setPosition(logoXPct, logoYPct)
                }
            }
            true
        }
        updatePreviewSize()

        // ---------- লোগো কার্ড ----------
        val lc = card("")
        lc.addView(btn("লোগো আপলোড") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 2)
        })
        val logoVal = tv("${logoPx}px")
        lc.addView(tv("\nলোগো সাইজ"))
        lc.addView(seek(48, 240, logoPx) { v -> logoPx = v; logoVal.text = "${v}px"; setLogoSize(v) })
        lc.addView(logoVal)
        lc.addView(tv("লোগো সরাতে প্রিভিউতে লোগোর ওপর আঙুল রেখে টানুন", 12f).apply { setTextColor(Color.GRAY) })

        // ---------- টোকেন কার্ড ----------
        val tc = card("টোকেন ও পেইজ ম্যানেজার")
        tc.addView(tv("\nপেইজ আইডি", 13f)); tc.addView(pageId)
        tc.addView(tv("\nলং-লিভড টোকেন", 13f)); tc.addView(longTok)
        tc.addView(tv("\nশর্ট-লিভড টোকেন", 13f)); tc.addView(shortTok)
        tc.addView(gap())
        tc.addView(btn("পেইজ আনয়ন") { fetchPage() })

        // ---------- স্টুডিও কন্ট্রোল ----------
        val sc = card("স্টুডিও কন্ট্রোল")
        sc.addView(tv("\nলাইভের উৎস", 13f))
        sc.addView(toggles(listOf("🎞 ভিডিও ফাইল", "📷 ফোন ক্যামেরা"), 0, 2) { setSource(it == 1) })
        sc.addView(tv("\nভিডিও উৎস (ফোনের ফাইল ম্যানেজার থেকে)", 13f))
        sc.addView(btn("ভিডিও নির্বাচন") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 1)
        })

        sc.addView(tv("\nফ্রেম / অনুপাত", 13f))
        val ratios = listOf(0f, 16f / 9f, 9f / 16f, 4f / 3f, 3f / 4f, 4f / 5f, 1f)
        sc.addView(toggles(listOf("মূল", "16:9", "9:16", "4:3", "3:4", "4:5", "1:1"), 0, 4) { setFrame(ratios[it]) })
        frameInfo = tv("ফ্রেম: মূল    জুম: ১.০x", 13f)
        sc.addView(frameInfo)
        sc.addView(btn("জুম রিসেট", DARK) { setZoom(1f) })

        sc.addView(tv("\nকোয়ালিটি", 13f))
        val qs = listOf(600_000, 1_500_000, 3_000_000)
        sc.addView(toggles(listOf("360p", "720p", "1080p"), 1, 3) { bitrate = qs[it]; qIdx = it })

        listenCb = CheckBox(this).apply {
            text = "লাইভের সময় ফোনেও শব্দ শুনব (ভিডিও ফাইল)"; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("listen", true)
        }
        sc.addView(listenCb)

        sc.addView(tv("\nটাইটেল টেক্সট", 13f))
        sc.addView(title)

        sc.addView(tv("\nক্যাপশন (বাংলায়)", 13f))
        capText = et("ক্যাপশন লিখুন (বাংলায়)", prefs.getString("cap", "") ?: "")
        capColor = et("লেখার রঙ, যেমন #FFFFFF", prefs.getString("capc", "#FFFFFF") ?: "#FFFFFF")
        capBg = et("ব্যাকগ্রাউন্ড রঙ, যেমন #B91C1C", prefs.getString("capb", "#B91C1C") ?: "#B91C1C")
        sc.addView(capText); sc.addView(gap(8)); sc.addView(capColor); sc.addView(gap(8)); sc.addView(capBg)
        sc.addView(gap(8))
        sc.addView(btn("ক্যাপশন প্রয়োগ করুন") { applyCaption() })

        sc.addView(tv("\nদিক", 13f))
        sc.addView(toggles(listOf("ডান → বাম", "বাম → ডান"), if (dirRtl) 0 else 1, 2) {
            dirRtl = (it == 0)
            save(); refreshPreviewTicker()
            if (live) resetCaptionPos()
        })

        val spVal = tv("${speedSec}s")
        sc.addView(tv("গতি (সেকেন্ড)", 13f))
        sc.addView(seek(5, 40, speedSec) { v -> speedSec = v; spVal.text = "${v}s"; refreshPreviewTicker() })
        sc.addView(spVal)

        sc.addView(gap(20))
        sc.addView(timer)
        liveBtn = btn("লাইভ চালু করুন", 0xFFEF4444.toInt()) { if (live) stopLive() else startLive() }
        sc.addView(liveBtn)

        // ---------- আমার পেজ ----------
        val mc = card("আমার পেজ")
        pageLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        pageName = tv("—", 18f); pageFans = tv("")
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prow.addView(pageLogo, LinearLayout.LayoutParams(140, 140))
        val pcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 0, 0) }
        pcol.addView(pageName); pcol.addView(pageFans)
        prow.addView(pcol)
        mc.addView(prow)
        statsView = tv("👁 0    👍 0    💬 0", 16f)
        commentsView = tv("").apply { setTextColor(0xFF9CA3AF.toInt()) }
        mc.addView(statsView); mc.addView(commentsView)

        // সেভ করা লোগো লোড
        val lf = File(filesDir, "logo.png")
        if (lf.exists()) { logoBmp = BitmapFactory.decodeFile(lf.path); logoBmp?.let { logoPrev.setImageBitmap(it) } }
        refreshPreviewTicker()

        if (longTok.text.isNotBlank() || shortTok.text.isNotBlank()) loadPageInfo(false)
    }

    // ---------- ক্যামেরা ----------
    private fun hasPerm() =
        checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED

    private fun askPerm() {
        requestPermissions(arrayOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"), 11)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 11) {
            if (hasPerm()) { if (srcCamera) ensureCam() }
            else toast("ক্যামেরা ও মাইকের অনুমতি না দিলে ক্যামেরা চলবে না")
        }
    }

    private fun setSource(camMode: Boolean) {
        if (live) return toast("লাইভ চলার সময় উৎস বদলানো যাবে না")
        srcCamera = camMode
        preview.visibility = if (camMode) View.GONE else View.VISIBLE
        glView.visibility = if (camMode) View.VISIBLE else View.GONE
        seekBar.visibility = if (camMode) View.GONE else View.VISIBLE
        playBtn.visibility = if (camMode) View.GONE else View.VISIBLE
        soundBtn.visibility = if (camMode) View.GONE else View.VISIBLE
        videoName.visibility = if (camMode) View.GONE else View.VISIBLE
        videoDur.visibility = if (camMode) View.GONE else View.VISIBLE
        camRow.visibility = if (camMode) View.VISIBLE else View.GONE
        videoAspect = if (camMode) 9f / 16f else fileAspect
        if (camMode) {
            try { if (preview.isPlaying) preview.pause() } catch (_: Exception) {}
            playBtn.text = "▶ প্লে"
            if (!hasPerm()) askPerm() else ensureCam()
        } else {
            stopCamPreview()
        }
        updatePreviewSize()
    }

    private fun ensureCam() {
        glView.post {
            if (cam == null) cam = RtmpCamera2(glView, this)
            if (surfaceReady) startCamPreview()
        }
    }

    private fun startCamPreview() {
        val c = cam ?: return
        if (!hasPerm()) return
        try { if (!c.isOnPreview) c.startPreview(CameraHelper.Facing.BACK, 1280, 720) }
        catch (e: Exception) { toast("ক্যামেরা চালু হয়নি: ${e.message}") }
    }

    private fun stopCamPreview() {
        try { if (cam?.isOnPreview == true) cam?.stopPreview() } catch (_: Exception) {}
    }

    private fun switchCam() {
        try { cam?.switchCamera() } catch (e: Exception) { toast("ক্যামেরা বদল হয়নি: ${e.message}") }
    }

    private fun toggleMic() {
        micOn = !micOn
        try { if (micOn) cam?.enableAudio() else cam?.disableAudio() } catch (_: Exception) {}
        micBtn.text = if (micOn) "🎤 মাইক চালু" else "🔇 মাইক বন্ধ"
    }

    // ---------- অটো রোটেট আইকন, ফুলস্ক্রিন ----------
    private fun toggleAuto() {
        autoRot = !autoRot
        requestedOrientation = if (autoRot) ActivityInfo.SCREEN_ORIENTATION_SENSOR else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        rotBtn.text = if (autoRot) "🔄" else "🔒"
        toast(if (autoRot) "অটো রোটেট চালু: ফোন আড়াআড়ি ধরলে ফুলস্ক্রিন হবে" else "পোর্ট্রেটে আটকানো")
    }

    private fun applyFull(on: Boolean) {
        full = on
        for (i in 0 until col.childCount) {
            col.getChildAt(i).visibility = if (on && col.getChildAt(i) !== pcCard) View.GONE else View.VISIBLE
        }
        videoName.visibility = if (on || srcCamera) View.GONE else View.VISIBLE
        col.setPadding(if (on) 0 else 36, if (on) 0 else 60, if (on) 0 else 36, if (on) 0 else 60)
        fullBtn.text = if (on) "⛶ ফুলস্ক্রিন বন্ধ" else "⛶ ফুলস্ক্রিন"
        window.decorView.systemUiVisibility = if (on)
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) else 0
        updatePreviewSize()
    }

    private fun toggleFull() {
        if (!full) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            applyFull(true)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            applyFull(false)
            if (autoRot) ui.postDelayed({ requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR }, 1500)
        }
    }

    override fun onBackPressed() { if (full) toggleFull() else super.onBackPressed() }

    override fun onPause() { super.onPause(); save() }

    // ফোন ঘুরলে (অটো রোটেট আইকন 🔄 থাকলে) নিজে ফুলস্ক্রিন হয়
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val land = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land != full) applyFull(land) else updatePreviewSize()
        refreshPreviewTicker()
    }

    // ---------- প্রিভিউয়ের মাপ ও ফ্রেমের বার ----------
    private fun updatePreviewSize() {
        val w = resources.displayMetrics.widthPixels - 144
        val maxH = if (full) resources.displayMetrics.heightPixels - 300 else (resources.displayMetrics.heightPixels * 0.7f).toInt()
        var h = (w / videoAspect).toInt()
        var ww = -1
        if (h > maxH) { h = maxH; ww = (h * videoAspect).toInt() }
        previewBox.layoutParams = LinearLayout.LayoutParams(ww, h).apply { gravity = Gravity.CENTER_HORIZONTAL }
        previewBox.post { updateBars(); placeLogoPreview() }
    }

    private fun placeLogoPreview() {
        logoPrev.translationX = logoXPct * previewBox.width / 100f
        logoPrev.translationY = logoYPct * previewBox.height / 100f
    }

    // (পাশের বার %, ওপর-নিচের বার %)
    private fun barPcts(va: Float): Pair<Float, Float> {
        if (frameAspect <= 0f) return 0f to 0f
        return if (frameAspect < va) ((1f - frameAspect / va) / 2f * 100f) to 0f
        else 0f to ((1f - va / frameAspect) / 2f * 100f)
    }

    private fun updateBars() {
        val (sp, tp) = barPcts(videoAspect)
        val bw = (previewBox.width * sp / 100f).toInt().coerceAtLeast(1)
        val bh = (previewBox.height * tp / 100f).toInt().coerceAtLeast(1)
        barLeftV.layoutParams = FrameLayout.LayoutParams(bw, -1, Gravity.START)
        barRightV.layoutParams = FrameLayout.LayoutParams(bw, -1, Gravity.END)
        barTopV.layoutParams = FrameLayout.LayoutParams(-1, bh, Gravity.TOP)
        barBotV.layoutParams = FrameLayout.LayoutParams(-1, bh, Gravity.BOTTOM)
        barLeftV.visibility = if (sp > 0f) View.VISIBLE else View.GONE
        barRightV.visibility = if (sp > 0f) View.VISIBLE else View.GONE
        barTopV.visibility = if (tp > 0f) View.VISIBLE else View.GONE
        barBotV.visibility = if (tp > 0f) View.VISIBLE else View.GONE
    }

    // ---------- প্লেয়ার ----------
    private fun fmtMs(ms: Int): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private val posTick = object : Runnable { override fun run() {
        try {
            if (!srcCamera && durTotal != "--:--" && !seeking) {
                val p = preview.currentPosition
                videoDur.text = "সময়: ${fmtMs(p)} / $durTotal মিনিট"
                seekBar.progress = p
            }
        } catch (_: Exception) {}
        ui.postDelayed(this, 500) } }

    private fun togglePlay() {
        if (videoUri == null) return toast("আগে ভিডিও নির্বাচন করুন")
        if (preview.isPlaying) { preview.pause(); playBtn.text = "▶ প্লে" }
        else { preview.start(); playBtn.text = "⏸ পজ" }
    }

    private fun applySound() {
        val v = if (previewSound) 1f else 0f
        try { mp?.setVolume(v, v) } catch (_: Exception) {}
        soundBtn.text = if (previewSound) "🔊 শব্দ চালু" else "🔇 শব্দ বন্ধ"
    }

    // লাইভ চলাকালে স্ট্রিমও ওই মিনিটে নেওয়ার চেষ্টা (লাইব্রেরির moveTo ফাংশন থাকলে)
    private fun seekStream(ms: Int) {
        val g = gen ?: return
        try {
            val m = g.javaClass.methods.firstOrNull { it.name == "moveTo" && it.parameterTypes.size == 1 } ?: return
            val sec = ms / 1000.0
            when (m.parameterTypes[0]) {
                java.lang.Double.TYPE -> m.invoke(g, sec)
                java.lang.Float.TYPE -> m.invoke(g, sec.toFloat())
                java.lang.Integer.TYPE -> m.invoke(g, (ms / 1000))
                else -> {}
            }
        } catch (_: Exception) {}
    }

    private fun loadVideoMeta(uri: Uri) {
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            mmr.release()
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            if (w > 0 && h > 0) { videoAspect = w.toFloat() / h; fileAspect = videoAspect }
        } catch (_: Exception) {}
        durTotal = if (durMs > 0) fmtMs(durMs) else "--:--"
        seekBar.max = if (durMs > 0) durMs else 1
        seekBar.progress = 0
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 1 && c == RESULT_OK && d?.data != null) {
            videoUri = d.data
            contentResolver.takePersistableUriPermission(d.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            videoName.text = "নির্বাচিত: " + d.data!!.lastPathSegment
            loadVideoMeta(d.data!!)
            videoDur.text = "সময়: 0:00 / $durTotal মিনিট"
            updatePreviewSize()
            preview.setVideoURI(d.data)
            preview.setOnPreparedListener { m -> mp = m; m.isLooping = true; applySound() }
            preview.start(); playBtn.text = "⏸ পজ"
        }
        if (r == 2 && c == RESULT_OK && d?.data != null) saveLogo(d.data!!)
    }

    private fun saveLogo(uri: Uri) {
        try {
            val bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: return toast("লোগো পড়া যায়নি")
            val scaled = if (bmp.width > 600) Bitmap.createScaledBitmap(bmp, 600, bmp.height * 600 / bmp.width, true) else bmp
            File(filesDir, "logo.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            logoBmp = scaled; logoPrev.setImageBitmap(scaled)
            toast("লোগো সেট হয়েছে (পরের লাইভ থেকে স্ট্রিমে দেখাবে)")
        } catch (e: Exception) { toast("ত্রুটি: ${e.message}") }
    }

    // লোগো সাইজ: প্রিভিউ ও (লাইভ চললে) স্ট্রিম দুটোতেই বদলায়
    private fun setLogoSize(px: Int) {
        logoPrev.layoutParams = FrameLayout.LayoutParams(px, -2, Gravity.TOP or Gravity.START)
        val f = logoFilter; val bmp = logoBmp
        if (f != null && bmp != null) {
            val sx = logoScalePct()
            f.setScale(sx, sx * streamW / streamH * bmp.height / bmp.width)
        }
    }

    private fun logoScalePct(): Float {
        val w = if (previewBox.width > 0) previewBox.width else 1000
        return (logoPx * 100f / w).coerceIn(4f, 40f)
    }

    // ---------- ফ্রেম ও জুম ----------
    private fun setFrame(ratio: Float) {
        frameAspect = ratio
        updateBars()
        applyFrameStream()
        updateFrameInfo()
    }

    private fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, 4f)
        preview.scaleX = zoom; preview.scaleY = zoom
        glView.scaleX = zoom; glView.scaleY = zoom
        applyZoomStream()
        updateFrameInfo()
    }

    private fun frameName(): String = when {
        frameAspect <= 0f -> "মূল"
        Math.abs(frameAspect - 16f / 9f) < 0.01f -> "16:9"
        Math.abs(frameAspect - 9f / 16f) < 0.01f -> "9:16"
        Math.abs(frameAspect - 4f / 3f) < 0.01f -> "4:3"
        Math.abs(frameAspect - 3f / 4f) < 0.01f -> "3:4"
        Math.abs(frameAspect - 4f / 5f) < 0.01f -> "4:5"
        else -> "1:1"
    }

    private fun updateFrameInfo() {
        frameInfo.text = "ফ্রেম: " + frameName() + "    জুম: " + "%.1f".format(zoom) + "x"
    }

    private fun applyZoomStream() {
        val f = cropFilter ?: return
        val z = if (zoom < 1.02f) 1.02f else zoom
        val side = 100f / z
        f.setCropArea((100f - side) / 2f, (100f - side) / 2f, side, side)
    }

    // স্ট্রিমে ফ্রেমের চার পাশের কালো বার, লোগো ও মিনিটের জায়গা
    private fun applyFrameStream() {
        val (sp, tp) = barPcts(streamW.toFloat() / streamH)
        val s = sp.coerceAtLeast(0.1f); val t = tp.coerceAtLeast(0.1f)
        barL?.let { it.setScale(s, 100f); it.setPosition(0f, 0f); it.setAlpha(if (sp > 0f) 1f else 0f) }
        barR?.let { it.setScale(s, 100f); it.setPosition(100f - s, 0f); it.setAlpha(if (sp > 0f) 1f else 0f) }
        barT?.let { it.setScale(100f, t); it.setPosition(0f, 0f); it.setAlpha(if (tp > 0f) 1f else 0f) }
        barB?.let { it.setScale(100f, t); it.setPosition(0f, 100f - t); it.setAlpha(if (tp > 0f) 1f else 0f) }
        logoFilter?.setPosition(logoXPct, logoYPct)
        val tf = timeFilter
        if (tf != null) tf.setPosition(100f - sp - tf.scale.x - 1f, tp + 2f)
        capFilter?.let { capY = 100f - tp - it.scale.y - 2f }
    }

    // প্রিভিউতে ক্যাপশন চলে (দিক ও গতি অনুযায়ী)
    private fun refreshPreviewTicker() {
        tAnim?.cancel()
        val t = capText.text.toString()
        ticker.text = t
        ticker.setTextColor(colr(capColor.text.toString(), Color.WHITE))
        ticker.setBackgroundColor(colr(capBg.text.toString(), 0xFFB91C1C.toInt()))
        ticker.visibility = if (t.isBlank()) View.INVISIBLE else View.VISIBLE
        if (t.isBlank()) return
        capBox.post {
            val tw = ticker.paint.measureText(t) + 40f
            val cw = capBox.width.toFloat()
            val from = if (dirRtl) cw else -tw
            val to = if (dirRtl) -tw else cw
            tAnim = ValueAnimator.ofFloat(from, to).apply {
                duration = speedSec * 1000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { ticker.translationX = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun applyCaption() {
        save(); refreshPreviewTicker()
        if (live) applyCaptionStream()
        toast("ক্যাপশন প্রয়োগ হয়েছে")
    }

    private fun resetCaptionPos() {
        val f = capFilter ?: return
        capX = if (dirRtl) 100f else -f.scale.x
    }

    private fun applyCaptionStream() {
        val gl = glI ?: return
        val t = capText.text.toString()
        if (t.isBlank()) return
        var f = capFilter
        if (f == null) { f = TextObjectFilterRender(); gl.addFilter(f); capFilter = f }
        f.setText(t, streamH / 18f, colr(capColor.text.toString(), Color.WHITE), colr(capBg.text.toString(), 0xFFB91C1C.toInt()))
        f.setDefaultScale(streamW, streamH)
        val (_, tp) = barPcts(streamW.toFloat() / streamH)
        capY = 100f - tp - f.scale.y - 2f
        resetCaptionPos()
    }

    private fun setupOverlays(gl: GlInterface, sw: Int, sh: Int) {
        glI = gl
        streamW = sw
        streamH = sh

        val cf = CropFilterRender()
        gl.addFilter(cf); cropFilter = cf
        applyZoomStream()

        val black = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val bl = ImageObjectFilterRender(); gl.addFilter(bl); bl.setImage(black); barL = bl
        val br = ImageObjectFilterRender(); gl.addFilter(br); br.setImage(black); barR = br
        val bt = ImageObjectFilterRender(); gl.addFilter(bt); bt.setImage(black); barT = bt
        val bb = ImageObjectFilterRender(); gl.addFilter(bb); bb.setImage(black); barB = bb

        logoBmp?.let { bmp ->
            val f = ImageObjectFilterRender()
            gl.addFilter(f); f.setImage(bmp)
            val sx = logoScalePct()
            f.setScale(sx, sx * streamW / streamH * bmp.height / bmp.width)
            f.setPosition(logoXPct, logoYPct)
            logoFilter = f
        }
        val tf = TextObjectFilterRender()
        gl.addFilter(tf)
        tf.setText("00:00", streamH / 20f, Color.WHITE, 0xAA000000.toInt())
        tf.setDefaultScale(streamW, streamH)
        timeFilter = tf
        applyFrameStream()
        applyCaptionStream()
    }

    private fun toast(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }
    private fun setStatus(s: String) { ui.post { status.text = s } }

    private fun http(method: String, url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 20000
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return s.bufferedReader().readText().also { if (c.responseCode !in 200..299) throw Exception(it) }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun save() = prefs.edit().putString("pid", pageId.text.toString()).putString("ltok", longTok.text.toString())
        .putString("stok", shortTok.text.toString()).putString("title", title.text.toString())
        .putString("cap", capText.text.toString()).putString("capc", capColor.text.toString())
        .putString("capb", capBg.text.toString()).putBoolean("listen", listenCb.isChecked)
        .putInt("logopx", logoPx).putFloat("lx", logoXPct).putFloat("ly", logoYPct)
        .putInt("speed", speedSec).putBoolean("rtl", dirRtl).apply()

    private fun resolvePage(): Pair<String, String> {
        val tok = longTok.text.toString().ifBlank { shortTok.text.toString() }
        val pages = JSONObject(http("GET", "https://graph.facebook.com/v19.0/me/accounts?access_token=${enc(tok)}")).getJSONArray("data")
        val want = pageId.text.toString().trim()
        for (i in 0 until pages.length()) {
            val p = pages.getJSONObject(i)
            if (want.isEmpty() || p.getString("id") == want) return p.getString("id") to p.getString("access_token")
        }
        throw Exception("পেইজ পাওয়া যায়নি")
    }

    private fun fetchPage() = loadPageInfo(true)

    private fun loadPageInfo(showError: Boolean) {
        Thread {
            try {
                val (id, tok) = try { resolvePage() } catch (e: Exception) {
                    val want = pageId.text.toString().trim()
                    if (want.isEmpty()) throw e
                    want to longTok.text.toString().ifBlank { shortTok.text.toString() }
                }
                pageToken = tok
                val j = JSONObject(http("GET", "https://graph.facebook.com/v19.0/$id?fields=name,fan_count,followers_count&access_token=${enc(tok)}"))
                val name = j.optString("name")
                val fans = if (j.has("followers_count")) j.optLong("followers_count") else j.optLong("fan_count")
                var bmp: Bitmap? = null
                try {
                    val c = URL("https://graph.facebook.com/v19.0/$id/picture?type=large").openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = true
                    bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                } catch (_: Exception) {}
                ui.post {
                    pageId.setText(id); pageName.text = name; pageFans.text = "ফলোয়ার: $fans"
                    bmp?.let { pageLogo.setImageBitmap(it) }
                }
                save()
                if (showError) toast("পেইজ আইডি পাওয়া গেছে")
            } catch (e: Exception) { if (showError) toast("ত্রুটি: ${e.message}") }
        }.start()
    }

    private fun startSvc() {
        try {
            val i = Intent(this, LiveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) { toast("সার্ভিস চালু হয়নি: ${e.message}") }
    }
    private fun stopSvc() { try { stopService(Intent(this, LiveService::class.java)) } catch (_: Exception) {} }

    private fun startLive() {
        if (srcCamera) {
            if (!hasPerm()) { askPerm(); return toast("ক্যামেরা ও মাইকের অনুমতি দিন") }
            if (cam == null) return toast("ক্যামেরা প্রস্তুত নয়, একটু অপেক্ষা করুন")
        } else if (videoUri == null) return toast("আগে ভিডিও নির্বাচন করুন")
        val uri = videoUri
        save(); setStatus("সংযোগ হচ্ছে..."); startSvc()
        Thread {
            try {
                val (id, tok) = try { resolvePage() } catch (e: Exception) {
                    pageId.text.toString() to longTok.text.toString().ifBlank { shortTok.text.toString() }
                }
                pageToken = tok
                val j = JSONObject(http("POST", "https://graph.facebook.com/v19.0/$id/live_videos",
                    "status=LIVE_NOW&title=${enc(title.text.toString())}&access_token=${enc(tok)}"))
                liveId = j.getString("id")
                val url = j.getString("secure_stream_url")
                ui.post { if (srcCamera) startCamStream(url) else startFileStream(uri!!, url) }
            } catch (e: Exception) { setStatus("● অফলাইন"); toast("ত্রুটি: ${e.message}"); stopSvc() }
        }.start()
    }

    private fun startFileStream(uri: Uri, url: String) {
        val g = RtmpFromFile(this, this, this, this); gen = g
        pfdV = contentResolver.openFileDescriptor(uri, "r"); pfdA = contentResolver.openFileDescriptor(uri, "r")
        if (g.prepareVideo(pfdV!!.fileDescriptor, bitrate, 0) && g.prepareAudio(pfdA!!.fileDescriptor, 128 * 1024)) {
            val sw = if (g.streamWidth > 0) g.streamWidth else 1280
            val sh = if (g.streamHeight > 0) g.streamHeight else 720
            // ওভারলে এখন জোড়া হবে না। সংযোগ সফল হলে (onConnectionSuccess) জোড়া হবে
            pendingGl = g.glInterface; pendingW = sw; pendingH = sh
            if (listenCb.isChecked) {
                previewSound = false; applySound()
                try { g.playAudioDevice() } catch (_: Exception) {}
            }
            g.startStream(url)
        } else { toast("ভিডিও প্রস্তুত করা যায়নি"); endFb(); stopSvc() }
    }

    private fun startCamStream(url: String) {
        val c = cam
        if (c == null) { toast("ক্যামেরা প্রস্তুত নয়"); endFb(); stopSvc(); return }
        val (w, h) = qWH[qIdx]
        val rot = CameraHelper.getCameraOrientation(this)
        val portrait = rot == 90 || rot == 270
        if (c.prepareAudio() && c.prepareVideo(w, h, bitrate)) {
            val sw = if (portrait) h else w
            val sh = if (portrait) w else h
            // ওভারলে এখন জোড়া হবে না। সংযোগ সফল হলে (onConnectionSuccess) জোড়া হবে
            pendingGl = c.glInterface; pendingW = sw; pendingH = sh
            if (!micOn) { try { c.disableAudio() } catch (_: Exception) {} }
            c.startStream(url)
        } else { toast("ক্যামেরা প্রস্তুত করা যায়নি"); endFb(); stopSvc() }
    }

    private fun endFb() { val id = liveId ?: return; liveId = null
        Thread { try { http("POST", "https://graph.facebook.com/v19.0/$id", "end_live_video=true&access_token=${enc(pageToken)}") } catch (_: Exception) {} }.start() }

    private fun stopLive() {
        try { gen?.stopAudioDevice() } catch (_: Exception) {}
        try { gen?.stopStream() } catch (_: Exception) {}
        gen = null
        try { if (cam?.isStreaming == true) cam?.stopStream() } catch (_: Exception) {}
        endFb(); live = false
        ui.removeCallbacks(statsTick); ui.removeCallbacks(capTick)
        capFilter = null; logoFilter = null; timeFilter = null; cropFilter = null; glI = null
        barL = null; barR = null; barT = null; barB = null
        pendingGl = null
        stopSvc()
        setStatus("● অফলাইন"); liveBtn.text = "লাইভ চালু করুন"
    }

    private val tick = object : Runnable { override fun run() {
        if (!live) return
        val s = (System.currentTimeMillis() - startMs) / 1000
        val txt = "%02d:%02d".format(s / 60, s % 60)
        timer.text = txt
        timeFilter?.setText(txt, streamH / 20f, Color.WHITE, 0xAA000000.toInt())
        ui.postDelayed(this, 1000) } }

    private val capTick = object : Runnable { override fun run() {
        if (!live) return
        val f = capFilter
        if (f != null) {
            val step = (100f + f.scale.x) / (speedSec * 25f)
            if (dirRtl) {
                capX -= step
                if (capX < -f.scale.x) capX = 100f
            } else {
                capX += step
                if (capX > 100f) capX = -f.scale.x
            }
            f.setPosition(capX, capY)
        }
        ui.postDelayed(this, 40) } }

    private val statsTick = object : Runnable { override fun run() {
        val id = liveId
        if (!live || id == null) return
        Thread {
            try {
                val fields = "live_views,reactions.summary(true).limit(0)," +
                    "comments.live_filter(no_filter).order(reverse_chronological).summary(true).limit(5){from,message}"
                val j = JSONObject(http("GET", "https://graph.facebook.com/v19.0/$id?fields=${enc(fields)}&access_token=${enc(pageToken)}"))
                val views = j.optLong("live_views", 0L)
                val likes = j.optJSONObject("reactions")?.optJSONObject("summary")?.optLong("total_count", 0L) ?: 0L
                val cm = j.optJSONObject("comments")
                val cnt = cm?.optJSONObject("summary")?.optLong("total_count", 0L) ?: 0L
                val arr = cm?.optJSONArray("data")
                val sb = StringBuilder()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val who = o.optJSONObject("from")?.optString("name") ?: "দর্শক"
                    sb.append(who).append(": ").append(o.optString("message")).append('\n')
                }
                ui.post { statsView.text = "👁 $views    👍 $likes    💬 $cnt"; commentsView.text = sb.toString() }
            } catch (e: Exception) {
                ui.post { statsView.text = "পরিসংখ্যান আনা যায়নি" }
            }
        }.start()
        ui.postDelayed(this, 10000) } }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { ui.post {
        live = true; startMs = System.currentTimeMillis(); setStatus("🔴 লাইভ"); liveBtn.text = "লাইভ বন্ধ করুন"
        tick.run(); statsTick.run(); capTick.run()
        // স্ট্রিম চালু হওয়ার একটু পরে ওভারলে জোড়া হয়
        ui.postDelayed({
            val g = pendingGl
            if (live && g != null) {
                try { setupOverlays(g, pendingW, pendingH) } catch (e: Exception) { toast("ওভারলে ত্রুটি: ${e.message}") }
            }
        }, 700)
    } }
    override fun onConnectionFailed(reason: String) { toast("সংযোগ ব্যর্থ: $reason"); ui.post { stopLive() } }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() { toast("অথ ত্রুটি") }
    override fun onAuthSuccess() {}
    override fun onVideoDecoderFinished() { ui.post { if (live) stopLive() } }
    override fun onAudioDecoderFinished() {}
}
