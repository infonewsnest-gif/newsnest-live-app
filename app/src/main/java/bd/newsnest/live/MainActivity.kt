package bd.newsnest.live

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.*
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
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

    private lateinit var pageId: EditText
    private lateinit var longTok: EditText
    private lateinit var shortTok: EditText
    private lateinit var title: EditText
    private lateinit var status: TextView
    private lateinit var videoName: TextView
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

    // স্ট্রিমের ওভারলে
    private var capFilter: TextObjectFilterRender? = null
    private var logoFilter: ImageObjectFilterRender? = null
    private var timeFilter: TextObjectFilterRender? = null
    private var streamW = 1280
    private var streamH = 720
    private var capX = 0f
    private var capY = 90f

    private var pfdV: android.os.ParcelFileDescriptor? = null
    private var pfdA: android.os.ParcelFileDescriptor? = null

    private fun et(hint: String, v: String, pw: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(v); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun tv(t: String, size: Float = 14f) = TextView(this).apply { text = t; textSize = size; setTextColor(Color.WHITE) }
    private fun btn(t: String, c: Int = 0xFF10B981.toInt(), f: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); setBackgroundColor(c); setOnClickListener { f() }
    }
    private fun colr(s: String, d: Int) = try { Color.parseColor(s.trim()) } catch (_: Exception) { d }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        val sv = ScrollView(this).apply { setBackgroundColor(0xFF0B1020.toInt()); addView(col) }
        setContentView(sv)

        status = tv("● অফলাইন", 16f)
        videoName = tv("কোনো ভিডিও নির্বাচিত নয়")
        timer = tv("00:00", 28f)
        pageId = et("পেইজ আইডি", prefs.getString("pid", "") ?: "")
        longTok = et("লং-লিভড টোকেন", prefs.getString("ltok", "") ?: "", true)
        shortTok = et("শর্ট-লিভড টোকেন", prefs.getString("stok", "") ?: "", true)
        title = et("টাইটেল", prefs.getString("title", "NewsNest Live — সরাসরি সম্প্রচার") ?: "")

        col.addView(tv("NewsNest Live", 24f)); col.addView(status)
        col.addView(tv("\nটোকেন ও পেইজ ম্যানেজার", 18f))
        col.addView(pageId); col.addView(longTok); col.addView(shortTok)
        col.addView(btn("পেইজ আনয়ন") { fetchPage() })
        col.addView(tv("\nস্টুডিও কন্ট্রোল", 18f))
        col.addView(btn("ভিডিও নির্বাচন (গ্যালারি)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 1)
        })
        col.addView(videoName)

        // প্রিভিউ: ভিডিও + লোগো (ওপরে বাঁয়ে) + ক্যাপশন (নিচে)
        val box = FrameLayout(this)
        preview = VideoView(this)
        box.addView(preview, FrameLayout.LayoutParams(-1, 600))
        logoPrev = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        box.addView(logoPrev, FrameLayout.LayoutParams(130, 130, Gravity.TOP or Gravity.START).apply { setMargins(12, 12, 0, 0) })
        capBox = FrameLayout(this)
        ticker = TextView(this).apply { textSize = 18f; setSingleLine(true); setHorizontallyScrolling(true); setPadding(20, 6, 20, 6) }
        capBox.addView(ticker, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
        box.addView(capBox, FrameLayout.LayoutParams(-1, 90, Gravity.BOTTOM))
        col.addView(box, LinearLayout.LayoutParams(-1, 600))

        col.addView(tv("কোয়ালিটি"))
        val row = LinearLayout(this)
        listOf("360p" to 600_000, "720p" to 1_500_000, "1080p" to 3_000_000).forEach { (n, br) ->
            row.addView(btn(n, 0xFF1F2937.toInt()) { bitrate = br; toast("$n নির্বাচিত") }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(row)

        // লোগো ও ক্যাপশন সেটিং
        col.addView(tv("\nলোগো ও ক্যাপশন", 18f))
        col.addView(btn("লোগো নির্বাচন") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 2)
        })
        capText = et("ক্যাপশন লিখুন (বাংলায়)", prefs.getString("cap", "") ?: "")
        capColor = et("লেখার রঙ, যেমন #FFFFFF", prefs.getString("capc", "#FFFFFF") ?: "#FFFFFF")
        capBg = et("ব্যাকগ্রাউন্ড রঙ, যেমন #B91C1C", prefs.getString("capb", "#B91C1C") ?: "#B91C1C")
        col.addView(capText); col.addView(capColor); col.addView(capBg)
        col.addView(btn("ক্যাপশন প্রয়োগ করুন") { applyCaption() })

        col.addView(title); col.addView(timer)
        liveBtn = btn("লাইভ চালু করুন", 0xFFEF4444.toInt()) { if (live) stopLive() else startLive() }
        col.addView(liveBtn)

        // নিচে: আমার পেজ + লাইভ পরিসংখ্যান
        col.addView(tv("\nআমার পেজ", 18f))
        pageLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        pageName = tv("—", 18f); pageFans = tv("")
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prow.addView(pageLogo, LinearLayout.LayoutParams(140, 140))
        val pcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 0, 0) }
        pcol.addView(pageName); pcol.addView(pageFans)
        prow.addView(pcol)
        col.addView(prow)
        statsView = tv("👁 0    👍 0    💬 0", 16f)
        commentsView = tv("").apply { setTextColor(0xFF9CA3AF.toInt()) }
        col.addView(statsView); col.addView(commentsView)

        // সেভ করা লোগো লোড
        val lf = File(filesDir, "logo.png")
        if (lf.exists()) { logoBmp = BitmapFactory.decodeFile(lf.path); logoBmp?.let { logoPrev.setImageBitmap(it) } }
        refreshPreviewTicker()

        // সেভ করা টোকেন থাকলে নিজে থেকেই পেজ লোড
        if (longTok.text.isNotBlank() || shortTok.text.isNotBlank()) loadPageInfo(false)
    }

    override fun onPause() { super.onPause(); save() }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 1 && c == RESULT_OK && d?.data != null) {
            videoUri = d.data
            contentResolver.takePersistableUriPermission(d.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            videoName.text = "নির্বাচিত: " + d.data!!.lastPathSegment
            preview.setVideoURI(d.data); preview.setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f) }; preview.start()
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

    // প্রিভিউতে ক্যাপশন বাঁ থেকে ডানে চলে
    private fun refreshPreviewTicker() {
        tAnim?.cancel()
        val t = capText.text.toString()
        ticker.text = t
        ticker.setTextColor(colr(capColor.text.toString(), Color.WHITE))
        ticker.setBackgroundColor(colr(capBg.text.toString(), 0xFFB91C1C.toInt()))
        ticker.visibility = if (t.isBlank()) android.view.View.INVISIBLE else android.view.View.VISIBLE
        if (t.isBlank()) return
        capBox.post {
            val tw = ticker.paint.measureText(t) + 40f
            val cw = capBox.width.toFloat()
            tAnim = ValueAnimator.ofFloat(-tw, cw).apply {
                duration = ((tw + cw) * 10).toLong().coerceAtLeast(4000L)
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

    // স্ট্রিমের ভেতরে ক্যাপশন বসায় বা বদলায়
    private fun applyCaptionStream() {
        val g = gen ?: return
        val t = capText.text.toString()
        if (t.isBlank()) return
        var f = capFilter
        if (f == null) { f = TextObjectFilterRender(); g.glInterface.addFilter(f); capFilter = f }
        f.setText(t, streamH / 18f, colr(capColor.text.toString(), Color.WHITE), colr(capBg.text.toString(), 0xFFB91C1C.toInt()))
        f.setDefaultScale(streamW, streamH)
        capX = -f.scale.x
        capY = 100f - f.scale.y - 2f
    }

    // স্ট্রিমের ওপর লোগো ও মিনিট বসায়
    private fun setupOverlays(g: RtmpFromFile) {
        val gl = g.glInterface
        streamW = if (g.streamWidth > 0) g.streamWidth else 1280
        streamH = if (g.streamHeight > 0) g.streamHeight else 720
        logoBmp?.let { bmp ->
            val f = ImageObjectFilterRender()
            gl.addFilter(f); f.setImage(bmp)
            val sx = 14f
            f.setScale(sx, sx * streamW / streamH * bmp.height / bmp.width)
            f.setPosition(2f, 3f)
            logoFilter = f
        }
        val tf = TextObjectFilterRender()
        gl.addFilter(tf)
        tf.setText("00:00", streamH / 20f, Color.WHITE, 0xAA000000.toInt())
        tf.setDefaultScale(streamW, streamH)
        tf.setPosition(TranslateTo.TOP_RIGHT)
        timeFilter = tf
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
        .putString("capb", capBg.text.toString()).apply()

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

    private fun startLive() {
        val uri = videoUri ?: return toast("আগে ভিডিও নির্বাচন করুন")
        save(); setStatus("সংযোগ হচ্ছে...")
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
                        g.startStream(url)
                    } else { toast("ভিডিও প্রস্তুত করা যায়নি"); endFb() }
                }
            } catch (e: Exception) { setStatus("● অফলাইন"); toast("ত্রুটি: ${e.message}") }
        }.start()
    }

    private fun endFb() { val id = liveId ?: return; liveId = null
        Thread { try { http("POST", "https://graph.facebook.com/v19.0/$id", "end_live_video=true&access_token=${enc(pageToken)}") } catch (_: Exception) {} }.start() }

    private fun stopLive() {
        gen?.stopStream(); gen = null; endFb(); live = false
        ui.removeCallbacks(statsTick); ui.removeCallbacks(capTick)
        capFilter = null; logoFilter = null; timeFilter = null
        setStatus("● অফলাইন"); liveBtn.text = "লাইভ চালু করুন"
    }

    private val tick = object : Runnable { override fun run() {
        if (!live) return
        val s = (System.currentTimeMillis() - startMs) / 1000
        val txt = "%02d:%02d".format(s / 60, s % 60)
        timer.text = txt
        timeFilter?.setText(txt, streamH / 20f, Color.WHITE, 0xAA000000.toInt())
        ui.postDelayed(this, 1000) } }

    // স্ট্রিমের ক্যাপশন বাঁ থেকে ডানে সরায়
    private val capTick = object : Runnable { override fun run() {
        if (!live) return
        val f = capFilter
        if (f != null) {
            capX += 0.3f
            if (capX > 100f) capX = -f.scale.x
            f.setPosition(capX, capY)
        }
        ui.postDelayed(this, 40) } }

    // প্রতি ১০ সেকেন্ডে দর্শক, লাইক ও কমেন্ট আনে
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
