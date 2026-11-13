package com.example.livestream

import android.content.Context
import android.content.pm.PackageManager
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.view.TextureView
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val MAX_VIDEO_MS = 8000L          // videos may be 8 seconds at most
const val MAX_VIDEO_BYTES = 12_000_000L // and 12 MB
const val MAX_PHOTO_BYTES = 550_000

class PostPrep(val kind: String, val file: File, val thumb: String, val frames: List<String> = emptyList())

// Downloads pictures from the server; small previews are kept in memory.
object Remote {
    private val cache = android.util.LruCache<String, ImageBitmap>(80)

    suspend fun image(path: String, maxSide: Int, keep: Boolean): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(path)?.let { return@withContext it }
        val bytes = Api.fetchBytes(Api.serverUrl, path) ?: return@withContext null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxSide && o.outHeight / (s * 2) >= maxSide) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        if (bmp != null && keep) cache.put(path, bmp)
        bmp
    }
}

private fun squareThumbOf(b: Bitmap): String {
    val side = minOf(b.width, b.height)
    val sq = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    val sc = Bitmap.createScaledBitmap(sq, 320, 320, true)
    var q = 70
    var s = jpegB64(sc, q)
    while (s.length > 38000 && q > 20) { q -= 10; s = jpegB64(sc, q) }
    return s
}

// a larger picture (480 px) for the server's second opinion
private fun checkB64(b: Bitmap): String {
    val m = maxOf(b.width, b.height)
    val f = if (m > 480) 480f / m else 1f
    val sc = if (f < 1f) Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true) else b
    var q = 70
    var s = jpegB64(sc, q)
    while (s.length > 60000 && q > 20) { q -= 10; s = jpegB64(sc, q) }
    return s
}

// "just now", "5m ago", "3h ago", "2d ago", then the date
fun timeAgo(ts: Long): String {
    if (ts <= 0L) return ""
    val sec = (System.currentTimeMillis() - ts) / 1000
    return when {
        sec < 60 -> "just now"
        sec < 3600 -> "${sec / 60}m ago"
        sec < 86400 -> "${sec / 3600}h ago"
        sec < 7 * 86400 -> "${sec / 86400}d ago"
        else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}

// posts this phone has already checked and found fine (not checked again when opened later)
object PostChecked {
    private const val KEY = "post_checked"
    fun has(ctx: Context, id: String): Boolean = ctx.getSharedPreferences("s", 0).getStringSet(KEY, emptySet())?.contains(id) == true
    fun add(ctx: Context, id: String) {
        val sp = ctx.getSharedPreferences("s", 0)
        val set = LinkedHashSet(sp.getStringSet(KEY, emptySet()) ?: emptySet())
        set.add(id)
        while (set.size > 500) set.remove(set.first()) // keep the newest 500
        sp.edit().putStringSet(KEY, set).apply()
    }
}

// Viewer side: the phone looks first; only a flagged picture goes to the server for a second opinion. "ok" or "removed".
private fun screenBitmap(server: String, id: String, bmp: Bitmap): String =
    if (!LocalGuard.suspicious(bmp)) "ok" else Api.confirmPost(server, id, checkB64(bmp))

private fun screenVideo(server: String, id: String, path: String): String {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(path)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        var t = 0L
        while (t < dur.coerceAtMost(9000L)) {
            val f = r.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (f != null && LocalGuard.suspicious(f)) return Api.confirmPost(server, id, checkB64(f))
            t += 1000L
        }
    } catch (e: Exception) {
    } finally {
        try { r.release() } catch (e: Exception) {}
    }
    return "ok"
}

// Photo: turned upright, scaled to 1280 px, checked on the phone, saved as a small JPEG in `out`.
private fun preparePhoto(ctx: Context, uri: Uri, out: File): Pair<PostPrep?, String> {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1280 && bounds.outHeight / (sample * 2) >= 1280) sample *= 2
        var bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return Pair(null, "Could not read the picture")
        val orient = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            } ?: 1
        } catch (e: Exception) { 1 }
        val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        val m = maxOf(bmp.width, bmp.height)
        if (m > 1280) {
            val f = 1280f / m
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        }
        // uploading: only the phone checks
        if (LocalGuard.suspicious(bmp)) return Pair(null, "This picture looks inappropriate and cannot be posted")
        var q = 85
        var bytes = ByteArray(0)
        do {
            val bo = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bo)
            bytes = bo.toByteArray()
            q -= 10
        } while (bytes.size > MAX_PHOTO_BYTES && q >= 30)
        if (bytes.size > 600_000) return Pair(null, "The picture is too large")
        out.writeBytes(bytes)
        return Pair(PostPrep("photo", out, squareThumbOf(bmp)), "")
    } catch (e: Exception) {
        return Pair(null, "Could not read the picture")
    }
}

// Video: MP4 of at most 8 seconds and 12 MB. A longer video is first trimmed by the user (TrimDialog).
const val MAX_SOURCE_BYTES = 300_000_000L // the video the user picks, before trimming

// copies the picked video into the app cache; returns "" when fine, otherwise the error text
private fun copyVideo(ctx: Context, uri: Uri, dst: File): String {
    try {
        var total = 0L
        val copied = ctx.contentResolver.openInputStream(uri)?.use { inp ->
            dst.outputStream().use { o ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SOURCE_BYTES) return "The video is too large (300 MB max)"
                    o.write(buf, 0, n)
                }
            }
            true
        } ?: false
        if (!copied) return "Could not read the video"
        val head = ByteArray(12)
        dst.inputStream().use { it.read(head) }
        if (!(head[4] == 0x66.toByte() && head[5] == 0x74.toByte() && head[6] == 0x79.toByte() && head[7] == 0x70.toByte()))
            return "Only MP4 videos can be posted"
        return ""
    } catch (e: Exception) {
        return "Could not read the video"
    }
}

private fun videoDurationMs(f: File): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(f.absolutePath)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) { 0L } finally {
        try { r.release() } catch (e: Exception) {}
    }
}

