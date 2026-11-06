package com.example.livestream

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.nio.ByteBuffer

// Records the host's own live (camera + microphone, or microphone only) to a file on the phone.
// Video frames arrive through the VideoSink interface, the microphone is read by a second AudioRecord.
// Stops by itself after maxMs. onFinished(message) is always called on the main thread.
class LiveRecorder(
    private val ctx: Context,
    private val withVideo: Boolean,
    private val maxMs: Long,
    private val onFinished: (String) -> Unit
) : VideoSink {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var muxer: MediaMuxer? = null
    private var tmp: File? = null
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var aRec: AudioRecord? = null
    private var vTrack = -1
    private var aTrack = -1
    private var muxStarted = false
    private var audioExpected = false
    private var audioThread: Thread? = null
    private var startNs = 0L
    private var lastVideoUs = -1L
    private var tw = 0
    private var th = 0
    private var gotSamples = false
    private val pending = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()

    @Volatile private var running = false
    @Volatile private var finished = false
    @Volatile var muted = false
    @Volatile var startedAtMs = 0L

    fun elapsedSec(): Int = if (startedAtMs == 0L) 0 else ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            try { ctx.cacheDir.listFiles()?.forEach { if (it.name.startsWith("rec_")) it.delete() } } catch (e: Exception) {}
            val f = File(ctx.cacheDir, "rec_" + System.currentTimeMillis() + ".mp4")
            tmp = f
            muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // microphone + AAC encoder
            try {
                val rate = 44100
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    val af = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1)
                    af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    af.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                    af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
                    enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    enc.start()
                    aEnc = enc
                    aRec = rec
                    audioExpected = true
                } else rec.release()
            } catch (e: Exception) { audioExpected = false; try { aEnc?.release() } catch (x: Exception) {}; aEnc = null; aRec = null }
            if (!withVideo && !audioExpected) { cleanup(); return false }
            startNs = System.nanoTime()
            startedAtMs = System.currentTimeMillis()
            running = true
            if (audioExpected) {
                val r = aRec!!
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    running = false
                    if (!withVideo) { cleanup(); return false }
                    synchronized(lock) { audioExpected = false; tryStartLocked() }
                } else {
                    val t = Thread { audioLoop() }
                    audioThread = t
                    t.start()
                }
            }
            main.postDelayed({ stop() }, maxMs)
            true
        } catch (e: Exception) {
            running = false
            cleanup()
            false
        }
    }

    // ---------- video ----------
    override fun onFrame(frame: VideoFrame) {
        if (!running || !withVideo) return
        try { encodeFrame(frame) } catch (e: Exception) { fail("video: " + e.message) }
    }

    private fun initVideo(w0: Int, h0: Int) {
        var w = w0
        var h = h0
        val m = maxOf(w, h)
        if (m > 1280) { val s = 1280f / m; w = (w * s).toInt(); h = (h * s).toInt() }
        tw = (w / 16 * 16).coerceAtLeast(16)
        th = (h / 16 * 16).coerceAtLeast(16)
        val fmt = MediaFormat.createVideoFormat("video/avc", tw, th)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, if (tw * th > 500000) 2000000 else 1000000)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 25)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        val enc = MediaCodec.createEncoderByType("video/avc")
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        vEnc = enc
    }

    private fun encodeFrame(frame: VideoFrame) {
        val nowUs = (System.nanoTime() - startNs) / 1000
        if (lastVideoUs >= 0 && nowUs - lastVideoUs < 40000) return // at most 25 frames per second
        val b0 = frame.buffer
        if (vEnc == null) {
            try { muxer?.setOrientationHint(frame.rotation) } catch (e: Exception) {}
            initVideo(b0.width, b0.height)
        }
        val enc = vEnc ?: return
        val idx = enc.dequeueInputBuffer(10000)
        if (idx < 0) { drainVideo(); return }
        val img = enc.getInputImage(idx)
        if (img == null) { enc.queueInputBuffer(idx, 0, 0, nowUs, 0); return }
        val i420: VideoFrame.I420Buffer
        if (b0.width != tw || b0.height != th) {
            val sc = b0.cropAndScale(0, 0, b0.width, b0.height, tw, th)
            i420 = sc.toI420()
            sc.release()
        } else i420 = b0.toI420()
        try {
            val planes = img.planes
            copyPlane(i420.dataY, i420.strideY, tw, th, planes[0])
            copyPlane(i420.dataU, i420.strideU, tw / 2, th / 2, planes[1])
            copyPlane(i420.dataV, i420.strideV, tw / 2, th / 2, planes[2])
        } finally { i420.release() }
        enc.queueInputBuffer(idx, 0, tw * th * 3 / 2, nowUs, 0)
        lastVideoUs = nowUs
        drainVideo()
    }

    private fun copyPlane(src: ByteBuffer, srcStride: Int, w: Int, h: Int, dst: Image.Plane) {
        val db = dst.buffer
        val rs = dst.rowStride
        val ps = dst.pixelStride
        val s = src.duplicate()
        for (row in 0 until h) {
            val so = row * srcStride
            if (ps == 1) {
                s.clear()
                s.limit(so + w)
                s.position(so)
                db.position(row * rs)
                db.put(s)
            } else {
                for (col in 0 until w) db.put(row * rs + col * ps, s.get(so + col))
            }
        }
    }

    private fun drainVideo() {
        val enc = vEnc ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = enc.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) { vTrack = muxer!!.addTrack(enc.outputFormat); tryStartLocked() }
            } else if (o >= 0) {
                val ob = enc.getOutputBuffer(o)
                if (ob != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) writeSample(vTrack, ob, info)
                enc.releaseOutputBuffer(o, false)
            }
        }
    }

    // ---------- audio ----------
    private fun audioLoop() {
        val rec = aRec ?: return
        val enc = aEnc ?: return
        val buf = ByteArray(4096)
        var samples = 0L
        val base = (System.nanoTime() - startNs) / 1000
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (muted) java.util.Arrays.fill(buf, 0, n, 0.toByte())
                val idx = enc.dequeueInputBuffer(10000)
                if (idx >= 0) {
                    val ib = enc.getInputBuffer(idx)
                    if (ib != null) {
                        ib.clear()
                        ib.put(buf, 0, n)
                        enc.queueInputBuffer(idx, 0, n, base + samples * 1000000L / 44100, 0)
                        samples += n / 2
                    }
                }
                drainAudio(enc)
            }
        } catch (e: Exception) {
            synchronized(lock) { audioExpected = false; tryStartLocked() }
        }
    }

    private fun drainAudio(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = enc.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) { aTrack = muxer!!.addTrack(enc.outputFormat); tryStartLocked() }
            } else if (o >= 0) {
                val ob = enc.getOutputBuffer(o)
                if (ob != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) writeSample(aTrack, ob, info)
                enc.releaseOutputBuffer(o, false)
            }
        }
    }

    // ---------- muxer ----------
    private fun tryStartLocked() {
        if (muxStarted) return
        if (withVideo && vTrack < 0) return
        if (audioExpected && aTrack < 0) return
        if (!withVideo && !audioExpected) return
        muxer?.start()
        muxStarted = true
        for (p in pending) { try { muxer?.writeSampleData(p.first, p.second, p.third); gotSamples = true } catch (e: Exception) {} }
        pending.clear()
    }

    private fun writeSample(track: Int, ob: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0) return
        synchronized(lock) {
            if (muxer == null || finished) return
            ob.position(info.offset)
            ob.limit(info.offset + info.size)
            if (!muxStarted) {
                val c = ByteBuffer.allocate(info.size)
                c.put(ob)
                c.flip()
                val ni = MediaCodec.BufferInfo()
                ni.set(0, info.size, info.presentationTimeUs, info.flags)
                pending.add(Triple(track, c, ni))
            } else {
                muxer!!.writeSampleData(track, ob, info)
                gotSamples = true
            }
        }
    }

    // ---------- finish ----------
    private fun fail(msg: String) {
        stopInternal("Recording stopped: $msg")
    }

    fun stop() { stopInternal("") }

    private fun stopInternal(problem: String) {
        if (finished) return
        finished = true
        running = false
        main.removeCallbacksAndMessages(null)
        Thread {
            try { audioThread?.join(1500) } catch (e: Exception) {}
            var msg = problem
            try {
                try { vEnc?.stop() } catch (e: Exception) {}
                try { aEnc?.stop() } catch (e: Exception) {}
                try { aRec?.stop() } catch (e: Exception) {}
                var ok = false
                synchronized(lock) {
                    if (muxStarted && gotSamples) { try { muxer?.stop(); ok = true } catch (e: Exception) {} }
                    try { muxer?.release() } catch (e: Exception) {}
                    muxer = null
                }
                if (ok) {
                    val where = publish(tmp!!, !withVideo)
                    msg = if (problem.isEmpty()) "Recording saved: $where" else "$problem. Saved: $where"
                } else if (msg.isEmpty()) msg = "Recording was too short, nothing saved."
            } catch (e: Exception) {
                msg = "Recording error: " + e.message
            }
            cleanup()
            main.post { onFinished(msg) }
        }.start()
    }

    private fun cleanup() {
        try { vEnc?.release() } catch (e: Exception) {}
        try { aEnc?.release() } catch (e: Exception) {}
        try { aRec?.release() } catch (e: Exception) {}
        try { muxer?.release() } catch (e: Exception) {}
        vEnc = null; aEnc = null; aRec = null; muxer = null
        try { tmp?.delete() } catch (e: Exception) {}
    }

    private fun publish(f: File, audioOnly: Boolean): String {
        val name = "Livestream_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) +
            (if (audioOnly) ".m4a" else ".mp4")
        if (Build.VERSION.SDK_INT >= 29) {
            val dir = (if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Livestream"
            val cv = ContentValues()
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            cv.put(MediaStore.MediaColumns.MIME_TYPE, if (audioOnly) "audio/mp4" else "video/mp4")
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            val col = if (audioOnly) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = ctx.contentResolver.insert(col, cv) ?: throw Exception("cannot create file")
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            return "$dir/$name"
        }
        val out = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), name)
        f.copyTo(out, true)
        return out.absolutePath
    }
}
