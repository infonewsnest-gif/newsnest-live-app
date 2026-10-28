package bd.newsnest.live

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
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
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.encoder.input.gl.render.filters.CropFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.library.rtmp.RtmpFromFile
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
    private var liveId: String? = null
    private var pageToken = ""
    private var live = false
    private var startMs = 0L
    private var bitrate = 1_500_000

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
    private var mp: MediaPlayer? = null
    private var previewSound = true
    private var durTotal = "--:--"
    private lateinit var timer: TextView
    private lateinit var liveBtn: Button
    private lateinit var preview: VideoView
    private lateinit var pageLogo: ImageView
    private lateinit var pageName: TextView
    private lateinit var pageFans: TextView
    private lateinit var statsView: TextView
    private lateinit var commentsView: TextView

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
    private var speedSec = 18
    private var dirRtl = true   // true = ডান → বাম

    // ফ্রেম, জুম, শব্দ
    private lateinit var previewBox: FrameLayout
    private lateinit var barLeftV: View
    private lateinit var barRightV: View
    private lateinit var frameInfo: TextView
    private lateinit var listenCb: CheckBox
    private var frame11 = false
    private var zoom = 1f
    private val barPct = 21.875f

    // স্ট্রিমের ওভারলে
    private var cropFilter: CropFilterRender? = null
    private var barL: ImageObjectFilterRender? = null
    private var barR: ImageObjectFilterRender? = null
    private var capFilter: TextObjectFilterRender? = null
    private var logoFilter: ImageObjectFilterRender? = null
    private var timeFilter: TextObjectFilterRender? = null
    private var streamW = 1280
    private var streamH = 720
    private var capX = 0f
    private var capY = 90f

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

    // কার্ড: গোল কোণ + হালকা বর্ডার
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

    // নির্বাচিত বোতাম সবুজ, বাকিগুলো কালো
    private fun toggles(labels: List<String>, init: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        val bs = mutableListOf<Button>()
        fun paint(sel: Int) { bs.forEachIndexed { i, b -> b.background = round(if (i == sel) GREEN else DARK, 24f) } }
        labels.forEachIndexed { i, l ->
            val b = Button(this).apply {
                text = l; setTextColor(Color.WHITE); isAllCaps = false
                setOnClickListener { paint(i); onPick(i) }
            }
            bs.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 8, 6, 8) })
        }
        paint(init)
        return row
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
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 60, 36, 60) }
        val sv = ScrollView(this).apply { setBackgroundColor(0xFF0B1020.toInt()); addView(col) }
        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 9) } catch (_: Exception) {}
        }

        logoPx = prefs.getInt("logopx", 96)
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

        // হেডার
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val headCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headCol.addView(tv("NewsNest Live", 24f))
        headCol.addView(tv("Facebook লাইভ স্ট্রিমিং স্টুডিও", 13f).apply { setTextColor(Color.GRAY) })
        head.addView(headCol, LinearLayout.LayoutParams(0, -2, 1f))
        status.background = round(0xFF1F2937.toInt(), 60f)
        status.setPadding(36, 16, 36, 16)
        head.addView(status)
        col.addView(head)

        // ---------- প্রিভিউ কার্ড ----------
        val pc = card("")
        previewBox = FrameLayout(this)
        preview = VideoView(this)
        previewBox.addView(preview, FrameLayout.LayoutParams(-1, 600))
        barLeftV = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        barRightV = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        previewBox.addView(barLeftV, FrameLayout.LayoutParams(10, 600, Gravity.START))
        previewBox.addView(barRightV, FrameLayout.LayoutParams(10, 600, Gravity.END))
        logoPrev = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        previewBox.addView(logoPrev, FrameLayout.LayoutParams(logoPx, logoPx, Gravity.TOP or Gravity.START).apply { setMargins(12, 12, 0, 0) })
        capBox = FrameLayout(this)
        ticker = TextView(this).apply { textSize = 18f; setSingleLine(true); setHorizontallyScrolling(true); setPadding(20, 6, 20, 6) }
        capBox.addView(ticker, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
        previewBox.addView(capBox, FrameLayout.LayoutParams(-1, 90, Gravity.BOTTOM))
        pc.addView(previewBox, LinearLayout.LayoutParams(-1, 600))
        pc.addView(videoName)
        pc.addView(videoDur)
        playBtn = btn("▶ প্লে", DARK) { togglePlay() }
        soundBtn = btn("🔊 শব্দ চালু", DARK) { previewSound = !previewSound; applySound() }
        val prow2 = LinearLayout(this)
        prow2.addView(playBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 8, 6, 8) })
        prow2.addView(soundBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(6, 8, 6, 8) })
        pc.addView(prow2)
        ui.post(posTick)

        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(zoom * d.scaleFactor); return true }
        })
        previewBox.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            sgd.onTouchEvent(e); true
        }

        // ---------- লোগো কার্ড ----------
        val lc = card("")
        lc.addView(btn("লোগো আপলোড") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 2)
        })
        val logoVal = tv("${logoPx}px")
        lc.addView(tv("\nলোগো সাইজ"))
        lc.addView(seek(48, 240, logoPx) { v -> logoPx = v; logoVal.text = "${v}px"; setLogoSize(v) })
        lc.addView(logoVal)

        // ---------- টোকেন কার্ড ----------
        val tc = card("টোকেন ও পেইজ ম্যানেজার")
        tc.addView(tv("\nপেইজ আইডি", 13f)); tc.addView(pageId)
        tc.addView(tv("\nলং-লিভড টোকেন", 13f)); tc.addView(longTok)
        tc.addView(tv("\nশর্ট-লিভড টোকেন", 13f)); tc.addView(shortTok)
        tc.addView(gap())
        tc.addView(btn("পেইজ আনয়ন") { fetchPage() })

        // ---------- স্টুডিও কন্ট্রোল ----------
        val sc = card("স্টুডিও কন্ট্রোল")
        sc.addView(tv("\nভিডিও উৎস (ফোনের ফাইল ম্যানেজার থেকে)", 13f))
        sc.addView(btn("ভিডিও নির্বাচন") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 1)
        })

        sc.addView(tv("\nঅনুপাত", 13f))
        sc.addView(toggles(listOf("16:9 ল্যান্ডস্কেপ", "1:1 স্কয়ার"), 0) { setFrame(it == 1) })
        frameInfo = tv("ফ্রেম: ১৬:৯    জুম: ১.০x", 13f)
        sc.addView(frameInfo)
        sc.addView(btn("জুম রিসেট", DARK) { setZoom(1f) })

        sc.addView(tv("\nকোয়ালিটি", 13f))
        val qs = listOf(600_000, 1_500_000, 3_000_000)
        sc.addView(toggles(listOf("360p", "720p", "1080p"), 1) { bitrate = qs[it] })

        listenCb = CheckBox(this).apply {
            text = "লাইভের সময় ফোনেও শব্দ শুনব"; setTextColor(Color.WHITE)
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
        sc.addView(toggles(listOf("ডান → বাম", "বাম → ডান"), if (dirRtl) 0 else 1) {
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

    override fun onPause() { super.onPause(); save() }

    private fun fmtMs(ms: Int): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private val posTick = object : Runnable { override fun run() {
        try { if (durTotal != "--:--") videoDur.text = "সময়: ${fmtMs(preview.currentPosition)} / $durTotal মিনিট" } catch (_: Exception) {}
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

    private fun videoDurationText(uri: Uri): String = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(this, uri)
        val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        mmr.release()
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    } catch (e: Exception) { "--:--" }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 1 && c == RESULT_OK && d?.data != null) {
            videoUri = d.data
            contentResolver.takePersistableUriPermission(d.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            videoName.text = "নির্বাচিত: " + d.data!!.lastPathSegment
            durTotal = videoDurationText(d.data!!)
            videoDur.text = "সময়: 0:00 / $durTotal মিনিট"
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
        logoPrev.layoutParams = FrameLayout.LayoutParams(px, px, Gravity.TOP or Gravity.START).apply { setMargins(12, 12, 0, 0) }
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
    private fun setFrame(square: Boolean) {
        frame11 = square
        val w = (previewBox.width * barPct / 100f).toInt().coerceAtLeast(1)
        barLeftV.layoutParams = FrameLayout.LayoutParams(w, 600, Gravity.START)
        barRightV.layoutParams = FrameLayout.LayoutParams(w, 600, Gravity.END)
        barLeftV.visibility = if (square) View.VISIBLE else View.GONE
        barRightV.visibility = if (square) View.VISIBLE else View.GONE
        applyFrameStream()
        updateFrameInfo()
    }

    private fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, 4f)
        preview.scaleX = zoom; preview.scaleY = zoom
        applyZoomStream()
        updateFrameInfo()
    }

    private fun updateFrameInfo() {
        frameInfo.text = "ফ্রেম: " + (if (frame11) "১:১" else "১৬:৯") + "    জুম: " + "%.1f".format(zoom) + "x"
    }

    private fun applyZoomStream() {
        val f = cropFilter ?: return
        val z = if (zoom < 1.02f) 1.02f else zoom
        val side = 100f / z
        f.setCropArea((100f - side) / 2f, (100f - side) / 2f, side, side)
    }

    private fun applyFrameStream() {
        barL?.setAlpha(if (frame11) 1f else 0f)
        barR?.setAlpha(if (frame11) 1f else 0f)
        logoFilter?.setPosition(if (frame11) barPct + 2f else 2f, 3f)
        val tf = timeFilter
        if (tf != null) {
            val right = if (frame11) 100f - barPct else 100f
            tf.setPosition(right - tf.scale.x - 1f, 2f)
        }
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
        val g = gen ?: return
        val t = capText.text.toString()
        if (t.isBlank()) return
        var f = capFilter
        if (f == null) { f = TextObjectFilterRender(); g.glInterface.addFilter(f); capFilter = f }
        f.setText(t, streamH / 18f, colr(capColor.text.toString(), Color.WHITE), colr(capBg.text.toString(), 0xFFB91C1C.toInt()))
        f.setDefaultScale(streamW, streamH)
        capY = 100f - f.scale.y - 2f
        resetCaptionPos()
    }

    private fun setupOverlays(g: RtmpFromFile) {
        val gl = g.glInterface
        streamW = if (g.streamWidth > 0) g.streamWidth else 1280
        streamH = if (g.streamHeight > 0) g.streamHeight else 720

        val cf = CropFilterRender()
        gl.addFilter(cf); cropFilter = cf
        applyZoomStream()

        val black = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val bl = ImageObjectFilterRender(); gl.addFilter(bl); bl.setImage(black)
        bl.setScale(barPct, 100f); bl.setPosition(0f, 0f); barL = bl
        val br = ImageObjectFilterRender(); gl.addFilter(br); br.setImage(black)
        br.setScale(barPct, 100f); br.setPosition(100f - barPct, 0f); barR = br

        logoBmp?.let { bmp ->
            val f = ImageObjectFilterRender()
            gl.addFilter(f); f.setImage(bmp)
            val sx = logoScalePct()
            f.setScale(sx, sx * streamW / streamH * bmp.height / bmp.width)
            f.setPosition(2f, 3f)
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
        .putInt("logopx", logoPx).putInt("speed", speedSec).putBoolean("rtl", dirRtl).apply()

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
        val uri = videoUri ?: return toast("আগে ভিডিও নির্বাচন করুন")
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
                ui.post {
                    val g = RtmpFromFile(this, this, this, this); gen = g
                    pfdV = contentResolver.openFileDescriptor(uri, "r"); pfdA = contentResolver.openFileDescriptor(uri, "r")
                    if (g.prepareVideo(pfdV!!.fileDescriptor, bitrate, 0) && g.prepareAudio(pfdA!!.fileDescriptor, 128 * 1024)) {
                        try { setupOverlays(g) } catch (e: Exception) { toast("ওভারলে ত্রুটি: ${e.message}") }
                        if (listenCb.isChecked) {
                            previewSound = false; applySound()
                            try { g.playAudioDevice() } catch (_: Exception) {}
                        }
                        g.startStream(url)
                    } else { toast("ভিডিও প্রস্তুত করা যায়নি"); endFb(); stopSvc() }
                }
            } catch (e: Exception) { setStatus("● অফলাইন"); toast("ত্রুটি: ${e.message}"); stopSvc() }
        }.start()
    }

    private fun endFb() { val id = liveId ?: return; liveId = null
        Thread { try { http("POST", "https://graph.facebook.com/v19.0/$id", "end_live_video=true&access_token=${enc(pageToken)}") } catch (_: Exception) {} }.start() }

    private fun stopLive() {
        try { gen?.stopAudioDevice() } catch (_: Exception) {}
        gen?.stopStream(); gen = null; endFb(); live = false
        ui.removeCallbacks(statsTick); ui.removeCallbacks(capTick)
        capFilter = null; logoFilter = null; timeFilter = null; cropFilter = null; barL = null; barR = null
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

    // স্ট্রিমের ক্যাপশন দিক ও গতি অনুযায়ী সরায় (প্রতি ৪০ms)
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
    override fun onConnectionSuccess() { ui.post { live = true; startMs = System.currentTimeMillis(); setStatus("🔴 লাইভ"); liveBtn.text = "লাইভ বন্ধ করুন"; tick.run(); statsTick.run(); capTick.run() } }
    override fun onConnectionFailed(reason: String) { toast("সংযোগ ব্যর্থ: $reason"); ui.post { stopLive() } }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() { toast("অথ ত্রুটি") }
    override fun onAuthSuccess() {}
    override fun onVideoDecoderFinished() { ui.post { if (live) stopLive() } }
    override fun onAudioDecoderFinished() {}
}