// The start of a trimmed clip can only be a key frame (the cut does not re-encode): the one nearest to ms, in ms
private fun nearestSyncMs(src: File, ms: Long): Long {
    val ex = MediaExtractor()
    try {
        ex.setDataSource(src.absolutePath)
        var vt = -1
        for (i in 0 until ex.trackCount) {
            if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { vt = i; break }
        }
        if (vt < 0) return ms
        ex.selectTrack(vt)
        val us = ms * 1000L
        ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val prev = ex.sampleTime
        ex.seekTo(us, MediaExtractor.SEEK_TO_NEXT_SYNC)
        val next = ex.sampleTime
        val best = listOf(prev, next).filter { it >= 0L }.minByOrNull { Math.abs(it - us) } ?: return ms
        return best / 1000L
    } catch (e: Exception) {
        return ms
    } finally {
        try { ex.release() } catch (e: Exception) {}
    }
}

private class TrimResult(val err: String, val baseUs: Long = 0L, val durUs: Long = 0L)

// Cuts [startUs, endUs] out of an MP4 without re-encoding (it starts at the key frame just before startUs; the result is kept under 8 s).
// keepAudio = false writes the picture only (used to mute the original sound, or to add music).
private fun trimVideo(src: File, dst: File, startUs: Long, endUs: Long, keepAudio: Boolean = true): TrimResult {
    val ex = MediaExtractor()
    var mux: MediaMuxer? = null
    var started = false
    try {
        ex.setDataSource(src.absolutePath)
        dst.delete()
        val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mux = m
        val map = HashMap<Int, Int>()
        var maxSize = 1 shl 20
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || (keepAudio && mime.startsWith("audio/"))) {
                ex.selectTrack(i)
                map[i] = m.addTrack(f)
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
        if (map.isEmpty()) return TrimResult("Could not read the video")
        val mr = MediaMetadataRetriever()
        try {
            mr.setDataSource(src.absolutePath)
            val rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            m.setOrientationHint(rot)
        } catch (e: Exception) {} finally {
            try { mr.release() } catch (e: Exception) {}
        }
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        m.start()
        started = true
        val buf = ByteBuffer.allocate(maxSize)
        val info = MediaCodec.BufferInfo()
        var base = -1L
        var last = 0L
        var wrote = 0
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            val t = ex.sampleTime
            if (base < 0) base = t
            if (t > endUs || t - base > MAX_VIDEO_MS * 1000L) break
            val track = map[ex.sampleTrackIndex]
            if (track != null) {
                info.set(0, size, maxOf(0L, t - base), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(track, buf, info)
                last = maxOf(last, t - base)
                wrote++
            }
            ex.advance()
        }
        m.stop()
        started = false
        return if (wrote > 0) TrimResult("", maxOf(0L, base), last + 34000L) else TrimResult("Could not trim the video")
    } catch (e: Exception) {
        return TrimResult("Could not trim the video")
    } finally {
        try { if (started) mux?.stop() } catch (e: Exception) {}
        try { mux?.release() } catch (e: Exception) {}
        try { ex.release() } catch (e: Exception) {}
    }
}

// ---- sound: decode, mix and encode to AAC (no extra library) ----

private class Pcm(val data: ShortArray, val rate: Int, val ch: Int)
private class AacFrame(val data: ByteArray, val pts: Long, val flags: Int)
private class Aac(val format: MediaFormat, val frames: List<AacFrame>)
class TrimChoice(val startMs: Long, val endMs: Long, val mute: Boolean, val music: Uri?, val musicVol: Float, val musicStartMs: Long = 0L, val musicEndMs: Long = 0L)

