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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.asAndroidBitmap
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
                Text("Text only, a photo, or a video of 8 seconds at most (MP4, up to 12 MB).")
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
