package com.example.livestream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

const val MAX_VIDEO_MS = 8000L          // videos may be 8 seconds at most
const val MAX_VIDEO_BYTES = 12_000_000L // and 12 MB
const val MAX_PHOTO_BYTES = 550_000

class PostPrep(val kind: String, val file: File, val thumb: String)

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

// Video: must be an MP4 of at most 8 seconds and 12 MB; frames at 0, 2, 4 and 6 seconds are checked on the phone.
private fun prepareVideo(ctx: Context, uri: Uri, out: File): Pair<PostPrep?, String> {
    try {
        var total = 0L
        val copied = ctx.contentResolver.openInputStream(uri)?.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_VIDEO_BYTES) return Pair(null, "The video is too large (12 MB max). Pick a shorter clip.")
                    o.write(buf, 0, n)
                }
            }
            true
        } ?: false
        if (!copied) return Pair(null, "Could not read the video")
        val head = ByteArray(12)
        out.inputStream().use { it.read(head) }
        if (!(head[4] == 0x66.toByte() && head[5] == 0x74.toByte() && head[6] == 0x79.toByte() && head[7] == 0x70.toByte()))
            return Pair(null, "Only MP4 videos can be posted")
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(out.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur <= 0L) return Pair(null, "Could not read the video")
            if (dur > MAX_VIDEO_MS + 300L) return Pair(null, "The video must be 8 seconds or shorter (yours is ${dur / 1000} s)")
            var thumbSrc: Bitmap? = null
            for (t in listOf(0L, 2000L, 4000L, 6000L).filter { it < dur }) {
                val f = r.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                if (LocalGuard.suspicious(f)) return Pair(null, "This video looks inappropriate and cannot be posted")
                if (thumbSrc == null || t == 2000L) thumbSrc = f
            }
            val ts = thumbSrc ?: return Pair(null, "Could not read the video")
            return Pair(PostPrep("video", out, squareThumbOf(ts)), "")
        } finally {
            try { r.release() } catch (e: Exception) {}
        }
    } catch (e: Exception) {
        return Pair(null, "Could not read the video")
    }
}

@Composable
fun PostsScreen(server: String, user: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var posts by remember { mutableStateOf(listOf<Api.Post>()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }
    var showChoose by remember { mutableStateOf(false) }
    var prep by remember { mutableStateOf<PostPrep?>(null) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Api.Post?>(null) }

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
            val out = File(ctx.cacheDir, if (kind == "video") "post_upload.mp4" else "post_upload.jpg")
            val r = withContext(Dispatchers.Default) { if (kind == "video") prepareVideo(ctx, uri, out) else preparePhoto(ctx, uri, out) }
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
                val bmp by produceState<ImageBitmap?>(null, p.id) { value = Remote.image("post/${p.id}/thumb", 400, true) }
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF333333)).clickable { viewing = p }) {
                    bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (p.kind == "video") Text("▶", color = Color.White, fontSize = 30.sp, modifier = Modifier.align(Alignment.Center))
                    Text(p.user, color = Color.White, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x88000000)).padding(horizontal = 5.dp, vertical = 2.dp))
                }
            }
        }
    }

    if (showChoose) AlertDialog(
        onDismissRequest = { showChoose = false },
        title = { Text("New post") },
        text = { Text("A photo, or a video of 8 seconds at most (MP4, up to 12 MB).") },
        confirmButton = { Button({ showChoose = false; pickPhoto.launch("image/*") }) { Text("📷 Photo") } },
        dismissButton = { OutlinedButton({ showChoose = false; pickVideo.launch("video/*") }) { Text("🎬 Video") } }
    )

    prep?.let { p ->
        AlertDialog(
            onDismissRequest = { if (!busy) prep = null },
            title = { Text(if (p.kind == "video") "Post this video" else "Post this picture") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(caption, { caption = it.take(100) }, label = { Text("Caption (optional)") }, modifier = Modifier.fillMaxWidth())
                    if (msg.isNotEmpty()) Text(msg)
                }
            },
            confirmButton = {
                Button({
                    scope.launch {
                        busy = true; msg = "Uploading…"
                        val err = withContext(Dispatchers.IO) {
                            try {
                                val id = Api.createPost(server, p.kind, caption.trim(), p.thumb)
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
            onDeleted = { viewing = null; scope.launch { refresh() } })
    }
}

@Composable
private fun PostViewer(server: String, p: Api.Post, mine: Boolean, onClose: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (p.kind == "video") {
                val path by produceState<String?>(null, p.id) {
                    value = withContext(Dispatchers.IO) {
                        val f = File(ctx.cacheDir, "post_${p.id}.mp4")
                        if ((f.exists() && f.length() > 0L) || Api.downloadTo(server, "post/${p.id}", f)) f.absolutePath else null
                    }
                }
                val pa = path
                if (pa == null) Text("Loading…", color = Color.White)
                else AndroidView(factory = { c ->
                    VideoView(c).apply {
                        setVideoPath(pa)
                        setOnPreparedListener { mp -> mp.isLooping = true; start() }
                    }
                })
            } else {
                val img by produceState<ImageBitmap?>(null, p.id) { value = Remote.image("post/${p.id}", 1600, false) }
                val b = img
                if (b == null) Text("Loading…", color = Color.White)
                else Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.user, 36.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(p.user, color = Color.White, fontSize = 15.sp)
                    if (p.caption.isNotEmpty()) Text(p.caption, color = Color(0xFFDDDDDD), fontSize = 13.sp)
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClose) { Text("Close") }
                if (mine) OutlinedButton({
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Api.deletePost(server, p.id) }
                        if (ok) onDeleted()
                    }
                }) { Text("Delete") }
            }
        }
    }
}