// decodes the audio track of a file / uri from startUs for about lenUs into 16-bit PCM; null if there is no audio
private fun decodeAudio(open: (MediaExtractor) -> Unit, startUs: Long, lenUs: Long): Pcm? {
    val ex = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        open(ex)
        var tr = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { tr = i; fmt = f; break }
        }
        val f = fmt ?: return null
        if (tr < 0) return null
        ex.selectTrack(tr)
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val c = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME) ?: return null)
        codec = c
        c.configure(f, null, null, 0)
        c.start()
        var rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val cap = (((lenUs / 1000000.0) + 1.0) * rate * ch).toInt()
        val data = ShortArray(cap)
        var n = 0
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        val deadline = System.currentTimeMillis() + 30000L
        while (!outDone && n < cap && System.currentTimeMillis() < deadline) {
            if (!inDone) {
                val ii = c.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val ib = c.getInputBuffer(ii)!!
                    val sz = ex.readSampleData(ib, 0)
                    if (sz < 0 || ex.sampleTime > startUs + lenUs + 500000L) {
                        c.queueInputBuffer(ii, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        c.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = c.dequeueOutputBuffer(info, 10000)
            if (oi >= 0) {
                if (info.size > 0 && info.presentationTimeUs >= startUs - 20000L) {
                    val ob = c.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val cnt = minOf(sb.remaining(), cap - n)
                    sb.get(data, n, cnt)
                    n += cnt
                }
                c.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = c.outputFormat
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        if (ch < 1) ch = 1
        n -= n % ch
        if (n <= 0) return null
        return Pcm(data.copyOf(n), rate, ch)
    } catch (e: Exception) {
        return null
    } finally {
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        try { ex.release() } catch (e: Exception) {}
    }
}

// converts to 44.1 kHz stereo, outFrames long; loop = repeat the sound when it is shorter, otherwise silence after its end
private fun toStereo44(p: Pcm, outFrames: Int, loop: Boolean): ShortArray {
    val out = ShortArray(outFrames * 2)
    val srcFrames = p.data.size / p.ch
    if (srcFrames <= 0) return out
    val step = p.rate / 44100.0
    for (i in 0 until outFrames) {
        var pos = i * step
        if (loop) {
            pos %= srcFrames.toDouble()
        } else if (pos >= srcFrames - 1) {
            break
        }
        val i0 = pos.toInt().coerceAtMost(srcFrames - 1)
        val i1 = if (i0 + 1 < srcFrames) i0 + 1 else if (loop) 0 else i0
        val fr = (pos - i0).toFloat()
        for (c in 0..1) {
            val cc = if (p.ch > 1) c else 0
            val a = p.data[i0 * p.ch + cc].toInt()
            val b = p.data[i1 * p.ch + cc].toInt()
            out[i * 2 + c] = (a + (b - a) * fr).toInt().toShort()
        }
    }
    return out
}

// encodes 44.1 kHz stereo PCM to AAC
private fun encodeAac(pcm: ShortArray): Aac? {
    var enc: MediaCodec? = null
    try {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val f = MediaFormat.createAudioFormat(mime, 44100, 2)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val e = MediaCodec.createEncoderByType(mime)
        enc = e
        e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        e.start()
        val frames = ArrayList<AacFrame>()
        var outFmt: MediaFormat? = null
        var pos = 0
        var inDone = false
        var outDone = false
        val info = MediaCodec.BufferInfo()
        val deadline = System.currentTimeMillis() + 30000L
        while (!outDone && System.currentTimeMillis() < deadline) {
            if (!inDone) {
                val ii = e.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val ib = e.getInputBuffer(ii)!!
                    ib.clear()
                    val room = ib.capacity() / 2
                    val n = minOf(room - room % 2, pcm.size - pos)
                    val pts = (pos / 2).toLong() * 1000000L / 44100L
                    if (n <= 0) {
                        e.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        ib.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, n)
                        e.queueInputBuffer(ii, 0, n * 2, pts, 0)
                        pos += n
                    }
                }
            }
            val oi = e.dequeueOutputBuffer(info, 10000)
            if (oi >= 0) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val ob = e.getOutputBuffer(oi)!!
                    val bytes = ByteArray(info.size)
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    ob.get(bytes)
                    frames.add(AacFrame(bytes, info.presentationTimeUs, info.flags))
                }
                e.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outFmt = e.outputFormat
            }
        }
        val of = outFmt ?: return null
        if (frames.isEmpty()) return null
        return Aac(of, frames)
    } catch (e: Exception) {
        return null
    } finally {
        try { enc?.stop() } catch (e: Exception) {}
        try { enc?.release() } catch (e: Exception) {}
    }
}

// writes the picture of vOnly together with the AAC sound into dst
private fun muxVideoAndAudio(vOnly: File, aac: Aac, dst: File): String {
    val ex = MediaExtractor()
    var mux: MediaMuxer? = null
    var started = false
    try {
        ex.setDataSource(vOnly.absolutePath)
        var vt = -1
        var vfmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { vt = i; vfmt = f; break }
        }
        val vf = vfmt ?: return "Could not read the video"
        ex.selectTrack(vt)
        dst.delete()
        val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mux = m
        val vi = m.addTrack(vf)
        val ai = m.addTrack(aac.format)
        val mr = MediaMetadataRetriever()
        try {
            mr.setDataSource(vOnly.absolutePath)
            m.setOrientationHint(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
        } catch (e: Exception) {} finally {
            try { mr.release() } catch (e: Exception) {}
        }
        m.start()
        started = true
        var maxSize = 1 shl 20
        if (vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        val buf = ByteBuffer.allocate(maxSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            m.writeSampleData(vi, buf, info)
            ex.advance()
        }
        for (fr in aac.frames) {
            info.set(0, fr.data.size, fr.pts, fr.flags)
            m.writeSampleData(ai, ByteBuffer.wrap(fr.data), info)
        }
        m.stop()
        started = false
        return ""
    } catch (e: Exception) {
        return "Could not add the music"
    } finally {
        try { if (started) mux?.stop() } catch (e: Exception) {}
        try { mux?.release() } catch (e: Exception) {}
        try { ex.release() } catch (e: Exception) {}
    }
}

// Exact cut with re-encoding (Media3 Transformer): starts at the chosen frame, not at a key frame. "" when fine, otherwise the error text.
private suspend fun exportExact(ctx: Context, src: File, dst: File, startMs: Long, endMs: Long, removeAudio: Boolean): String {
    val r = withTimeoutOrNull(90000L) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String> { cont ->
                try {
                    dst.delete()
                    val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
                    val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(src)).setClippingConfiguration(clip).build())
                        .setRemoveAudio(removeAudio)
                        .build()
                    val enc = DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(6000000).build())
                        .build()
                    val t = Transformer.Builder(ctx)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(enc)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume("")
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resume("Could not trim the video")
                            }
                        })
                        .build()
                    cont.invokeOnCancellation { try { t.cancel() } catch (e: Exception) {} }
                    t.start(item, dst.absolutePath)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume("Could not trim the video")
                }
            }
        }
    }
    return r ?: "Trimming took too long"
}

