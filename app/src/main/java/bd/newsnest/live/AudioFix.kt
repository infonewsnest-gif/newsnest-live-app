package bd.newsnest.live

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

// ভিডিওর শব্দ পরীক্ষা করে। শব্দ ধীর হওয়ার ঝুঁকি থাকলে (HE-AAC ইত্যাদি)
// শব্দটা সাধারণ AAC-LC তে নতুন করে বানিয়ে একটা সাময়িক ভিডিও ফাইল তৈরি করে।
// ভিডিওর ছবি বদলায় না, শুধু কপি হয়।
object AudioFix {

    class Info(val extSr: Int, val extCh: Int, val extBr: Int, val outSr: Int, val outCh: Int)

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith(prefix)) return i
        }
        return -1
    }

    private fun intOf(f: MediaFormat, key: String, def: Int): Int {
        return try { if (f.containsKey(key)) f.getInteger(key) else def } catch (_: Exception) { def }
    }

    // ফাইলের শব্দ আর ডিকোডারের আসল শব্দের হার/চ্যানেল মেলে কিনা দেখে
    fun probe(ctx: Context, uri: Uri): Info? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val ti = findTrack(ex, "audio/")
            if (ti < 0) return null
            ex.selectTrack(ti)
            val f = ex.getTrackFormat(ti)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: return null
            val esr = intOf(f, MediaFormat.KEY_SAMPLE_RATE, 44100)
            val ech = intOf(f, MediaFormat.KEY_CHANNEL_COUNT, 2)
            val ebr = intOf(f, MediaFormat.KEY_BIT_RATE, 0)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(f, null, null, 0)
            c.start()
            val bi = MediaCodec.BufferInfo()
            var inDone = false
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val b = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(bi, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || oi >= 0) {
                    val of = c.outputFormat
                    return Info(esr, ech, ebr, intOf(of, MediaFormat.KEY_SAMPLE_RATE, esr), intOf(of, MediaFormat.KEY_CHANNEL_COUNT, ech))
                }
            }
            return Info(esr, ech, ebr, esr, ech)
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    // শব্দ নতুন করে AAC-LC (১২৮ kbps) বানিয়ে ভিডিওসহ out ফাইলে লেখে
    fun fix(ctx: Context, uri: Uri, out: File, info: Info, onProgress: (Int) -> Unit): Boolean {
        val tmp = File(out.path + ".tmp")
        val aEx = MediaExtractor()
        val vEx = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var muxStarted = false
        try {
            if (tmp.exists()) tmp.delete()
            aEx.setDataSource(ctx, uri, null)
            vEx.setDataSource(ctx, uri, null)
            val at = findTrack(aEx, "audio/")
            if (at < 0) return false
            val vt = findTrack(vEx, "video/")
            aEx.selectTrack(at)
            val af = aEx.getTrackFormat(at)
            val durUs = try { if (af.containsKey(MediaFormat.KEY_DURATION)) af.getLong(MediaFormat.KEY_DURATION) else 0L } catch (_: Exception) { 0L }
            val vf: MediaFormat? = if (vt >= 0) { vEx.selectTrack(vt); vEx.getTrackFormat(vt) } else null

            val d = MediaCodec.createDecoderByType(af.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(af, null, null, 0)
            d.start()

            val sr = info.outSr
            val ch = info.outCh
            val ef = MediaFormat.createAudioFormat("audio/mp4a-latm", sr, ch)
            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            ef.setInteger(MediaFormat.KEY_BIT_RATE, if (ch >= 2) 128000 else 96000)
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            val e = MediaCodec.createEncoderByType("audio/mp4a-latm")
            enc = e
            e.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            e.start()

            val m = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            if (vf != null && vf.containsKey(MediaFormat.KEY_ROTATION)) {
                try { m.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION)) } catch (_: Exception) {}
            }

            var aTrack = -1
            var vTrack = -1
            val vBuf = ByteBuffer.allocate(maxOf(if (vf != null) intOf(vf, MediaFormat.KEY_MAX_INPUT_SIZE, 0) else 0, 2 * 1024 * 1024))
            val vInfo = MediaCodec.BufferInfo()

            // ভিডিওর নমুনা কপি করে (upTo মাইক্রোসেকেন্ড পর্যন্ত)
            fun drainVideo(upTo: Long) {
                if (vf == null || vTrack < 0) return
                while (true) {
                    val t = vEx.sampleTime
                    if (t < 0 || t > upTo) break
                    vBuf.clear()
                    val n = vEx.readSampleData(vBuf, 0)
                    if (n < 0) break
                    val fl = if ((vEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    vInfo.set(0, n, t, fl)
                    m.writeSampleData(vTrack, vBuf, vInfo)
                    vEx.advance()
                }
            }

            val di = MediaCodec.BufferInfo()
            val ei = MediaCodec.BufferInfo()
            var decInDone = false
            var decOutDone = false
            var encInDone = false
            var encOutDone = false
            var pending: ByteArray? = null
            var pOff = 0
            var pLen = 0
            var fedBytes = 0L
            val bytesPerSec = sr.toLong() * ch * 2L
            val deadline = System.currentTimeMillis() + 15 * 60 * 1000L
            var lastP = -1

            while (!encOutDone) {
                if (System.currentTimeMillis() > deadline) return false

                // ১. ডিকোডারে ইনপুট
                if (!decInDone) {
                    val ii = d.dequeueInputBuffer(0)
                    if (ii >= 0) {
                        val b = d.getInputBuffer(ii)!!
                        val n = aEx.readSampleData(b, 0)
                        if (n < 0) {
                            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInDone = true
                        } else {
                            d.queueInputBuffer(ii, 0, n, aEx.sampleTime, 0)
                            aEx.advance()
                        }
                    }
                }

                // ২. ডিকোডার থেকে PCM নেওয়া
                if (pLen == 0 && !decOutDone) {
                    val oi = d.dequeueOutputBuffer(di, 0)
                    if (oi >= 0) {
                        if (di.size > 0) {
                            val ob = d.getOutputBuffer(oi)!!
                            ob.position(di.offset)
                            ob.limit(di.offset + di.size)
                            val arr = ByteArray(di.size)
                            ob.get(arr)
                            pending = arr; pOff = 0; pLen = arr.size
                        }
                        if ((di.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decOutDone = true
                        d.releaseOutputBuffer(oi, false)
                    }
                }

                // ৩. এনকোডারে PCM দেওয়া
                if (!encInDone && (pLen > 0 || decOutDone)) {
                    val ii = e.dequeueInputBuffer(0)
                    if (ii >= 0) {
                        val b = e.getInputBuffer(ii)!!
                        b.clear()
                        val ptsUs = fedBytes * 1_000_000L / bytesPerSec
                        if (pLen > 0) {
                            val n = minOf(pLen, b.capacity())
                            b.put(pending!!, pOff, n)
                            pOff += n; pLen -= n; fedBytes += n
                            e.queueInputBuffer(ii, 0, n, ptsUs, 0)
                        } else {
                            e.queueInputBuffer(ii, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encInDone = true
                        }
                    }
                }

                // ৪. এনকোডার থেকে AAC নিয়ে ফাইলে লেখা
                val eo = e.dequeueOutputBuffer(ei, 5000)
                if (eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    aTrack = m.addTrack(e.outputFormat)
                    if (vf != null) vTrack = m.addTrack(vf)
                    m.start()
                    muxStarted = true
                } else if (eo >= 0) {
                    val ob = e.getOutputBuffer(eo)!!
                    if ((ei.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0
                    if (ei.size > 0 && muxStarted) {
                        ob.position(ei.offset)
                        ob.limit(ei.offset + ei.size)
                        m.writeSampleData(aTrack, ob, ei)
                        drainVideo(ei.presentationTimeUs + 500_000L)
                        if (durUs > 0) {
                            val p = minOf(99L, ei.presentationTimeUs * 100L / durUs).toInt()
                            if (p != lastP) { lastP = p; onProgress(p) }
                        }
                    }
                    if ((ei.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encOutDone = true
                    e.releaseOutputBuffer(eo, false)
                }
            }

            if (!muxStarted) return false
            drainVideo(Long.MAX_VALUE)
            m.stop()
            muxStarted = false
            m.release()
            mux = null
            if (out.exists()) out.delete()
            val ok = tmp.renameTo(out)
            if (ok) onProgress(100)
            return ok
        } catch (e: Exception) {
            return false
        } finally {
            try { dec?.stop() } catch (_: Exception) {}
            try { dec?.release() } catch (_: Exception) {}
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
            try { if (muxStarted) mux?.stop() } catch (_: Exception) {}
            try { mux?.release() } catch (_: Exception) {}
            try { aEx.release() } catch (_: Exception) {}
            try { vEx.release() } catch (_: Exception) {}
            if (tmp.exists()) tmp.delete()
        }
    }
}
