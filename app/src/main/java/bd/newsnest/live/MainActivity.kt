package bd.newsnest.live

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.rtmp.RtmpFromFile
import org.json.JSONObject
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

    private fun et(hint: String, v: String, pw: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(v); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun tv(t: String, size: Float = 14f) = TextView(this).apply { text = t; textSize = size; setTextColor(Color.WHITE) }
    private fun btn(t: String, c: Int = 0xFF10B981.toInt(), f: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); setBackgroundColor(c); setOnClickListener { f() }
    }

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
        col.addView(tv("কোয়ালিটি"))
        val row = LinearLayout(this)
        listOf("360p" to 600_000, "720p" to 1_500_000, "1080p" to 3_000_000).forEach { (n, br) ->
            row.addView(btn(n, 0xFF1F2937.toInt()) { bitrate = br; toast("$n নির্বাচিত") }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(row); col.addView(title); col.addView(timer)
        liveBtn = btn("লাইভ চালু করুন", 0xFFEF4444.toInt()) { if (live) stopLive() else startLive() }
        col.addView(liveBtn)
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 1 && c == RESULT_OK && d?.data != null) {
            videoUri = d.data
            contentResolver.takePersistableUriPermission(d.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            videoName.text = "নির্বাচিত: " + d.data!!.lastPathSegment
        }
    }

    private fun toast(s: String) = ui.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun setStatus(s: String) = ui.post { status.text = s }

    private fun http(method: String, url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 20000
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return s.bufferedReader().readText().also { if (c.responseCode !in 200..299) throw Exception(it) }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun save() = prefs.edit().putString("pid", pageId.text.toString()).putString("ltok", longTok.text.toString())
        .putString("stok", shortTok.text.toString()).putString("title", title.text.toString()).apply()

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

    private fun fetchPage() = Thread {
        try { val (id, _) = resolvePage(); ui.post { pageId.setText(id) }; save(); toast("পেইজ আইডি পাওয়া গেছে") }
        catch (e: Exception) { toast("ত্রুটি: ${e.message}") }
    }.start()

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
                    val g = RtmpFromFile(this, this, this); gen = g
                    if (g.prepareVideo(this, uri, bitrate) && g.prepareAudio(this, uri, 128 * 1024)) {
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
        setStatus("● অফলাইন"); liveBtn.text = "লাইভ চালু করুন"
    }

    private val tick = object : Runnable { override fun run() {
        if (!live) return
        val s = (System.currentTimeMillis() - startMs) / 1000
        timer.text = "%02d:%02d".format(s / 60, s % 60); ui.postDelayed(this, 1000) } }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { ui.post { live = true; startMs = System.currentTimeMillis(); setStatus("🔴 লাইভ"); liveBtn.text = "লাইভ বন্ধ করুন"; tick.run() } }
    override fun onConnectionFailed(reason: String) { toast("সংযোগ ব্যর্থ: $reason"); ui.post { stopLive() } }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {}
    override fun onAuthError() { toast("অথ ত্রুটি") }
    override fun onAuthSuccess() {}
    override fun onVideoDecoderFinished() { ui.post { if (live) stopLive() } }
    override fun onAudioDecoderFinished() {}
}