// builds the video to post from the user's choices: exact cut, original sound on/off, music. Returns "" when fine, otherwise the error text.
private suspend fun buildClip(ctx: Context, src: File, dst: File, c: TrimChoice): String {
    val music = c.music
    val removeAudio = c.mute || music != null
    val picture = if (music == null) dst else File(ctx.cacheDir, "post_video_only.mp4")
    var baseUs = c.startMs * 1000L
    var durUs = (c.endMs - c.startMs) * 1000L
    var e = exportExact(ctx, src, picture, c.startMs, c.endMs, removeAudio)
    if (e.isNotEmpty() || !picture.exists() || picture.length() < 1000L) {
        // the exact cut did not work on this phone: fall back to cutting at the nearest key frame
        val r = withContext(Dispatchers.IO) { trimVideo(src, picture, c.startMs * 1000L, c.endMs * 1000L, keepAudio = !removeAudio) }
        if (r.err.isNotEmpty()) return r.err
        baseUs = r.baseUs
        durUs = r.durUs
        e = ""
    }
    if (music == null) return e
    return withContext(Dispatchers.IO) {
        val frames = (durUs * 44100L / 1000000L).toInt().coerceAtLeast(22050)
        val segUs = (c.musicEndMs - c.musicStartMs).coerceAtLeast(1000L) * 1000L // the chosen part of the song; it repeats if it is shorter than the video
        val song0 = decodeAudio({ it.setDataSource(ctx, music, null) }, c.musicStartMs * 1000L, segUs)
        if (song0 == null) return@withContext "Could not read this music file"
        val keep = minOf(song0.data.size, ((segUs / 1000000.0) * song0.rate).toInt() * song0.ch) // exactly the chosen part
        val song = Pcm(song0.data.copyOf(keep - keep % song0.ch), song0.rate, song0.ch)
        val mus = toStereo44(song, frames, true)
        var org: ShortArray? = null
        if (!c.mute) {
            val o = decodeAudio({ it.setDataSource(src.absolutePath) }, baseUs, durUs)
            if (o != null) org = toStereo44(o, frames, false)
        }
        val mix = ShortArray(frames * 2)
        for (i in mix.indices) {
            val v = (mus[i] * c.musicVol + (if (org != null) org[i].toInt() else 0)).toInt()
            mix[i] = v.coerceIn(-32768, 32767).toShort()
        }
        val aac = encodeAac(mix) ?: return@withContext "Could not add the music"
        val err = muxVideoAndAudio(picture, aac, dst)
        picture.delete()
        err
    }
}

// checks a video file of 8 seconds at most: size, every second of it on the phone for nudity, and makes the thumbnail
private fun checkVideo(out: File): Pair<PostPrep?, String> {
    try {
        if (out.length() > MAX_VIDEO_BYTES) return Pair(null, "The video is larger than 12 MB. Pick a shorter or lower-quality clip.")
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(out.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur <= 0L) return Pair(null, "Could not read the video")
            if (dur > MAX_VIDEO_MS + 300L) return Pair(null, "The video must be 8 seconds or shorter (yours is ${dur / 1000} s)")
            // uploading: only the phone checks, every second of the video
            var thumb: String? = null
            var t = 0L
            while (t < dur) {
                val f = r.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (f != null) {
                    if (LocalGuard.suspicious(f)) return Pair(null, "This video looks inappropriate and cannot be posted")
                    if (thumb == null) thumb = squareThumbOf(f)
                }
                t += 1000L
            }
            val th = thumb ?: return Pair(null, "Could not read the video")
            return Pair(PostPrep("video", out, th), "")
        } finally {
            try { r.release() } catch (e: Exception) {}
        }
    } catch (e: Exception) {
        return Pair(null, "Could not read the video")
    }
}

private class Song(val uri: Uri, val title: String, val artist: String)

// songs on the phone whose title or artist contains q
private fun searchSongs(ctx: Context, q: String): List<Song> {
    val out = ArrayList<Song>()
    try {
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
        var sel = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        var args: Array<String>? = null
        if (q.isNotEmpty()) {
            sel += " AND (" + MediaStore.Audio.Media.TITLE + " LIKE ? OR " + MediaStore.Audio.Media.ARTIST + " LIKE ?)"
            args = arrayOf("%$q%", "%$q%")
        }
        ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, args, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { c ->
            while (c.moveToNext() && out.size < 100) {
                if (c.getLong(3) < 5000L) continue
                val artist = c.getString(2) ?: ""
                out.add(Song(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), c.getString(1) ?: "Music", if (artist == "<unknown>") "" else artist))
            }
        }
    } catch (e: Exception) {}
    return out
}

private fun fileNameOf(ctx: Context, u: Uri): String {
    try {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: "Music").substringBeforeLast('.')
        }
    } catch (e: Exception) {}
    return "Music"
}

@Composable
private fun MusicDialog(onPick: (Uri, String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val browse = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) onPick(u, fileNameOf(ctx, u)) }
    var q by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    LaunchedEffect(q, allowed) {
        if (allowed) {
            delay(250)
            songs = withContext(Dispatchers.IO) { searchSongs(ctx, q.trim()) }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add music") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search song or artist") }, modifier = Modifier.fillMaxWidth())
                if (!allowed) {
                    Text("Allow access to the music on your phone to search it.")
                    Button({ ask.launch(perm) }) { Text("Allow") }
                } else if (songs.isEmpty()) {
                    Text("No songs found")
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(songs.size) { i ->
                            val sg = songs[i]
                            Column(Modifier.fillMaxWidth().clickable { onPick(sg.uri, sg.title) }.padding(vertical = 6.dp)) {
                                Text(sg.title, maxLines = 1)
                                if (sg.artist.isNotEmpty()) Text(sg.artist, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
                OutlinedButton({ browse.launch("audio/*") }) { Text("Browse files") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClose) { Text("Close") } }
    )
}

// Edit a video before posting: choose the part (start and end, or a fixed length that slides), mute the original sound, add music
// (its part of the song is chosen the same way). The preview keeps playing while you drag, and plays exactly the chosen part.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimDialog(src: File, durMs: Long, busy: Boolean, msg: String, onUse: (TrimChoice) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val maxLen = MAX_VIDEO_MS.toFloat()
    val total = durMs.toFloat()
    var start by remember { mutableStateOf(0f) }
    var end by remember { mutableStateOf(minOf(maxLen, total)) }
    var lockLen by remember { mutableStateOf(0f) } // 0 = free (drag start and end); otherwise the fixed length in ms
    var mute by remember { mutableStateOf(false) }
    var music by remember { mutableStateOf<Uri?>(null) }
    var musicName by remember { mutableStateOf("") }
    var musicVol by remember { mutableStateOf(0.8f) }
    var mS by remember { mutableStateOf(0f) }          // the part of the song that is used: from mS to mE (ms)
    var mE by remember { mutableStateOf(0f) }
    var musicLock by remember { mutableStateOf(true) } // true = the music part is as long as the video part
    var musicDur by remember { mutableStateOf(0L) }    // length of the song (ms), 0 = unknown
    var showMusic by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf(16f / 9f) }
    var gen by remember { mutableStateOf(0) }            // the two players are rebuilt (gen + 1) when they were released to free the phone's codecs
    var released by remember { mutableStateOf(false) }
    var previewErr by remember { mutableStateOf("") }
    val exo = remember(gen) { ExoPlayer.Builder(ctx).build() }      // plays the video
    val musicExo = remember(gen) { ExoPlayer.Builder(ctx).build() } // plays the song

    // jump the preview to the start of the chosen part and play (video and music together)
    fun restart() {
        exo.seekTo(start.toLong())
        exo.playWhenReady = true
        if (music != null) { musicExo.seekTo(mS.toLong()); musicExo.playWhenReady = true }
    }
    // while a handle is dragged, seek at most ~8 times a second (more would starve the phone and the slider)
    var lastSeekV by remember { mutableStateOf(0L) }
    var lastSeekM by remember { mutableStateOf(0L) }
    fun seekVideo(ms: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSeekV > 120L) { lastSeekV = now; exo.seekTo(ms) }
        exo.playWhenReady = true
    }
    fun seekMusic(ms: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSeekM > 120L) { lastSeekM = now; musicExo.seekTo(ms) }
        musicExo.playWhenReady = true
    }
    DisposableEffect(gen) {
        previewErr = ""
        exo.setSeekParameters(SeekParameters.EXACT)
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0 && videoSize.width > 0) ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (previewErr.isEmpty()) previewErr = "Video preview problem: " + error.errorCodeName + (error.cause?.message?.let { " (" + it.take(80) + ")" } ?: "") + (if (src.exists()) "" else " - the video file is gone")
            }
        })
        musicExo.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (previewErr.isEmpty()) previewErr = "Music preview problem: " + error.errorCodeName + (error.cause?.message?.let { " (" + it.take(80) + ")" } ?: "")
            }
        })
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(src)))
        exo.prepare()
        exo.seekTo(start.toLong())
        exo.playWhenReady = true
        onDispose {
            try { exo.release() } catch (e: Exception) {}
            try { musicExo.release() } catch (e: Exception) {}
        }
    }
    // after the video is made (or failed) the previews are rebuilt, so that "Use this" can be tapped again
    LaunchedEffect(busy) { if (!busy && released) { released = false; gen++ } }
    LaunchedEffect(gen) { // keeps the preview inside the chosen part, and loops it (the song loops inside its own part)
        var lastPos = -1L
        var stuck = 0
        var retries = 0
        var retryAt = 0L
        while (true) {
            delay(30)
            if (released) continue // the players are freed while the video is being made
            val st = exo.playbackState
            if (st == Player.STATE_IDLE) { // the video player stopped (an error): load the video again, a few times only
                val now = System.currentTimeMillis()
                if (retries < 3 && now >= retryAt) {
                    retries++
                    retryAt = now + 1500L
                    exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(src)))
                    exo.prepare()
                    restart()
                }
                continue
            }
            if (st == Player.STATE_READY) retries = 0
            val p = exo.currentPosition
            // playing but the picture is not moving for about a second: start the part again
            if (exo.playWhenReady && st == Player.STATE_READY && p == lastPos) stuck += 30 else stuck = 0
            lastPos = p
            if (stuck >= 900) { stuck = 0; restart() }
            if (st == Player.STATE_ENDED || p >= end.toLong() - 15L || p < start.toLong() - 150L) restart()
            if (music != null && mE > mS + 200f && (musicExo.playbackState == Player.STATE_ENDED || musicExo.currentPosition >= mE.toLong() - 15L)) {
                musicExo.seekTo(mS.toLong())
                musicExo.playWhenReady = true
            }
        }
    }
    LaunchedEffect(mute, gen) { exo.volume = if (mute) 0f else 1f }
    LaunchedEffect(musicVol, gen) { musicExo.volume = musicVol }
    LaunchedEffect(music) { // length of the song, and the first music part
        val m = music
        mS = 0f
        mE = 0f
        if (m == null) {
            musicDur = 0L
        } else {
            musicDur = withContext(Dispatchers.IO) {
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(ctx, m)
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    r.release()
                    d
                } catch (e: Exception) { 0L }
            }
            mE = if (musicDur > 0L) minOf((end - start).coerceAtLeast(500f), musicDur.toFloat()) else (end - start).coerceAtLeast(500f)
        }
    }
    LaunchedEffect(music, gen) { // loads the song into its player
        val m = music
        if (m == null) {
            musicExo.stop()
            musicExo.clearMediaItems()
        } else {
            musicExo.setMediaItem(MediaItem.fromUri(m))
            musicExo.volume = musicVol
            musicExo.prepare()
            restart() // video and music start together
        }
    }
    // when the music part is "as long as the video part", it follows every change of the video part
    LaunchedEffect(start, end, musicLock, musicDur, music) {
        if (music != null && musicLock && musicDur > 0L) {
            val d = musicDur.toFloat()
            val l = minOf((end - start).coerceAtLeast(500f), d)
            val ns = mS.coerceIn(0f, (d - l).coerceAtLeast(0f))
            mS = ns
            mE = ns + l
        }
    }
    fun mmss(ms: Long) = "%d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)
    fun lock(l: Float) {
        lockLen = l
        val e = minOf(total, start + l)
        end = e
        start = (e - l).coerceAtLeast(0f)
        restart()
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Edit your video") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your video is ${mmss(durMs)} long. A post can be 8 seconds at most. The preview plays exactly the part that will be posted.")
                if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                if (previewErr.isNotEmpty()) {
                    Text(previewErr, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    TextButton({ gen++ }) { Text("Reload preview") }
                }
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    key(gen) { AndroidView(factory = { c -> TextureView(c).also { exo.setVideoTextureView(it) } }, modifier = Modifier.aspectRatio(ratio)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Length", fontSize = 13.sp)
                    FilterChip(selected = lockLen == 0f, onClick = { lockLen = 0f }, label = { Text("Free") }, enabled = !busy)
                    for (sec in listOf(3, 5, 8)) {
                        if (sec * 1000f <= total) FilterChip(selected = lockLen == sec * 1000f, onClick = { lock(sec * 1000f) }, label = { Text("$sec s") }, enabled = !busy)
                    }
                }
                if (lockLen > 0f) {
                    val l = minOf(lockLen, total)
                    // two handles, one fixed length: dragging either one moves both together
                    RangeSlider(start..end, { r ->
                        val ns = if (r.start != start) r.start else r.endInclusive - l
                        start = ns.coerceIn(0f, (total - l).coerceAtLeast(0f))
                        end = start + l
                        seekVideo(start.toLong())
                    }, valueRange = 0f..total, onValueChangeFinished = { restart() }, enabled = !busy)
                } else {
                    RangeSlider(start..end, { r ->
                        var a = r.start
                        var e = r.endInclusive
                        val moved = a != start
                        if (moved) a = a.coerceIn((e - maxLen).coerceAtLeast(0f), (e - 1000f).coerceAtLeast(0f))
                        else e = e.coerceIn(minOf(a + 1000f, total), minOf(a + maxLen, total))
                        start = a; end = e
                        // keep playing while dragging: the start handle shows its own picture, the end handle shows the last second
                        seekVideo((if (moved) a else (e - 1000f).coerceAtLeast(a)).toLong())
                    }, valueRange = 0f..total, onValueChangeFinished = { restart() }, enabled = !busy)
                }
                Text("Part used: ${mmss(start.toLong())} to ${mmss(end.toLong())}  (${"%.1f".format((end - start) / 1000f)} s of 8 s max)" + if (lockLen > 0f) "  - drag to move the whole part" else "", fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Original sound", Modifier.weight(1f))
                    Switch(checked = !mute, onCheckedChange = { mute = !it; exo.volume = if (mute) 0f else 1f; restart() }, enabled = !busy)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (music == null) "No music" else "Music: $musicName", Modifier.weight(1f), maxLines = 2)
                    OutlinedButton({ showMusic = true }, enabled = !busy) { Text(if (music == null) "Add music" else "Change") }
                    if (music != null) TextButton({ music = null }, enabled = !busy) { Text("Remove") }
                }
                if (music != null) {
                    Text("Music volume", fontSize = 12.sp)
                    Slider(musicVol, { musicVol = it }, valueRange = 0f..1f, enabled = !busy)
                    if (musicDur > 0L) {
                        val d = musicDur.toFloat()
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Music part", fontSize = 13.sp)
                            FilterChip(selected = musicLock, onClick = { musicLock = true; restart() }, label = { Text("Same length as video") }, enabled = !busy)
                            FilterChip(selected = !musicLock, onClick = { musicLock = false; restart() }, label = { Text("Free") }, enabled = !busy)
                        }
                        // two handles on the song, like the video part
                        RangeSlider(mS..mE, { r ->
                            if (musicLock) {
                                val l = mE - mS
                                val ns = if (r.start != mS) r.start else r.endInclusive - l
                                mS = ns.coerceIn(0f, (d - l).coerceAtLeast(0f))
                                mE = mS + l
                                seekMusic(mS.toLong())
                            } else {
                                var a = r.start
                                var e = r.endInclusive
                                val moved = a != mS
                                if (moved) a = a.coerceIn(0f, (e - 1000f).coerceAtLeast(0f))
                                else e = e.coerceIn(minOf(a + 1000f, d), d)
                                mS = a; mE = e
                                seekMusic((if (moved) a else (e - 500f).coerceAtLeast(a)).toLong())
                            }
                        }, valueRange = 0f..d, onValueChangeFinished = { restart() }, enabled = !busy)
                        Text("Music part: ${mmss(mS.toLong())} to ${mmss(mE.toLong())} of ${mmss(musicDur)}" +
                            (if (!musicLock && mE - mS < end - start) "  - it repeats to fill the video" else ""), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button({
                // free the phone's video and audio codecs first: making the video needs them too
                released = true
                try { exo.release() } catch (e: Exception) {}
                try { musicExo.release() } catch (e: Exception) {}
                val ms = if (music != null && musicDur > 0L) mS.toLong() else 0L
                val me = if (music != null && musicDur > 0L) mE.toLong() else (end - start).toLong()
                onUse(TrimChoice(start.toLong(), end.toLong(), mute, music, musicVol, ms, me))
            }, enabled = !busy) { Text(if (busy) "Working…" else "Use this") }
        },
        dismissButton = { TextButton(onCancel, enabled = !busy) { Text("Cancel") } }
    )
    if (showMusic) MusicDialog({ u, n -> music = u; musicName = n; showMusic = false }, { showMusic = false })
}

@Composable
fun PostsScreen(server: String, user: String, openNew: Boolean = false, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var posts by remember { mutableStateOf(listOf<Api.Post>()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }
    var showChoose by remember { mutableStateOf(openNew) } // from the profile page the "new post" choices open at once
    var prep by remember { mutableStateOf<PostPrep?>(null) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Api.Post?>(null) }
    var writing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var trimSrc by remember { mutableStateOf<File?>(null) } // a picked video longer than 8 s, waiting to be trimmed
    var trimDur by remember { mutableStateOf(0L) }

    suspend fun refresh() {
        loading = true
        posts = withContext(Dispatchers.IO) { Api.posts(server) }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    fun picked(uri: Uri?, kind: String) {
        if (uri == null) return
        scope.launch {
            busy = true; msg = "Checking…"
            if (kind == "video") {
                val src = File(ctx.filesDir, "post_source.mp4") // not in the cache folder: the phone may clean that while you edit
                val e = withContext(Dispatchers.IO) { copyVideo(ctx, uri, src) }
                if (e.isNotEmpty()) { busy = false; msg = e; return@launch }
                val dur = withContext(Dispatchers.IO) { videoDurationMs(src) }
                if (dur <= 0L) { busy = false; msg = "Could not read the video"; return@launch }
                trimSrc = src; trimDur = dur; busy = false; msg = "" // the user edits it first: trim, mute, music
                return@launch
            }
            val out = File(ctx.cacheDir, "post_upload.jpg")
            val r = withContext(Dispatchers.Default) { preparePhoto(ctx, uri, out) }
            busy = false
            if (r.first != null) { prep = r.first; caption = ""; msg = "" } else msg = r.second
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked(it, "photo") }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked(it, "video") }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Back") }
            Text("Posts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button({ if (Api.auth.isEmpty()) msg = "Log in to post (👤 on Home)" else showChoose = true }, enabled = !busy) { Text("➕ Post") }
        }
        if (msg.isNotEmpty()) Text(msg, modifier = Modifier.padding(vertical = 6.dp))
        if (!loading && posts.isEmpty()) Text("No posts yet", modifier = Modifier.padding(vertical = 12.dp))
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(posts, key = { it.id }) { p ->
                val bmp by produceState<ImageBitmap?>(null, p.id) { value = if (p.kind == "text") null else Remote.image("post/${p.id}/thumb", 400, true) }
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF333333)).clickable { viewing = p }) {
                    if (p.kind == "text") Text(p.caption, color = Color.White, fontSize = 12.sp, maxLines = 6, modifier = Modifier.padding(6.dp))
                    bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (p.kind == "video") Text("▶", color = Color.White, fontSize = 30.sp, modifier = Modifier.align(Alignment.Center))
                    Text(p.user + (if (p.ts > 0L) "\n" + timeAgo(p.ts) else ""), color = Color.White, fontSize = 11.sp, maxLines = 2,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x88000000)).padding(horizontal = 5.dp, vertical = 2.dp))
                }
            }
        }
    }

    if (showChoose) AlertDialog(
        onDismissRequest = { showChoose = false },
        title = { Text("New post") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text only, a photo, or a video (MP4). A video can be 8 seconds at most; you can trim it, mute its sound or add music.")
                Button({ showChoose = false; draft = ""; msg = ""; writing = true }, Modifier.fillMaxWidth()) { Text("✏️ Text only") }
                Button({ showChoose = false; pickPhoto.launch("image/*") }, Modifier.fillMaxWidth()) { Text("📷 Photo") }
                OutlinedButton({ showChoose = false; pickVideo.launch("video/*") }, Modifier.fillMaxWidth()) { Text("🎬 Video") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ showChoose = false }) { Text("Cancel") } }
    )

    if (writing) AlertDialog(
        onDismissRequest = { if (!busy) writing = false },
        title = { Text("Write a post") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(draft, { draft = it.take(500) }, label = { Text("What's on your mind?") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp))
                Text("${draft.length}/500", fontSize = 12.sp)
                if (msg.isNotEmpty()) Text(msg)
            }
        },
        confirmButton = {
            Button({
                scope.launch {
                    busy = true; msg = "Posting…"
                    val err = withContext(Dispatchers.IO) {
                        try { Api.createPost(server, "text", draft.trim(), ""); "" } catch (e: Exception) { e.message ?: "Failed" }
                    }
                    busy = false
                    if (err.isEmpty()) { writing = false; msg = "Posted ✓"; refresh() } else msg = err
                }
            }, enabled = !busy && draft.isNotBlank()) { Text("Post") }
        },
        dismissButton = { TextButton({ writing = false; msg = "" }, enabled = !busy) { Text("Cancel") } }
    )

    trimSrc?.let { src ->
        TrimDialog(src, trimDur, busy, msg, { ch ->
            scope.launch {
                busy = true; msg = "Preparing video…"
                val out = File(ctx.cacheDir, "post_upload.mp4")
                val untouched = ch.startMs == 0L && ch.endMs >= trimDur - 150L && !ch.mute && ch.music == null && trimDur <= MAX_VIDEO_MS + 300L
                val e = if (untouched) "" else buildClip(ctx, src, out, ch)
                if (e.isNotEmpty()) { busy = false; msg = e; return@launch }
                msg = "Checking…"
                val v = withContext(Dispatchers.Default) { checkVideo(if (untouched) src else out) }
                busy = false
                trimSrc = null
                if (!untouched) { try { src.delete() } catch (e: Exception) {} } // the edited copy is what gets posted
                if (v.first != null) { prep = v.first; caption = ""; msg = "" } else msg = v.second
            }
        }, { try { trimSrc?.delete() } catch (e: Exception) {}; trimSrc = null; msg = "" })
    }

    prep?.let { p ->
        AlertDialog(
            onDismissRequest = { if (!busy) prep = null },
            title = { Text(if (p.kind == "video") "Post this video" else "Post this picture") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(caption, { caption = it.take(300) }, label = { Text("Write something (optional)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
                    Text("${caption.length}/300", fontSize = 12.sp)
                    if (msg.isNotEmpty()) Text(msg)
                }
            },
            confirmButton = {
                Button({
                    scope.launch {
                        busy = true; msg = "Uploading…"
                        val err = withContext(Dispatchers.IO) {
                            try {
                                val id = Api.createPost(server, p.kind, caption.trim(), p.thumb, p.frames)
                                Api.uploadPostData(server, id, p.file)
                            } catch (e: Exception) { e.message ?: "Failed" }
                        }
                        busy = false
                        if (err.isEmpty()) { prep = null; msg = "Posted ✓"; refresh() } else msg = err
                    }
                }, enabled = !busy) { Text("Post") }
            },
            dismissButton = { TextButton({ prep = null; msg = "" }, enabled = !busy) { Text("Cancel") } }
        )
    }

    viewing?.let { p ->
        PostViewer(server, p, mine = Api.auth.isNotEmpty() && p.user.equals(user, ignoreCase = true),
            onClose = { viewing = null },
            onDeleted = { viewing = null; scope.launch { refresh() } },
            onRemoved = { scope.launch { refresh() } })
    }
}

@Composable
fun PostViewer(server: String, p: Api.Post, mine: Boolean, onClose: () -> Unit, onDeleted: () -> Unit, onRemoved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = remember { ctx.getSharedPreferences("s", 0).getString("acct", "") ?: "" }
    var info by remember { mutableStateOf<Api.PostInfo?>(null) }
    var showComments by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var cmsg by remember { mutableStateOf("") }
    LaunchedEffect(p.id) { info = withContext(Dispatchers.IO) { Api.postInfo(server, p.id) } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (p.kind == "text") {
                val txt by produceState<String?>(null, p.id) {
                    value = withContext(Dispatchers.IO) { Api.fetchBytes(server, "post/${p.id}")?.toString(Charsets.UTF_8) }
                }
                Text(txt ?: p.caption.ifEmpty { "Loading…" }, color = Color.White, fontSize = 20.sp,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 110.dp))
            } else if (p.kind == "video") {
                val path by produceState<String?>(null, p.id) {
                    value = withContext(Dispatchers.IO) {
                        val f = File(ctx.cacheDir, "post_${p.id}.mp4")
                        if ((f.exists() && f.length() > 0L) || Api.downloadTo(server, "post/${p.id}", f)) f.absolutePath else null
                    }
                }
                val pa = path
                val verdict by produceState("checking", pa) {
                    value = if (pa == null) "checking" else if (mine || p.cleared || PostChecked.has(ctx, p.id)) "ok" else withContext(Dispatchers.Default) { screenVideo(server, p.id, pa).also { if (it == "ok") PostChecked.add(ctx, p.id) } }
                }
                LaunchedEffect(verdict) { if (verdict == "removed") onRemoved() }
                if (pa == null || verdict == "checking") Text("Checking…", color = Color.White)
                else if (verdict == "removed") Text("This post was removed because it looked inappropriate.", color = Color.White, modifier = Modifier.padding(24.dp))
                else AndroidView(factory = { c ->
                    VideoView(c).apply {
                        setVideoPath(pa)
                        setOnPreparedListener { mp -> mp.isLooping = true; start() }
                    }
                })
            } else {
                val img by produceState<ImageBitmap?>(null, p.id) { value = Remote.image("post/${p.id}", 1600, false) }
                val b = img
                val verdict by produceState("checking", b) {
                    value = if (b == null) "checking" else if (mine || p.cleared || PostChecked.has(ctx, p.id)) "ok" else withContext(Dispatchers.Default) { screenBitmap(server, p.id, b.asAndroidBitmap()).also { if (it == "ok") PostChecked.add(ctx, p.id) } }
                }
                LaunchedEffect(verdict) { if (verdict == "removed") onRemoved() }
                if (b == null || verdict == "checking") Text("Checking…", color = Color.White)
                else if (verdict == "removed") Text("This post was removed because it looked inappropriate.", color = Color.White, modifier = Modifier.padding(24.dp))
                else Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.user, 36.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(p.user, color = Color.White, fontSize = 15.sp)
                    if (p.ts > 0L) Text(timeAgo(p.ts), color = Color(0xFFBBBBBB), fontSize = 12.sp)
                    if (p.caption.isNotEmpty() && p.kind != "text") {
                        // the list only carries the first 100 characters; longer text is fetched
                        val fullText by produceState(p.caption, p.id) {
                            if (p.caption.length >= 100) value = withContext(Dispatchers.IO) { Api.fetchBytes(server, "post/${p.id}/text")?.toString(Charsets.UTF_8)?.ifEmpty { null } } ?: p.caption
                        }
                        Text(fullText, color = Color(0xFFDDDDDD), fontSize = 13.sp)
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showComments) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xEE111118)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val cs = info?.comments.orEmpty()
                        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (cs.isEmpty()) Text("No comments yet", color = Color(0xFFBBBBBB), fontSize = 13.sp)
                            cs.forEach { c ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.user + "  " + timeAgo(c.ts), color = Color(0xFFFFD54F), fontSize = 11.sp)
                                        Text(c.text, color = Color.White, fontSize = 14.sp)
                                    }
                                    if (mine || (me.isNotEmpty() && c.user.equals(me, ignoreCase = true))) TextButton({
                                        scope.launch { withContext(Dispatchers.IO) { Api.uncomment(server, p.id, c.ts) }?.let { info = it } }
                                    }) { Text("✕", color = Color.White) }
                                }
                            }
                        }
                        if (me.isEmpty()) Text("Log in to comment", color = Color(0xFFBBBBBB), fontSize = 13.sp)
                        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(draft, { draft = it.take(200) }, Modifier.weight(1f), singleLine = true,
                                textStyle = LocalTextStyle.current.copy(color = Color.White), placeholder = { Text("Add a comment…") })
                            Button({
                                val text = draft.trim()
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { Api.commentPost(server, p.id, text) }
                                    if (r != null) { info = r; draft = ""; cmsg = "" } else cmsg = "Could not send the comment"
                                }
                            }, enabled = draft.isNotBlank()) { Text("Send") }
                        }
                        if (cmsg.isNotEmpty()) Text(cmsg, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val liked = info?.liked == true
                    OutlinedButton({
                        if (me.isEmpty()) { cmsg = "Log in to like"; showComments = true }
                        else scope.launch { withContext(Dispatchers.IO) { Api.likePost(server, p.id, !liked) }?.let { info = it } }
                    }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text((if (liked) "❤️ " else "🤍 ") + compactNum((info?.likes ?: 0).toLong())) }
                    OutlinedButton({ showComments = !showComments }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("💬 " + compactNum((info?.comments?.size ?: 0).toLong()))
                    }
                    OutlinedButton(onClose, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Close") }
                    if (mine) OutlinedButton({
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { Api.deletePost(server, p.id) }
                            if (ok) onDeleted()
                        }
                    }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Delete") }
                }
            }
        }
    }
}
