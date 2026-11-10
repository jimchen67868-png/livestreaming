package com.example.livestream

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

object SwipeBus {
    var onDrag: ((Float) -> Unit)? = null      // finger moved sideways by dx pixels since touch-down
    var onRelease: ((Float) -> Unit)? = null   // finger lifted after a sideways drag of dx pixels
}

class MainActivity : ComponentActivity() {
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var maxDx = 0f
    private var maxDy = 0f

    private fun note(key: String, v: String) {
        getSharedPreferences("diag", 0).edit().putString(key, v).commit()
    }
    private fun now() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    // Looks at every touch (video, chat, buttons) and reports sideways drags. It never blocks the touch.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false; maxDx = 0f; maxDy = 0f }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (Math.abs(dx) > maxDx) maxDx = Math.abs(dx)
                if (Math.abs(dy) > maxDy) maxDy = Math.abs(dy)
                if (!dragging && Math.abs(dx) > 16f && Math.abs(dx) > Math.abs(dy)) dragging = true
                if (dragging) SwipeBus.onDrag?.invoke(dx)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                note("swipe", now() + " drag=" + dragging + " maxDx=" + maxDx.toInt() + " maxDy=" + maxDy.toInt() + " handler=" + (SwipeBus.onDrag != null))
                if (dragging) SwipeBus.onRelease?.invoke(ev.rawX - downX)
                dragging = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        try { FloatChat.hide(this) } catch (e: Exception) {}
        note("destroy", now() + " changing=" + isChangingConfigurations + " finishing=" + isFinishing)
        super.onDestroy()
    }

    override fun onStart() { super.onStart(); appVisible = true; clearChatNotification(this) }
    override fun onStop() { appVisible = false; super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { note("crash", now() + " " + e + " | cause: " + e.cause + " | " + e.stackTrace.take(4).joinToString(" <- ")) } catch (x: Exception) {}
            old?.uncaughtException(t, e)
        }
        note("create", now() + " restored=" + (savedInstanceState != null))
        LocalGuard.load(applicationContext)
        LiveAlerts.schedule(applicationContext)
        GenderGuard.load(applicationContext)
        AppTheme.load(getSharedPreferences("s", 0))
        OpenRoom.fromIntent(intent)
        setContent { MaterialTheme(colorScheme = AppTheme.scheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }

    // the app is already open and a notification is tapped
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OpenRoom.fromIntent(intent)
    }
}

sealed interface Screen {
    object Home : Screen
    data class Watch(val stream: Api.Stream) : Screen
    object Live : Screen
    object Posts : Screen
    object MyProfile : Screen
    data class Guest(val room: String, val from: String) : Screen
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("s", 0) }
    var server by remember { mutableStateOf(if (DEFAULT_SERVER.isNotBlank()) DEFAULT_SERVER else sp.getString("server", "") ?: "") }
    SideEffect { Api.serverUrl = server }
    // guests get a random name (kept on this phone); logged-in accounts use their account name
    var user by remember { mutableStateOf(sp.getString("acct", "")?.takeIf { it.isNotEmpty() } ?: makeGuestName(sp)) }
    remember { Api.auth = sp.getString("auth", "") ?: ""; true }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var postsReturn by remember { mutableStateOf<Screen>(Screen.Home) } // where Back goes from the Posts screen
    var postsNew by remember { mutableStateOf(false) } // open the Posts screen with the new-post choices already showing
    // a tapped "is live" notification: go straight into that live (not while you are broadcasting)
    val wanted = OpenRoom.pending.value
    LaunchedEffect(wanted) {
        if (wanted != null) {
            if (screen !is Screen.Live && screen !is Screen.Guest) screen = Screen.Watch(wanted)
            OpenRoom.pending.value = null
        }
    }
    // dark icons in the status bar on the light Home screen; video screens stay dark
    val act = ctx as? android.app.Activity
    SideEffect {
        act?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightStatusBars = (screen is Screen.Home || screen is Screen.Posts) && !AppTheme.dark
        }
    }
    when (val s = screen) {
        Screen.Home -> HomeScreen(server, user,
            { sv, u -> server = sv; user = u; sp.edit().putString("server", sv).putString("user", u).apply() },
            { screen = Screen.Watch(it) }, { screen = Screen.Live }, { postsReturn = Screen.Home; postsNew = false; screen = Screen.Posts }, { screen = Screen.MyProfile })
        Screen.Posts -> PostsScreen(server, user, postsNew) { screen = postsReturn }
        Screen.MyProfile -> MyProfileScreen(server, user,
            { sv, u -> server = sv; user = u; sp.edit().putString("server", sv).putString("user", u).apply() },
            { postsReturn = Screen.MyProfile; postsNew = true; screen = Screen.Posts }) { screen = Screen.Home }
        is Screen.Watch -> key(s.stream.id) {
            WatchScreen(server, user, s.stream, { id -> screen = Screen.Watch(Api.Stream(id, "PK battle", "", 0)) }) {
                screen = Screen.Home
            }
        }
        Screen.Live -> PermGate({ screen = Screen.Home }) {
            BroadcastContent(server, user, { room, from -> screen = Screen.Guest(room, from) }) { screen = Screen.Home }
        }
        is Screen.Guest -> PermGate({ screen = Screen.Home }) {
            GuestScreen(server, user, s.room, s.from) { screen = Screen.Home }
        }
    }
}

// ---------- helpers ----------
suspend fun Room.send(o: JSONObject) {
    localParticipant.publishData(o.toString().toByteArray())
}

// broadcaster = the message comes from the host, whose name is marked "(broadcaster)"
fun chatLine(name: String, o: JSONObject, broadcaster: Boolean = false): String? = when (o.optString("t")) {
    "chat" -> "${if (broadcaster) "$name (broadcaster)" else name}: ${o.optString("text")}"
    "gift" -> "$name sent ${o.optString("g")}"
    else -> null
}

fun parseJson(b: ByteArray): JSONObject? = try { JSONObject(String(b)) } catch (e: Exception) { null }
fun MutableList<String>.addCapped(s: String) { add(s); while (size > 200) removeAt(0) }
// Gifts the host sends to a viewer (viewers send ❤️ 🌹 🚀 to the host).
val HOST_GIFTS = listOf("🎁", "🍬", "🏆", "💖", "🎉")
// Lumis the host earns per gift (the server uses the same amounts)
// Gifts viewers can send (in random order, prices not tied to the picture), with the lumis the host earns (the worker has the same table, 1 to 30000).
// Animation: under 50 lumis none; 50 to 999 only the gifts in MID_ANIMATED; 1000 and up always. All gifts show the sender banner.
val VIEWER_GIFTS = listOf("🎇" to 3000, "🐘" to 50, "🐱" to 40, "🎻" to 1000, "🍦" to 15, "🧸" to 20, "🎠" to 15, "💋" to 700, "🎡" to 30, "🦄" to 2000, "🗼" to 2000, "🚗" to 100, "🎆" to 3000, "🌌" to 3, "🚕" to 150, "🎀" to 700, "🎹" to 20000, "🍔" to 5, "🎧" to 80, "🍷" to 40, "💍" to 200, "🍫" to 10, "🦅" to 30000, "🍩" to 20, "🚢" to 2, "🏰" to 20000, "🗽" to 1500, "🍓" to 3, "🌹" to 1, "🛸" to 8, "🎤" to 1000, "📸" to 5, "🌷" to 100, "🌙" to 30, "🌃" to 5000, "🎪" to 50, "🌈" to 1, "🍾" to 2, "🔔" to 1500, "🐉" to 10000, "🏠" to 200, "🎈" to 50, "🍟" to 10000, "🦁" to 5, "🚁" to 20, "🎂" to 5000, "👑" to 60, "🚒" to 500, "🌅" to 30, "🍰" to 2, "🌠" to 150, "🎮" to 10, "🦋" to 300, "❤️" to 10, "🌋" to 300, "🍕" to 80, "🐶" to 3000, "🎸" to 60, "🍪" to 8, "🚀" to 40, "🚤" to 100, "☕" to 200, "🍀" to 15, "🎨" to 8, "🌉" to 1000, "🚅" to 500, "⛴️" to 30000)
fun lumisValue(g: String): Int { val k = g.replace("\uFE0F", ""); return VIEWER_GIFTS.firstOrNull { it.first.replace("\uFE0F", "") == k }?.second ?: 0 }
// host gifts to viewers (0 lumis) always animate
// the 50-999 lumis gifts that have the animation (fixed, same for everyone); the other gifts in that range have none
val MID_ANIMATED = setOf("🚕", "🚅", "☕", "🐘", "🎈", "🎪", "🚤", "💋", "🎧", "🍕")
fun giftAnimated(g: String): Boolean { val v = lumisValue(g); return v == 0 || v >= 1000 || (v >= 50 && g.replace("\uFE0F", "") in MID_ANIMATED) }
fun giftValue(g: String) = (lumisValue(g) * 4).coerceAtLeast(1)
fun isGuest(id: String?) = id?.startsWith("guest-") == true
fun isHost(id: String?) = id?.startsWith("host-") == true
fun pkJson(l: Int, r: Int, left: Int, end: Boolean) =
    JSONObject().put("t", "pk").put("l", l).put("r", r).put("left", left).put("end", end)
fun resultText(l: Int, r: Int, ln: String, rn: String) =
    if (l > r) "🏆 $ln wins!" else if (r > l) "🏆 $rn wins!" else "🤝 Draw"

fun viewerCount(room: Room) = room.remoteParticipants.values.count { !isGuest(it.identity?.value) }
// Guests are named guest1234; the server does not let accounts use such names.
fun isGuestAccount(n: String) = Regex("(?i)guest\\d{4}").matches(n.removeSuffix(" (you)").trim())
fun viewerNames(room: Room): List<String> = room.remoteParticipants.values
    .filter { !isHost(it.identity?.value) && !isGuest(it.identity?.value) }
    .map { it.name ?: it.identity?.value ?: "guest" }

fun remoteVideo(room: Room, guest: Boolean): VideoTrack? = room.remoteParticipants.values
    .filter { isGuest(it.identity?.value) == guest }
    .firstNotNullOfOrNull { it.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack }

fun captureFrame(v: TextureViewRenderer, w: Int = 384): Bitmap? = try {
    if (v.width == 0 || v.height == 0) null
    else v.getBitmap(w, (w.toFloat() * v.height / v.width).toInt().coerceAtLeast(1))
} catch (e: Exception) { null }

fun jpegB64(b: Bitmap, quality: Int): String {
    val bos = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, quality, bos)
    return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
}

fun squareThumb(b: Bitmap, out: Int = 160): String {
    val side = minOf(b.width, b.height)
    val sq = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    return jpegB64(Bitmap.createScaledBitmap(sq, out, out, true), 60)
}

/**
 * Cheap on-device pre-filter: share of skin-coloured pixels outside detected faces.
 * It only decides whether a frame is worth sending to the server for a real check,
 * so it is allowed to be crude (false alarms just cost one server check).
 */
// ---------- rule: no man + woman together in one camera (needs assets/gender.tflite) ----------
object GenderGuard {
    private var interp: Interpreter? = null

    fun load(ctx: Context) {
        if (interp != null) return
        interp = try {
            val fd = ctx.assets.openFd("gender.tflite")
            Interpreter(FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength))
        } catch (e: Exception) { null }
    }

    // true when one face is classified as one gender and another face as the other, both with confidence >= 0.85
    // (works whichever way round the model orders its two classes)
    @Synchronized
    fun mixed(src: Bitmap): Boolean {
        val it = interp ?: return false
        return try {
            val w = 480
            val h = ((w.toFloat() * src.height / src.width).toInt() / 2 * 2).coerceAtLeast(16)
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val b565 = small.copy(Bitmap.Config.RGB_565, false)
            val found = arrayOfNulls<android.media.FaceDetector.Face>(6)
            val n = android.media.FaceDetector(w, h, 6).findFaces(b565, found)
            val pt = android.graphics.PointF()
            var a = 0
            var b = 0
            for (i in 0 until n) {
                val f = found[i] ?: continue
                if (f.confidence() < 0.4f) continue
                f.getMidPoint(pt)
                val half = (1.5f * f.eyesDistance()).toInt().coerceAtLeast(8)
                val left = (pt.x - half).toInt().coerceIn(0, w - 2)
                val top = (pt.y - half).toInt().coerceIn(0, h - 2)
                val size = minOf(2 * half, w - left, h - top).coerceAtLeast(2)
                val face = Bitmap.createScaledBitmap(Bitmap.createBitmap(small, left, top, size, size), 128, 128, true)
                val px = IntArray(128 * 128)
                face.getPixels(px, 0, 128, 0, 0, 128, 128)
                val buf = ByteBuffer.allocateDirect(4 * 128 * 128 * 3).order(ByteOrder.nativeOrder())
                for (p in px) {
                    buf.putFloat(((p shr 16) and 0xFF) / 255f)
                    buf.putFloat(((p shr 8) and 0xFF) / 255f)
                    buf.putFloat((p and 0xFF) / 255f)
                }
                buf.rewind()
                val out = Array(1) { FloatArray(2) }
                it.run(buf, out)
                var p0 = out[0][0]
                var p1 = out[0][1]
                val sum = p0 + p1
                if (p0 < 0f || p1 < 0f || sum < 0.9f || sum > 1.1f) {   // logits -> probabilities
                    val e0 = Math.exp(p0.toDouble()); val e1 = Math.exp(p1.toDouble())
                    p0 = (e0 / (e0 + e1)).toFloat(); p1 = 1f - p0
                }
                if (p0 >= 0.85f) a++ else if (p1 >= 0.85f) b++
            }
            a > 0 && b > 0
        } catch (e: Exception) { false }
    }
}

object LocalGuard {
    private const val THRESHOLD = 0.45f   // raise to check less often, lower to be stricter

    // Optional ML model (assets/nsfw.tflite). If it is missing, the skin-colour check below is used.
    private var interp: Interpreter? = null

    fun load(ctx: Context) {
        if (interp != null) return
        interp = try {
            val fd = ctx.assets.openFd("nsfw.tflite")
            Interpreter(FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength))
        } catch (e: Exception) { null }
    }

    // classes: drawings, hentai, neutral, porn, sexy
    @Synchronized
    private fun modelScore(src: Bitmap): Float? {
        val it = interp ?: return null
        return try {
            val s = Bitmap.createScaledBitmap(src, 224, 224, true)
            val px = IntArray(224 * 224)
            s.getPixels(px, 0, 224, 0, 0, 224, 224)
            val buf = ByteBuffer.allocateDirect(4 * 224 * 224 * 3).order(ByteOrder.nativeOrder())
            for (p in px) {
                buf.putFloat(((p shr 16) and 0xFF) / 255f)
                buf.putFloat(((p shr 8) and 0xFF) / 255f)
                buf.putFloat((p and 0xFF) / 255f)
            }
            buf.rewind()
            val out = Array(1) { FloatArray(5) }
            it.run(buf, out)
            out[0][3] + out[0][1] + 0.25f * out[0][4]
        } catch (e: Exception) { null }
    }

    fun suspicious(src: Bitmap): Boolean {
        val m = modelScore(src)
        return if (m != null) m >= 0.5f else skinRatio(src) > THRESHOLD
    }

    private fun isSkin(r: Int, g: Int, b: Int): Boolean {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        return y > 50f && cb in 77f..127f && cr in 133f..173f && r > g && r > b
    }

    fun skinRatio(src: Bitmap): Float {
        val w = 128
        val h = (w.toFloat() * src.height / src.width).toInt().coerceAtLeast(16)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val faces = mutableListOf<android.graphics.RectF>()
        try {
            val b565 = small.copy(Bitmap.Config.RGB_565, false)
            val found = arrayOfNulls<android.media.FaceDetector.Face>(4)
            val n = android.media.FaceDetector(w, h, 4).findFaces(b565, found)
            val pt = android.graphics.PointF()
            for (i in 0 until n) {
                val f = found[i] ?: continue
                f.getMidPoint(pt)
                val e = f.eyesDistance()
                faces.add(android.graphics.RectF(pt.x - 1.8f * e, pt.y - 1.6f * e, pt.x + 1.8f * e, pt.y + 2.6f * e))
            }
        } catch (e: Exception) {}
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        var skin = 0
        var total = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (faces.any { it.contains(x.toFloat(), y.toFloat()) }) continue
            total++
            val c = px[y * w + x]
            if (isSkin((c shr 16) and 255, (c shr 8) and 255, c and 255)) skin++
        }
        return if (total == 0) 0f else skin / total.toFloat()
    }
}

// "US" -> flag emoji + space; "" when unknown.
fun flag(cc: String): String =
    if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) ""
    else cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("") + " "

object LiveType {
    // Coarse type from the camera frame: no face = scene, one face = talk, several = group.
    fun detect(src: Bitmap): String = try {
        val w = 320
        val h = ((w.toFloat() * src.height / src.width).toInt() / 2 * 2).coerceAtLeast(16)
        val b = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.RGB_565, false)
        val found = arrayOfNulls<android.media.FaceDetector.Face>(4)
        val n = android.media.FaceDetector(w, h, 4).findFaces(b, found)
        var faces = 0
        for (i in 0 until n) if ((found[i]?.confidence() ?: 0f) >= 0.4f) faces++
        if (faces == 0) "scene" else if (faces == 1) "talk" else "group"
    } catch (e: Exception) { "" }

    fun icon(t: String) = when (t) { "talk" -> "💬 "; "group" -> "👥 "; "scene" -> "🎬 "; "pk" -> "⚔️ "; "screen" -> "🖥 "; "audio" -> "🎙 "; else -> "" }
}

// What this viewer watches: +1 per 30 s visit, older visits fade (x0.95 each time).
object Pref {
    private val types = listOf("talk", "group", "scene", "pk", "screen", "audio")
    fun score(ctx: Context, t: String): Float =
        if (t.isEmpty()) 0f else ctx.getSharedPreferences("s", 0).getFloat("pref_$t", 0f)

    fun bump(ctx: Context, t: String) {
        if (t.isEmpty()) return
        val sp = ctx.getSharedPreferences("s", 0)
        val e = sp.edit()
        for (k in types) e.putFloat("pref_$k", sp.getFloat("pref_$k", 0f) * 0.95f + (if (k == t) 1f else 0f))
        e.apply()
    }
}

// ---------- YouTube synced player (audio rooms) ----------
fun youtubeId(s: String): String? {
    val t = s.trim()
    if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) return t
    return Regex("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").find(t)?.groupValues?.get(1)
}

fun findActivity(ctx: Context): Activity? {
    var c: Context = ctx
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// the player library keeps its page inside a WebView somewhere in its view tree
fun findWebView(v: android.view.View): android.webkit.WebView? {
    if (v is android.webkit.WebView) return v
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) {
        val w = findWebView(v.getChildAt(i))
        if (w != null) return w
    }
    return null
}

// "RATE_1_5" -> 1.5
fun rateValue(r: PlayerConstants.PlaybackRate): Double =
    if (r.name.startsWith("RATE_")) r.name.removePrefix("RATE_").replace('_', '.').toDoubleOrNull() ?: 1.0 else 1.0

// Host: has the player controls and reports id / play state / position every 2 s.
// Viewer: no controls, follows the host's latest message (`cmd`).
@Composable
fun YtView(modifier: Modifier, host: Boolean, cmd: JSONObject?, onHostState: (JSONObject) -> Unit = {}) {
    val ctx = LocalContext.current
    var st by remember { mutableStateOf("Starting player…") }
    val player = remember { arrayOfNulls<YouTubePlayer>(1) }
    val view = remember { arrayOfNulls<YouTubePlayerView>(1) }
    val pos = remember { doubleArrayOf(0.0) }
    val playing = remember { booleanArrayOf(false) }
    val vid = remember { arrayOf("") }
    val latest by rememberUpdatedState(cmd)

    val lastSeek = remember { longArrayOf(0L) }
    val rate = remember { doubleArrayOf(1.0) }
    val pendingPause = remember { booleanArrayOf(false) }
    val capPending = remember { booleanArrayOf(true) }
    var ccOn by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }
    val act = remember { findActivity(ctx) }

    // viewer's own subtitle switch: the host's setting cannot be read, so viewers start with subtitles off
    fun setCaptions(on: Boolean) {
        val w = view[0]?.let { findWebView(it) } ?: return
        w.evaluateJavascript(if (on) "player.loadModule('captions')" else "player.unloadModule('captions')", null)
    }

    fun applyCmd(c: JSONObject) {
        val p = player[0] ?: return
        val id = c.optString("id")
        if (id.isEmpty()) return
        val play = c.optBoolean("play", true)
        var want = c.optDouble("pos", 0.0)
        val ts = c.optLong("ts", 0L)
        val hostRate = c.optDouble("rate", 1.0)
        // the message is a little old when it arrives: while playing, add the time it spent travelling
        if (play && ts > 0) want += ((System.currentTimeMillis() - ts) / 1000.0).coerceIn(0.0, 3.0) * hostRate
        if (id != vid[0]) {
            vid[0] = id
            capPending[0] = true
            // a new video needs ~1 s to start, so start a bit ahead
            p.loadVideo(id, (want + (if (play) 0.8 else 0.0)).toFloat())
            lastSeek[0] = System.currentTimeMillis()
            // host is paused: let the first picture appear, THEN pause (pausing too early leaves a black screen)
            pendingPause[0] = !play
            return
        }
        if (play && !playing[0]) p.play()
        if (!play && playing[0]) p.pause()
        if (Math.abs(rate[0] - hostRate) > 0.01) {
            PlayerConstants.PlaybackRate.values()
                .firstOrNull { it.name.startsWith("RATE_") && Math.abs(rateValue(it) - hostRate) < 0.01 }
                ?.let { p.setPlaybackRate(it) }
        }
        val now = System.currentTimeMillis()
        if (Math.abs(pos[0] - want) > 1.5 && now - lastSeek[0] > 5000) {
            p.seekTo(want.toFloat())
            lastSeek[0] = now
        }
    }

    val listener = remember {
        object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player[0] = youTubePlayer
                st = ""
                latest?.let { applyCmd(it) }
            }
            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                playing[0] = state == PlayerConstants.PlayerState.PLAYING
                if (playing[0]) {
                    st = ""
                    if (pendingPause[0]) { pendingPause[0] = false; youTubePlayer.pause() }
                    if (!host && capPending[0]) { capPending[0] = false; setCaptions(ccOn) }
                }
            }
            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) { pos[0] = second.toDouble() }
            override fun onVideoId(youTubePlayer: YouTubePlayer, videoId: String) { vid[0] = videoId }
            override fun onPlaybackRateChange(youTubePlayer: YouTubePlayer, playbackRate: PlayerConstants.PlaybackRate) { rate[0] = rateValue(playbackRate) }
            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                st = "YouTube cannot play this video here ($error)"
            }
        }
    }

    Box(modifier.background(Color.Black).clipToBounds()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Viewers: the player is made taller and pushed up so YouTube's title / share strip falls outside the screen.
            // The player library keeps the picture in a 16:9 box. Zoom enlarges that box so a tall (portrait) video fills the screen.
            val zs = if (zoom) (maxHeight.value / (maxWidth.value * 9f / 16f)).coerceIn(1f, 3f) else 1f
            AndroidView(
                modifier = (if (host) Modifier.fillMaxSize()
                else Modifier.requiredSize(maxWidth, maxHeight + 72.dp).offset(y = (-72).dp))
                    .graphicsLayer(scaleX = zs, scaleY = zs),
                factory = { c ->
                    YouTubePlayerView(c).also { v ->
                        v.enableAutomaticInitialization = false
                        v.initialize(listener, true, IFramePlayerOptions.Builder(c).controls(if (host) 1 else 0).build())
                        view[0] = v
                    }
                }
            )
        }
        if (st.isNotEmpty()) Text(st, color = Color.White, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000)))
        Row(Modifier.align(Alignment.TopEnd)) {
            TextButton({ zoom = !zoom }) { Text(if (zoom) "Zoom ✓" else "Zoom", style = MaterialTheme.typography.bodySmall) }
            TextButton({
                full = !full
                act?.requestedOrientation = if (full) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }) {
                Text(if (full) "Exit full screen" else "Full screen", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!host) TextButton({ ccOn = !ccOn; setCaptions(ccOn) }, Modifier.align(Alignment.BottomEnd)) {
            Text(if (ccOn) "Subtitles: on" else "Subtitles: off", style = MaterialTheme.typography.bodySmall)
        }
        TextButton({
            val id = if (vid[0].isNotEmpty()) vid[0] else latest?.optString("id") ?: ""
            if (id.isNotEmpty()) try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://youtu.be/" + id)))
            } catch (e: Exception) {}
        }, Modifier.align(Alignment.BottomStart)) { Text("Open in YouTube", style = MaterialTheme.typography.bodySmall) }
    }

    LaunchedEffect(cmd) { if (cmd != null) applyCmd(cmd) }
    LaunchedEffect(host) {
        if (host) while (true) {
            delay(1000)
            if (vid[0].isNotEmpty()) onHostState(JSONObject().put("id", vid[0]).put("play", playing[0]).put("pos", pos[0]).put("rate", rate[0]).put("ts", System.currentTimeMillis()))
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try { view[0]?.release() } catch (e: Exception) {}
            try { act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } catch (e: Exception) {}
        }
    }
}

const val BUILD_TAG = "b135-claim"

// Your Worker address. When set, the app uses it and the Server URL box is hidden. Leave "" to type it in the app.
const val DEFAULT_SERVER = "https://old-moon-cd21.jimchen67688.workers.dev"

fun exitName(r: Int) = when (r) {
    1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"; 6 -> "ANR"
    7 -> "INIT_FAILURE"; 8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE"; 10 -> "USER_REQUESTED"
    11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; else -> "UNKNOWN($r)"
}

fun diagText(ctx: Context): String {
    val d = ctx.getSharedPreferences("diag", 0)
    val sb = StringBuilder("Build: $BUILD_TAG\n")
    sb.append("create: ").append(d.getString("create", "-")).append("\n")
    sb.append("destroy: ").append(d.getString("destroy", "-")).append("\n")
    sb.append("swipe: ").append(d.getString("swipe", "-")).append("\n")
    sb.append("crash: ").append(d.getString("crash", "none")).append("\n")
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            for (x in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 4)) {
                sb.append("exit: ").append(exitName(x.reason)).append(" ")
                    .append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(x.timestamp)))
                    .append(" ").append(x.description ?: "").append("\n")
            }
        } catch (e: Exception) {}
    }
    return sb.toString()
}

fun decodeThumb(b64: String): ImageBitmap? = try {
    if (b64.isEmpty()) null else {
        val b = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
    }
} catch (e: Exception) { null }

// ---------- shared UI ----------
// "9:16" -> 0.5625 ; "auto" / unknown -> null (no frame)
fun ratioValue(s: String): Float? = when (s) { "9:16" -> 9f / 16f; "16:9" -> 16f / 9f; "1:1" -> 1f; else -> null }

// Fill = crop the picture to fill the view, Fit = show the whole picture. Done by name so it cannot break the build.
fun setFillMode(r: TextureViewRenderer, fill: Boolean) {
    try {
        val m = r.javaClass.methods.firstOrNull { it.name == "setScalingType" && it.parameterTypes.size == 1 }
        val c = m?.parameterTypes?.get(0)?.enumConstants?.firstOrNull {
            (it as Enum<*>).name == (if (fill) "SCALE_ASPECT_FILL" else "SCALE_ASPECT_FIT")
        }
        if (m != null && c != null) m.invoke(r, c)
    } catch (e: Exception) {}
}

@Composable
fun VideoView(room: Room, track: VideoTrack?, modifier: Modifier = Modifier, onReady: (TextureViewRenderer) -> Unit = {}, scaleFill: Boolean? = null) {
    var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
    LaunchedEffect(scaleFill, renderer) { val r = renderer; if (r != null && scaleFill != null) setFillMode(r, scaleFill) }
    AndroidView(
        factory = { c -> TextureViewRenderer(c).also { room.initVideoRenderer(it); renderer = it; onReady(it) } },
        modifier = modifier
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        if (track != null && r != null) track.addRenderer(r)
        onDispose { if (track != null && r != null) track.removeRenderer(r) }
    }
}

@Composable
fun ViewerListDialog(names: List<String>, onGift: ((String, String) -> Unit)? = null, onClose: () -> Unit) {
    val fctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var giftFor by remember { mutableStateOf("") }
    var profileFor by remember { mutableStateOf("") }
    if (profileFor.isNotEmpty()) ProfileDialog(profileFor.replace(" (you)", "").replace(" (host)", ""), profileFor.endsWith(" (host)"), profileFor.contains(" (you)")) { profileFor = "" }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("Close") } },
        title = { Text("Viewers (${names.count { !it.endsWith(" (host)") }})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (names.size <= 1) Text("No viewers yet")
                // host first, then registered users, then guests; A-Z inside each group
                names.sortedWith(compareBy<String>({ !it.endsWith(" (host)") }, { isGuestAccount(it) }, { it.lowercase() })).forEach { n ->
                    val clean = n.replace(" (you)", "").replace(" (host)", "")
                    val isYou = n.contains(" (you)")
                    val isHostRow = n.endsWith(" (host)")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // tap a name to see that person's profile
                        Row(Modifier.weight(1f).clickable { profileFor = n }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(clean, 30.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isHostRow) n.replace(" (you) (host)", " (host, you)") else n)
                        }
                        // the host can send a gift to a viewer (a different set from the gifts viewers send to the host)
                        if (onGift != null && !isYou && !isHostRow) {
                            TextButton({ giftFor = if (giftFor == n) "" else n }) { Text("\uD83C\uDF81") }
                        }
                        // guests have no profile, so they cannot be followed
                        if (Api.auth.isNotEmpty() && !isYou && !isGuestAccount(clean)) {
                            TextButton({ Follows.toggle(fctx, clean); tick++ }) {
                                Text(if (tick >= 0 && Follows.has(fctx, clean)) "Following \u2713" else "+ Follow")
                            }
                        }
                    }
                    if (onGift != null && giftFor == n) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HOST_GIFTS.forEach { g -> FilledTonalButton({ onGift(n, g); giftFor = "" }) { Text(g) } }
                    }
                }
            }
        }
    )
}

@Composable
fun ProfileDialog(name: String, isHost: Boolean, isMe: Boolean, onClose: () -> Unit) {
    val fctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val guest = isGuestAccount(name)
    val cover by produceState<ImageBitmap?>(null, name) {
        value = if (guest) null else withContext(Dispatchers.IO) {
            Api.fetchBytes(Api.serverUrl, "cover?u=" + java.net.URLEncoder.encode(name, "UTF-8"))?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        }
    }
    // numbers + posts from the server (first uploads the follows that were only on this phone)
    val info by produceState<Api.ProfileInfo?>(null, name) {
        value = if (guest) null else withContext(Dispatchers.IO) {
            Api.syncFollows(Api.serverUrl, Follows.list(fctx))
            Api.profile(Api.serverUrl, name)
        }
    }
    var viewingPost by remember { mutableStateOf<Api.Post?>(null) }
    val following0 = remember(name) { Follows.has(fctx, name) } // state when the dialog opened (the server count already includes it)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("Close") } },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(name, 96.dp)
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text(when { isHost -> "Broadcaster"; guest -> "Guest (no profile)"; isMe -> "This is you"; else -> "Member" }, style = MaterialTheme.typography.bodySmall)
                if (!guest) {
                    val nowFollowing = tick >= 0 && Follows.has(fctx, name)
                    val adj = (if (nowFollowing) 1 else 0) - (if (following0) 1 else 0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatBox("Lumis", info?.received?.let { compactNum(it) } ?: "-")
                        StatBox("Diamonds", info?.spent?.let { compactNum(it) } ?: "-")
                        StatBox("Followers", info?.let { compactNum((it.followers + adj).coerceAtLeast(0).toLong()) } ?: "-")
                        StatBox("Following", info?.following?.let { compactNum(it.toLong()) } ?: "-")
                    }
                }
                cover?.let { Image(it, null, Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
                if (Api.auth.isNotEmpty() && !isMe && !guest) {
                    Button({ Follows.toggle(fctx, name); tick++ }) {
                        Text(if (tick >= 0 && Follows.has(fctx, name)) "Following \u2713" else "+ Follow")
                    }
                }
                val posts = info?.posts.orEmpty()
                if (!guest) {
                    Text(if (info == null) "Loading..." else if (posts.isEmpty()) "No posts yet" else "Posts", style = MaterialTheme.typography.titleSmall)
                    posts.chunked(3).forEach { rowPosts ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowPosts.forEach { p ->
                                val th by produceState<ImageBitmap?>(null, p.id) {
                                    value = if (p.kind == "text") null else withContext(Dispatchers.IO) {
                                        Api.fetchBytes(Api.serverUrl, "post/" + p.id + "/thumb")?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                                    }
                                }
                                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0x22888888)).clickable { viewingPost = p }) {
                                    if (p.kind == "text") Text(p.caption, fontSize = 11.sp, maxLines = 6, modifier = Modifier.padding(5.dp))
                                    th?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                    if (p.kind == "video") Text("\u25B6", color = Color.White, modifier = Modifier.align(Alignment.Center))
                                }
                            }
                            repeat(3 - rowPosts.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    )
    viewingPost?.let { PostViewer(Api.serverUrl, it, mine = isMe && Api.auth.isNotEmpty(), onClose = { viewingPost = null }, onDeleted = { viewingPost = null }) }
}

// My profile page: numbers, posts, and the buttons to post or edit the profile
@Composable
fun MyProfileScreen(server: String, user: String, onSettings: (String, String) -> Unit, onPosts: () -> Unit, onBack: () -> Unit) {
    val fctx = LocalContext.current
    val sp = remember { fctx.getSharedPreferences("s", 0) }
    var showAcct by remember { mutableStateOf(false) }
    var viewingPost by remember { mutableStateOf<Api.Post?>(null) }
    var showBuy by remember { mutableStateOf(false) }
    var wallet by remember { mutableStateOf<Api.Wallet?>(null) } // my own balances (never shown on other people's profiles)
    LaunchedEffect(user, showBuy) { wallet = withContext(Dispatchers.IO) { Api.walletInfo(server) } }
    if (showBuy) BuyDialog(server, wallet?.diamonds ?: -1, { d -> wallet = Api.Wallet(wallet?.lumis ?: 0, d) }) { showBuy = false }
    val info by produceState<Api.ProfileInfo?>(null, user) {
        value = withContext(Dispatchers.IO) {
            Api.syncFollows(Api.serverUrl, Follows.list(fctx))
            Api.profile(Api.serverUrl, user)
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("← Back") }
            Spacer(Modifier.weight(1f))
            TextButton({ showAcct = true }) { Text("⚙ Edit profile / Log out") }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(user, 96.dp)
            Text(user, style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBox("Lumis", info?.received?.let { compactNum(it) } ?: "-")
            StatBox("Diamonds", info?.spent?.let { compactNum(it) } ?: "-")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBox("Followers", info?.followers?.let { compactNum(it.toLong()) } ?: "-")
            StatBox("Following", info?.following?.let { compactNum(it.toLong()) } ?: "-")
        }
        // my diamond balance (what I can still spend on gifts)
        Text("My diamonds: " + (wallet?.diamonds?.toString() ?: "…"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button({ showBuy = true }, Modifier.fillMaxWidth()) { Text("Buy diamonds") }
        Button(onPosts, Modifier.fillMaxWidth()) { Text("➕ Add post") }
        val posts = info?.posts.orEmpty()
        Text(if (info == null) "Loading..." else if (posts.isEmpty()) "No posts yet" else "My posts", style = MaterialTheme.typography.titleSmall)
        posts.chunked(3).forEach { rowPosts ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowPosts.forEach { p ->
                    val th by produceState<ImageBitmap?>(null, p.id) {
                        value = if (p.kind == "text") null else withContext(Dispatchers.IO) {
                            Api.fetchBytes(Api.serverUrl, "post/" + p.id + "/thumb")?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                        }
                    }
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0x22888888)).clickable { viewingPost = p }) {
                        if (p.kind == "text") Text(p.caption, fontSize = 11.sp, maxLines = 6, modifier = Modifier.padding(5.dp))
                        th?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        if (p.kind == "video") Text("\u25B6", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
                repeat(3 - rowPosts.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (showAcct) AccountDialog(server, user, { token, name ->
        Api.auth = token
        sp.edit().putString("auth", token).putString("acct", name).apply()
        onSettings(server, if (name.isNotEmpty()) name else makeGuestName(sp))
        showAcct = false
        if (name.isEmpty()) onBack() // logged out: nothing to show
    }, { showAcct = false; onPosts() }) { showAcct = false }
    viewingPost?.let { PostViewer(Api.serverUrl, it, mine = true, onClose = { viewingPost = null }, onDeleted = { viewingPost = null }) }
}

// diamonds to price in US cents ($5 = 50 diamonds)
val DIAMOND_PACKS = listOf(5 to 50, 10 to 100, 50 to 500, 100 to 1000, 200 to 2000, 500 to 5000, 1000 to 10000, 5000 to 50000)

@Composable
fun BuyDialog(server: String, diamonds: Int, onBalance: (Int) -> Unit, onClose: () -> Unit) {
    val bctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    // while this is open the balance is checked every 3 seconds, so it updates after paying in the browser
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { Api.walletInfo(server) }?.let { onBalance(it.diamonds) }
            delay(3000)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Buy diamonds") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("You have 💎 " + (if (diamonds >= 0) diamonds.toString() else "…"))
                DIAMOND_PACKS.forEach { (n, cents) ->
                    Button({
                        msg = ""
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Api.buy(server, n) }
                            val url = r.first
                            if (url != null) {
                                try { bctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                                catch (e: Exception) { msg = "Could not open the payment page" }
                            } else msg = r.second
                        }
                    }, Modifier.fillMaxWidth()) { Text("💎 $n   –   US$" + "%d.%02d".format(cents / 100, cents % 100)) }
                }
                if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF8A80))
                Text("Pay on the Stripe page, then come back here. Your diamonds appear within a few seconds.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } }
    )
}

// 1234 -> 1.2k, 1500000 -> 1.5M (cut, not rounded up)
fun compactNum(n: Long): String {
    fun one(v: Double, suf: String): String {
        val t = Math.floor(v * 10) / 10
        return (if (t == Math.floor(t)) t.toLong().toString() else t.toString()) + suf
    }
    return when {
        n >= 1_000_000L -> one(n / 1_000_000.0, "M")
        n >= 1_000L -> one(n / 1_000.0, "k")
        else -> n.toString()
    }
}

@Composable
fun StatBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PkBoard(l: Int, r: Int, left: Int, ln: String, rn: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0x99000000)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$ln  $l", color = Color(0xFFFF6B6B))
            Text("⏱ %d:%02d".format(left / 60, left % 60), color = Color.White)
            Text("$r  $rn", color = Color(0xFF6BB6FF))
        }
        Row(Modifier.fillMaxWidth().height(8.dp)) {
            Box(Modifier.weight((l + 1).toFloat()).fillMaxHeight().background(Color(0xFFE53935)))
            Box(Modifier.weight((r + 1).toFloat()).fillMaxHeight().background(Color(0xFF1E88E5)))
        }
    }
}

@Composable
fun SideBtn(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick) { Text(text, maxLines = 1) } else OutlinedButton(onClick) { Text(text, maxLines = 1) }
}

@Composable
fun ChatOverlay(msgs: List<String>, modifier: Modifier = Modifier, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // scrollable: swipe up to read older messages; it follows new messages unless you scrolled up
        val listState = rememberLazyListState()
        val atBottom by remember { derivedStateOf {
            val li = listState.layoutInfo
            li.totalItemsCount == 0 || (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 2
        } }
        LaunchedEffect(msgs.size, msgs.lastOrNull()) { if (atBottom && msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1) }
        LazyColumn(Modifier.heightIn(max = 190.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(count = msgs.size) { i ->
                val it = msgs[i]
                // lines from the host are gold and bold. Only the part before the first ":" is checked, so a viewer cannot fake it in a message.
                val fromBroadcaster = it.substringBefore(":").contains("(broadcaster)")
                Text(it, color = if (fromBroadcaster) Color(0xFFFFD54F) else Color.White,
                    fontWeight = if (fromBroadcaster) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    modifier = Modifier
                        .background(Color(0x66000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        if (Api.auth.isEmpty()) {
            Text("Log in (👤 on Home) to chat", color = Color.White, modifier = Modifier
                .background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, placeholder = { Text("Say something") },
                singleLine = true, modifier = Modifier.weight(1f))
            Button({ if (text.isNotBlank()) { onSend(text); text = "" } }) { Text("Send") }
        }
    }
}

private fun hasPerms(c: Context) = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun PermGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[Manifest.permission.CAMERA] == true && it[Manifest.permission.RECORD_AUDIO] == true
    }
    LaunchedEffect(Unit) {
        if (!granted || !notifAllowed(ctx)) launcher.launch(permsToAsk())
    }
    BackHandler(enabled = !granted, onBack = onBack)
    if (granted) content()
    else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Camera and microphone access is needed to broadcast.")
        Spacer(Modifier.height(12.dp))
        Button({ launcher.launch(permsToAsk()) }) {
            Text("Grant permissions")
        }
    }
}


// ---------- chat notifications for the host while the app is in the background ----------
@Volatile var appVisible = true
private val notifLines = mutableListOf<String>()

fun notifAllowed(ctx: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

fun permsToAsk(): Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

// Shows the newest chat lines as a pop-up banner while the host is in another app (e.g. while sharing the screen).
fun notifyChat(ctx: Context, line: String) {
    if (appVisible) return
    if (FloatChat.canShow(ctx)) { FloatChat.add(ctx, line); return }
    if (!notifAllowed(ctx)) return
    try {
        synchronized(notifLines) { notifLines.add(line); while (notifLines.size > 5) notifLines.removeAt(0) }
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(android.app.NotificationChannel("chatmsg", "Live chat", android.app.NotificationManager.IMPORTANCE_HIGH))
        val open = android.app.PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val style = androidx.core.app.NotificationCompat.InboxStyle()
        val copy = synchronized(notifLines) { notifLines.toList() }
        copy.forEach { style.addLine(it) }
        val n = androidx.core.app.NotificationCompat.Builder(ctx, "chatmsg")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Live chat")
            .setContentText(line)
            .setStyle(style)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(3, n)
    } catch (e: Exception) {}
}

fun clearChatNotification(ctx: Context) {
    try {
        synchronized(notifLines) { notifLines.clear() }
        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(3)
        FloatChat.hide(ctx)
    } catch (e: Exception) {}
}

// Random guest name such as guest4821, made once and kept on this phone.
fun makeGuestName(sp: android.content.SharedPreferences): String {
    val g = sp.getString("guest", "") ?: ""
    if (g.isNotEmpty()) return g
    val n = "guest" + (1000..9999).random()
    sp.edit().putString("guest", n).apply()
    return n
}

// ---------- Home ----------
@Composable
fun HomeScreen(
    server: String, user: String, onSettings: (String, String) -> Unit,
    onOpen: (Api.Stream) -> Unit, onGoLive: () -> Unit, onPosts: () -> Unit, onProfile: () -> Unit
) {
    var streams by remember { mutableStateOf(listOf<Api.Stream>()) }
    var err by remember { mutableStateOf("") }
    var showDiag by remember { mutableStateOf(false) }
    var showAcct by remember { mutableStateOf(false) }
    var lumis by remember { mutableIntStateOf(-1) } // lumis earned as a host
    var diamondsBal by remember { mutableIntStateOf(-1) } // diamonds bought
    var showTheme by remember { mutableStateOf(false) }
    val hctx = LocalContext.current
    val hsp = remember { hctx.getSharedPreferences("s", 0) }
    var acct by remember { mutableStateOf(hsp.getString("acct", "") ?: "") }
    LaunchedEffect(acct, server) {
        val w = if (acct.isNotEmpty() && server.isNotBlank()) withContext(Dispatchers.IO) { Api.walletInfo(server) } else null
        lumis = w?.lumis ?: -1
        diamondsBal = w?.diamonds ?: -1
    }
    val sorted = remember(streams) {
        streams.sortedWith(compareByDescending<Api.Stream> { Follows.has(hctx, it.host) }.thenByDescending { it.same }.thenByDescending { Pref.score(hctx, it.type) }.thenByDescending { it.viewers })
    }
    LaunchedEffect(server) {
        while (true) {
            if (server.isNotBlank()) {
                try { streams = withContext(Dispatchers.IO) { Api.streams(server) }; err = "" }
                catch (e: Exception) { err = "Can't reach server" }
            }
            delay(8000)
        }
    }
    val alertCtx = LocalContext.current
    var alertsOn by remember { mutableStateOf(LiveAlerts.enabled(alertCtx)) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live now", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            // alert when someone you follow goes live (checked about every 15 minutes in the background)
            TextButton({
                alertsOn = !alertsOn
                LiveAlerts.setEnabled(alertCtx, alertsOn)
                if (alertsOn && !notifAllowed(alertCtx) && android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text(if (alertsOn) "🔔" else "🔕") }
            TextButton({ showTheme = true }) { Text("🎨") }
            TextButton({ showAcct = true }) { Text(if (acct.isEmpty()) "👤" else "👤 $acct" + (if (lumis >= 0) " 💎${compactNum(diamondsBal.toLong())} ⭐${compactNum(lumis.toLong())}" else "")) }
            TextButton({ showDiag = true }) { Text("ⓘ") }
        }
        if (DEFAULT_SERVER.isBlank()) OutlinedTextField(server, { onSettings(it.trim(), user) }, label = { Text("Server URL (your Worker)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(if (acct.isEmpty()) "You are $user (guest)" else "You are $user", style = MaterialTheme.typography.bodySmall)
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (streams.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No one is live yet") }
            items(sorted, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(r) }) {
                    val img = remember(r.thumb) { decodeThumb(r.thumb) }
                    if (img != null) Image(img, null, Modifier.fillMaxWidth().aspectRatio(1f),
                        contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF333333)),
                        contentAlignment = Alignment.Center) { Text(if (r.kind == "audio") "🎙" else "📺") }
                    Column(Modifier.padding(10.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("${if (Follows.has(hctx, r.host)) "⭐" else ""}${flag(r.cc)}${LiveType.icon(r.type)}${r.host} • 👁 ${r.viewers}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
        Button({ if (acct.isEmpty()) showAcct = true else onGoLive() }, Modifier.fillMaxWidth(), enabled = server.isNotBlank()) {
            Text(if (acct.isEmpty()) "Log in to Go Live" else "Go Live")
        }
        OutlinedButton({ if (acct.isEmpty()) showAcct = true else onProfile() }, Modifier.fillMaxWidth()) { Text(if (acct.isEmpty()) "👤 Log in" else "👤 My profile") }
        if (showTheme) ThemeDialog(hsp) { showTheme = false }
        if (showAcct) AccountDialog(server, acct, { token, name ->
            Api.auth = token
            acct = name
            hsp.edit().putString("auth", token).putString("acct", name).apply()
            // logged in: the account name is your name; logged out: back to a guest name
            onSettings(server, if (name.isNotEmpty()) name else makeGuestName(hsp))
            showAcct = false
        }, { showAcct = false; onPosts() }) { showAcct = false }
        if (showDiag) AlertDialog(
            onDismissRequest = { showDiag = false },
            title = { Text("Diagnostics") },
            text = { SelectionContainer { Text(diagText(hctx), style = MaterialTheme.typography.bodySmall) } },
            dismissButton = {
                TextButton({
                    val cm = hctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", diagText(hctx)))
                }) { Text("Copy") }
            },
            confirmButton = { TextButton({ showDiag = false }) { Text("Close") } }
        )
    }
}

// ---------- Viewer ----------
@Composable
fun WatchScreen(server: String, user: String, stream: Api.Stream, onSwitch: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lk = remember { LiveKit.create(ctx.applicationContext) }
    val msgs = remember { mutableStateListOf<String>() }
    val fx = remember { GiftFx() }
    var viewers by remember { mutableIntStateOf(0) }
    var hostV by remember { mutableStateOf<VideoTrack?>(null) }
    var screenV by remember { mutableStateOf<VideoTrack?>(null) }
    var yt by remember { mutableStateOf<JSONObject?>(null) }
    var fillMode by rememberSaveable { mutableStateOf(false) }
    var chatHidden by rememberSaveable { mutableStateOf(false) }
    var guestV by remember { mutableStateOf<VideoTrack?>(null) }
    var blockedHost by remember { mutableStateOf(false) } // the host's phone found possible nudity: black screen until it is checked
    var blockedGuest by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connecting…") }
    var showViewers by remember { mutableStateOf(false) }
    var giftPopup by remember { mutableStateOf("") }
    LaunchedEffect(giftPopup) { if (giftPopup.isNotEmpty()) { delay(3500); giftPopup = "" } }
    var pk by remember { mutableStateOf<List<Int>?>(null) } // l, r, left
    var result by remember { mutableStateOf("") }
    var guestName by remember { mutableStateOf("Guest") }
    var side by remember { mutableStateOf("L") }

    LaunchedEffect(Unit) {
        try {
            val j = withContext(Dispatchers.IO) { Api.viewerToken(server, stream.id, user) }
            launch {
                lk.events.collect { e ->
                    when (e) {
                        is RoomEvent.TrackSubscribed -> {
                            val t = e.track as? VideoTrack
                            if (t != null) {
                                if (isGuest(e.participant.identity?.value)) {
                                    guestV = t; guestName = e.participant.name ?: "Guest"
                                } else if (e.publication.source == Track.Source.SCREEN_SHARE) screenV = t
                                else hostV = t
                            }
                        }
                        is RoomEvent.TrackUnsubscribed -> {
                            if (isGuest(e.participant.identity?.value)) guestV = null
                            else if (e.track === screenV) screenV = null
                        }
                        is RoomEvent.DataReceived -> {
                            val o = parseJson(e.data)
                            val fromHost = isHost(e.participant?.identity?.value)
                            if (o != null) when (o.optString("t")) {
                                "chat", "gift" -> {
                                    chatLine(e.participant?.name ?: "?", o, fromHost)?.let { msgs.addCapped(it) }
                                    if (o.optString("t") == "gift") fx.add(o.optString("g"), e.participant?.name ?: "?")
                                }
                                "hgift" -> if (fromHost) {
                                    fx.add(o.optString("g"), stream.host)
                                    val to = o.optString("to"); val g = o.optString("g")
                                    msgs.addCapped("${stream.host} (broadcaster) sent $g to $to")
                                    if (to.equals(user, ignoreCase = true)) giftPopup = "$g ${stream.host} sent you a gift!"
                                }
                                "pk" -> if (fromHost) {
                                    if (o.optBoolean("end")) {
                                        result = resultText(o.optInt("l"), o.optInt("r"), stream.host, guestName); pk = null
                                    } else pk = listOf(o.optInt("l"), o.optInt("r"), o.optInt("left"))
                                }
                                "goto" -> if (fromHost) onSwitch(o.optString("room"))
                                "blk" -> if (fromHost) blockedHost = o.optBoolean("on") else if (isGuest(e.participant?.identity?.value)) blockedGuest = o.optBoolean("on")
                                "yt" -> if (fromHost) yt = if (o.optString("id").isEmpty()) null else o
                            }
                        }
                        is RoomEvent.ParticipantConnected -> { viewers = viewerCount(lk) }
                        is RoomEvent.ParticipantDisconnected -> {
                            viewers = viewerCount(lk)
                            val id = e.participant.identity?.value
                            if (isGuest(id)) { guestV = null; pk = null }
                            if (isHost(id)) ended = true
                        }
                        is RoomEvent.Disconnected -> { ended = true }
                        else -> {}
                    }
                }
            }
            lk.connect(j.url, j.token)
            viewers = viewerCount(lk)
            hostV = remoteVideo(lk, false)
            guestV = remoteVideo(lk, true)
            screenV = lk.remoteParticipants.values.filter { !isGuest(it.identity?.value) }
                .firstNotNullOfOrNull { it.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack }
            status = ""
        } catch (e: Exception) { status = "Error: ${e.message}" }
    }
    LaunchedEffect(result) { if (result.isNotEmpty()) { delay(8000); result = "" } }
    LaunchedEffect(Unit) { delay(30000); Pref.bump(ctx, stream.type) }
    DisposableEffect(Unit) { onDispose { lk.disconnect(); lk.release() } }
    // swipe left = hide chat / title / gifts, swipe right = show them (swipes are reported by MainActivity)
    val wPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val offsetX = remember { Animatable(if (chatHidden) -wPx else 0f) }
    DisposableEffect(wPx) {
        SwipeBus.onDrag = { dx ->
            val base = if (chatHidden) -wPx else 0f
            scope.launch { offsetX.snapTo((base + dx).coerceIn(-wPx, 0f)) }
        }
        SwipeBus.onRelease = { dx ->
            val hide = if (chatHidden) !(dx > wPx * 0.25f) else dx < -wPx * 0.25f
            chatHidden = hide
            scope.launch { offsetX.animateTo(if (hide) -wPx else 0f) }
        }
        onDispose { SwipeBus.onDrag = null; SwipeBus.onRelease = null }
    }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (yt != null && screenV == null) YtView(Modifier.fillMaxSize(), false, yt)
                else {
                    val fr = ratioValue(stream.ratio)
                    val framed = fr != null && screenV == null && !fillMode
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // shared screen: no forced size, so the view measures itself to the picture's own shape (Fit) and nothing is zoomed
                        VideoView(lk, screenV ?: hostV, if (screenV != null) Modifier else if (framed) Modifier.aspectRatio(fr!!) else Modifier.fillMaxSize(),
                            scaleFill = if (screenV != null) false else if (framed || fillMode) true else null)
                    }
                }
                if (yt == null && screenV == null && stream.kind != "audio")
                    TextButton({ fillMode = !fillMode }, Modifier.align(Alignment.TopEnd).padding(top = 64.dp)) {
                        Text(if (fillMode) "Fill" else "Fit", style = MaterialTheme.typography.bodySmall)
                    }
                if (stream.kind == "audio" && yt == null && screenV == null)
                    Text("🎙", style = MaterialTheme.typography.displayLarge, modifier = Modifier.align(Alignment.Center))
                if (screenV != null && hostV != null) VideoView(lk, hostV,
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(84.dp, 120.dp))
                // the host's phone detected possible nudity: black first, the picture comes back after the check
                if (blockedHost) Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Text("Video paused while it is checked", color = Color(0xFFBBBBBB))
                }
            }
            if (guestV != null) Box(Modifier.weight(1f).fillMaxHeight()) {
                VideoView(lk, guestV, Modifier.fillMaxSize())
                if (blockedGuest) Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Text("Video paused while it is checked", color = Color(0xFFBBBBBB))
                }
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth()
                .graphicsLayer { translationX = offsetX.value; alpha = (1f + offsetX.value / wPx).coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically) {
                Text("${stream.title} • 👁 $viewers", color = Color.White,
                    modifier = Modifier.weight(1f).clickable { showViewers = true }.padding(12.dp))
                if (Api.auth.isNotEmpty() && !stream.host.isNullOrBlank() && !isGuestAccount(stream.host) && !stream.host.equals(user, ignoreCase = true)) {
                    var followHost by remember(stream.host) { mutableStateOf(Follows.has(ctx, stream.host)) }
                    OutlinedButton({ followHost = Follows.toggle(ctx, stream.host) },
                        contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.padding(end = 8.dp)) {
                        Text(if (followHost) "Following ✓" else "+ Follow")
                    }
                }
            }
            pk?.let { PkBoard(it[0], it[1], it[2], stream.host, guestName, Modifier.fillMaxWidth()) }
            if (result.isNotEmpty()) Text(result, color = Color.White,
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(12.dp))
        }
        if (showViewers) ViewerListDialog(
            remember(viewers, showViewers) {
                val hn = stream.host.ifBlank { lk.remoteParticipants.values.firstOrNull { isHost(it.identity?.value) }?.name ?: "" }
                (if (hn.isNotBlank()) listOf("$hn (host)") else emptyList()) + viewerNames(lk) + "$user (you)"
            }) { showViewers = false }
        GiftOverlay(fx)
        if (giftPopup.isNotEmpty()) Text(giftPopup, color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center)
            .background(Color(0xAA000000), RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 14.dp))
        if (ended) Text("Stream ended", color = Color.White, modifier = Modifier.align(Alignment.Center))
        else if (status.isNotEmpty()) Text(status, color = Color.White, modifier = Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .graphicsLayer { translationX = offsetX.value; alpha = (1f + offsetX.value / wPx).coerceIn(0f, 1f) }) {
            if (pk != null) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SideBtn("Support ${stream.host}", side == "L") { side = "L" }
                SideBtn("Support $guestName", side == "R") { side = "R" }
            }
            // guests can watch only: gifts, chat and follow need a login
            if (Api.auth.isNotEmpty()) {
                var showGifts by remember { mutableStateOf(false) }
                var sel by remember { mutableStateOf(VIEWER_GIFTS.first().first) }
                var diamonds by remember { mutableIntStateOf(-1) } // balance of the logged-in account
                var showBuy by remember { mutableStateOf(false) }
                var giftMsg by remember { mutableStateOf("") }
                LaunchedEffect(showGifts) { if (showGifts) withContext(Dispatchers.IO) { Api.walletInfo(server) }?.let { diamonds = it.diamonds } }
                if (showBuy) BuyDialog(server, diamonds, { diamonds = it }) { showBuy = false }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    FilledTonalButton({ showGifts = !showGifts }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) { Text(if (showGifts) "✕" else "🎁 Gift") }
                }
                if (showGifts) Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = Color(0xF0101018)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("💎 " + (if (diamonds >= 0) compactNum(diamonds.toLong()) else "…"), color = Color.White, modifier = Modifier.weight(1f))
                            TextButton({ showBuy = true }) { Text("+ Buy diamonds") }
                        }
                        LazyVerticalGrid(GridCells.Fixed(4), Modifier.height(250.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(VIEWER_GIFTS) { item ->
                                val g = item.first
                                val gv = item.second
                                Column(Modifier.fillMaxWidth().background(if (sel == g) Color(0x55FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(12.dp))
                                    .clickable { sel = g }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(g, fontSize = 28.sp)
                                    Text("💎$gv", fontSize = 12.sp, color = Color(0xFFFFD54F))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("$sel  💎${lumisValue(sel)}", color = Color.White)
                            Button({
                                val g = sel
                                giftMsg = ""
                                scope.launch {
                                    // the gift is paid first; only a paid gift is shown and sent to the room
                                    val r = withContext(Dispatchers.IO) { Api.sendGift(server, stream.id, g) }
                                    if (r.ok) {
                                        if (r.diamonds >= 0) diamonds = r.diamonds
                                        msgs.addCapped("$user sent $g")
                                        fx.add(g, user)
                                        try { lk.send(JSONObject().put("t", "gift").put("g", g).put("s", side)) } catch (e: Exception) {}
                                    } else {
                                        if (r.diamonds >= 0) diamonds = r.diamonds
                                        giftMsg = r.error
                                        if (r.needDiamonds) showBuy = true
                                    }
                                }
                            }) { Text("Send") }
                        }
                        if (giftMsg.isNotEmpty()) Text(giftMsg, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
            }
            ChatOverlay(msgs, onSend = {
                msgs.addCapped("$user: $it")
                scope.launch { lk.send(JSONObject().put("t", "chat").put("text", it)) }
            })
        }
    }
}

// ---------- Broadcaster ----------
@Composable
fun BroadcastContent(server: String, user: String, onGuest: (String, String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lk = remember { LiveKit.create(ctx.applicationContext) }
    var title by rememberSaveable { mutableStateOf("$user's live") }
    var live by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var lumis by remember { mutableIntStateOf(0) } // earned from gifts in this live
    var viewers by remember { mutableIntStateOf(0) }
    var cam by remember { mutableStateOf<LocalVideoTrack?>(null) }
    var showViewers by remember { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
    var roomId by remember { mutableStateOf("") }
    var hostKey by remember { mutableStateOf("") }
    // local recording of the live (saved on this phone, 10 minutes at most)
    var recorder by remember { mutableStateOf<LiveRecorder?>(null) }
    var recSecs by remember { mutableIntStateOf(0) }
    // live cover from the profile page (saved on the account); it replaces the automatic camera picture and cannot be changed during a live
    var coverB64 by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val bytes = withContext(Dispatchers.IO) { Api.fetchBytes(server, "cover?u=" + java.net.URLEncoder.encode(user, "UTF-8")) }
        if (bytes != null && bytes.isNotEmpty()) coverB64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
    LaunchedEffect(coverB64, live, roomId) {
        if (live && roomId.isNotEmpty() && coverB64.isNotEmpty())
            withContext(Dispatchers.IO) { try { Api.setThumb(server, roomId, coverB64, hostKey) } catch (e: Exception) {} }
    }
    // swipe left = hide chat and status, swipe right = show them (swipes are reported by MainActivity)
    var chatHidden by rememberSaveable { mutableStateOf(false) }
    val wPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val offsetX = remember { Animatable(if (chatHidden) -wPx else 0f) }
    DisposableEffect(wPx) {
        SwipeBus.onDrag = { dx ->
            val base = if (chatHidden) -wPx else 0f
            scope.launch { offsetX.snapTo((base + dx).coerceIn(-wPx, 0f)) }
        }
        SwipeBus.onRelease = { dx ->
            val hide = if (chatHidden) !(dx > wPx * 0.25f) else dx < -wPx * 0.25f
            chatHidden = hide
            scope.launch { offsetX.animateTo(if (hide) -wPx else 0f) }
        }
        onDispose { SwipeBus.onDrag = null; SwipeBus.onRelease = null }
    }
    val msgs = remember { mutableStateListOf<String>() }
    val fx = remember { GiftFx() }
    // PK
    var guestV by remember { mutableStateOf<VideoTrack?>(null) }
    var guestName by remember { mutableStateOf("Guest") }
    var pkActive by remember { mutableStateOf(false) }
    var scoreL by remember { mutableIntStateOf(0) }
    var scoreR by remember { mutableIntStateOf(0) }
    var pkLeft by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("") }
    var showPick by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf<Pair<String, String>?>(null) }
    var notice by remember { mutableStateOf("") }
    var held by remember { mutableStateOf<Bitmap?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var screenTrack by remember { mutableStateOf<LocalVideoTrack?>(null) }
    var muted by remember { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf("live") }
    var ratio by rememberSaveable { mutableStateOf("auto") }
    var ytId by remember { mutableStateOf("") }
    var ytCmd by remember { mutableStateOf<JSONObject?>(null) }
    var showYt by remember { mutableStateOf(false) }
    var ytText by remember { mutableStateOf("") }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) scope.launch {
            try {
                try { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java).putExtra("share", true)) } catch (e: Exception) {}
                delay(500)
                lk.localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(data, notificationId = 2, notification = liveNotification(ctx, "Sharing your screen")))
                val tr = lk.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalVideoTrack
                if (tr != null) {
                    screenTrack = tr; sharing = true
                    if (ytId.isNotEmpty()) {
                        try { lk.send(JSONObject().put("t", "yt").put("id", "")) } catch (e: Exception) {}
                        ytId = ""; ytCmd = null; showYt = false
                    }
                }
                else notice = "Screen share did not start. Try again."
            } catch (e: Exception) { status = "Share error: ${e.message}" }
        }
    }

    LaunchedEffect(sharing) {
        while (sharing) {
            delay(2000)
            if (lk.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE) == null) {
                sharing = false
                try { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java)) } catch (e: Exception) {}
            }
        }
    }

    fun startPk() { scoreL = 0; scoreR = 0; pkLeft = 180; pkActive = true; result = "" }
    fun endPk() {
        if (!pkActive) return
        pkActive = false
        result = resultText(scoreL, scoreR, user, guestName)
        val l = scoreL; val r = scoreR
        scope.launch { try { lk.send(pkJson(l, r, 0, true)) } catch (e: Exception) {} }
    }

    LaunchedEffect(Unit) {
        lk.events.collect { e ->
            when (e) {
                is RoomEvent.TrackSubscribed -> {
                    if (isGuest(e.participant.identity?.value)) {
                        guestV = e.track as? VideoTrack; guestName = e.participant.name ?: "Guest"
                    }
                }
                is RoomEvent.DataReceived -> {
                    val o = parseJson(e.data)
                    if (o != null) when (o.optString("t")) {
                        "chat" -> chatLine(e.participant?.name ?: "?", o)?.let { msgs.addCapped(it); notifyChat(ctx, it) }
                        "gift" -> {
                            chatLine(e.participant?.name ?: "?", o)?.let { msgs.addCapped(it); notifyChat(ctx, it) }
                            if (!isGuest(e.participant?.identity?.value)) fx.add(o.optString("g"), e.participant?.name ?: "?")
                            if (!isGuest(e.participant?.identity?.value)) lumis += lumisValue(o.optString("g"))
                            if (pkActive && !isGuest(e.participant?.identity?.value)) {
                                val v = giftValue(o.optString("g"))
                                if (o.optString("s") == "R") scoreR += v else scoreL += v
                            }
                        }
                        "pk_invite" -> if (!pkActive && live) invite = Pair(o.optString("room"), o.optString("name"))
                        "warn" -> if (e.participant == null) notice = "⚠️ Warning: nudity detected. Next time your live will be ended."
                        "ended" -> if (e.participant == null) {
                            val mins = o.optInt("mins")
                            status = "Ended: policy violation"
                            notice = "Your live was ended for breaking the nudity policy." + (if (mins > 0) " You cannot go live for " + (if (mins < 60) "$mins minutes" else if (mins == 60) "1 hour" else "${mins / 60} hours") + "." else "")
                        }
                    }
                }
                is RoomEvent.ParticipantConnected -> {
                    viewers = viewerCount(lk)
                    if (isGuest(e.participant.identity?.value)) startPk()
                }
                is RoomEvent.ParticipantDisconnected -> {
                    viewers = viewerCount(lk)
                    if (isGuest(e.participant.identity?.value)) { endPk(); guestV = null }
                }
                is RoomEvent.Disconnected -> { recorder?.stop(); recorder = null; live = false; cam = null; if (!status.startsWith("Ended")) status = "Disconnected"; try { ctx.stopService(Intent(ctx, LiveService::class.java)) } catch (e: Exception) {} }
                else -> {}
            }
        }
    }
    LaunchedEffect(pkActive) {
        if (pkActive) {
            while (pkLeft > 0 && pkActive) {
                lk.send(pkJson(scoreL, scoreR, pkLeft, false))
                delay(1000); pkLeft--
            }
            if (pkActive) endPk()
        }
    }
    LaunchedEffect(result) { if (result.isNotEmpty()) { delay(8000); result = "" } }
    LaunchedEffect(notice) { if (notice.isNotEmpty()) { delay(12000); notice = "" } }

    fun stopRec() { val r = recorder; recorder = null; recSecs = 0; r?.stop() }
    fun startRec() {
        if (!live || recorder != null) return
        if (isGuestAccount(user) || Api.auth.isEmpty()) { notice = "Guests can't record. Log in with an account first."; return }
        val holder = arrayOfNulls<LiveRecorder>(1)
        val r = LiveRecorder(ctx.applicationContext, kind == "live", 600_000L) { msg ->
            if (recorder === holder[0]) { recorder = null; recSecs = 0 }
            notice = msg
            android.widget.Toast.makeText(ctx.applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
        holder[0] = r
        r.muted = muted
        if (r.start()) { recorder = r; recSecs = 0; notice = "⏺ Recording (10 min max). It is saved on this phone." }
        else notice = "Could not start recording."
    }
    LaunchedEffect(recorder) { while (recorder != null) { recSecs = recorder?.elapsedSec() ?: 0; delay(500) } }
    LaunchedEffect(muted, recorder) { recorder?.muted = muted }
    DisposableEffect(cam, recorder) {
        val r = recorder
        val c = cam
        if (r != null && c != null && kind == "live") c.addRenderer(r)
        onDispose { if (r != null && c != null && kind == "live") try { c.removeRenderer(r) } catch (e: Exception) {} }
    }
    fun stop() {
        stopRec()
        lk.disconnect(); live = false; cam = null; sharing = false; status = "Ended"
        try { ctx.stopService(Intent(ctx, LiveService::class.java)) } catch (e: Exception) {}
    }
    // camera preview before going live; "Go Live" then counts 3 2 1 and starts the live
    var preview by remember { mutableStateOf<LocalVideoTrack?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    fun stopPreview() {
        val t = preview
        preview = null
        try { t?.stop() } catch (e: Exception) {}
    }
    LaunchedEffect(kind, live) {
        if (kind == "live" && !live) {
            if (preview == null) {
                try {
                    val t = lk.localParticipant.createVideoTrack()
                    t.startCapture()
                    preview = t
                } catch (e: Exception) { notice = "Camera preview failed: ${e.message}" }
            }
        } else if (preview != null) stopPreview()
    }
    fun start() {
        scope.launch {
            try {
                status = "Starting…"
                val j = withContext(Dispatchers.IO) { Api.createRoom(server, title, user, kind, ratio) }
                roomId = j.id
                hostKey = j.key
                lk.connect(j.url, j.token)
                if (kind == "live") lk.localParticipant.setCameraEnabled(true)
                lk.localParticipant.setMicrophoneEnabled(true)
                cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                live = true; muted = false; status = "LIVE"
                try { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java).putExtra("cam", kind == "live")) } catch (e: Exception) {}
            } catch (e: Exception) { status = "Error: ${e.message}" }
        }
    }
    fun goLive() {
        if (kind != "live") { start(); return }
        scope.launch {
            for (i in 3 downTo 1) { countdown = i; delay(1000) }
            countdown = 0
            stopPreview()
            delay(350) // let the camera be released before the live takes it
            start()
        }
    }
    fun accept(inv: Pair<String, String>) {
        invite = null
        scope.launch {
            try {
                lk.send(JSONObject().put("t", "goto").put("room", inv.first))
                delay(700)
                onGuest(inv.first, roomId)
            } catch (e: Exception) { status = "PK error: ${e.message}" }
        }
    }

    DisposableEffect(Unit) { onDispose { try { preview?.stop() } catch (e: Exception) {}; recorder?.stop(); lk.disconnect(); lk.release(); try { ctx.stopService(Intent(ctx, LiveService::class.java)) } catch (e: Exception) {} } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, live, held) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME && live && held == null && kind == "live") scope.launch {
                try {
                    lk.localParticipant.setCameraEnabled(false)
                    lk.localParticipant.setCameraEnabled(true)
                    cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                } catch (e: Exception) { status = "Camera error: ${e.message}" }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(live) {
        if (live) {
            delay(3000)
            var lastCheck = 0L
            var lastThumb = 0L
            var mixedStrikes = 0
            while (true) {
                val now = System.currentTimeMillis()
                val h = held
                if (h != null) {
                    // blocked on this phone: once the server may check, verify the held frame
                    if (notice.isEmpty()) notice = "⚠️ Video blocked while it is checked."
                    try { lk.send(JSONObject().put("t", "blk").put("on", true)) } catch (e: Exception) {} // also for viewers who join meanwhile
                    if (now - lastCheck > 30000) {
                        lastCheck = now
                        val img = jpegB64(h, 75)
                        val clean = withContext(Dispatchers.IO) {
                            try { Api.checkFrame(server, roomId, img, "host", hostKey) } catch (e: Exception) { false }
                        }
                        if (clean) {
                            held = null; notice = ""
                            try {
                                lk.localParticipant.setCameraEnabled(true)
                                cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                            } catch (e: Exception) { status = "Camera error: ${e.message}" }
                            try { lk.send(JSONObject().put("t", "blk").put("on", false)) } catch (e: Exception) {}
                        }
                        // not clean: the server ends the live and the "ended" message does the rest
                    }
                } else {
                    val frame = if (kind == "audio") null else renderer?.let { captureFrame(it) }
                    if (frame != null) {
                        if (withContext(Dispatchers.Default) { LocalGuard.suspicious(frame) }) {
                            // local detection: black screen first (here and for the viewers), then block the camera
                            held = frame
                            try { lk.send(JSONObject().put("t", "blk").put("on", true)) } catch (e: Exception) {}
                            notice = "⚠️ Possible nudity detected. Your video is blocked until it is checked."
                            if (sharing) { try { lk.localParticipant.setScreenShareEnabled(false) } catch (e: Exception) {}; sharing = false }
                            try { lk.localParticipant.setCameraEnabled(false) } catch (e: Exception) {}
                            cam = null
                        } else if (!sharing && withContext(Dispatchers.Default) { GenderGuard.mixed(frame) }) {
                            // rule: no man and woman together in one camera
                            mixedStrikes++
                            if (mixedStrikes >= 3) {
                                status = "Ended: policy violation"
                                notice = "Your live was ended: a man and a woman can't be on camera together."
                                stop(); delay(2500); onBack(); break
                            }
                            notice = "⚠️ A man and a woman together on camera isn't allowed. Video paused for 15 seconds."
                            try { lk.localParticipant.setCameraEnabled(false) } catch (e: Exception) {}
                            cam = null
                            delay(15000)
                            try {
                                lk.localParticipant.setCameraEnabled(true)
                                cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                            } catch (e: Exception) {}
                        } else if (now - lastThumb > 30000) {
                            lastThumb = now
                            val t = if (coverB64.isNotEmpty()) coverB64 else squareThumb(frame)
                            val ty = if (pkActive) "pk" else if (sharing) "screen" else withContext(Dispatchers.Default) { LiveType.detect(frame) }
                            withContext(Dispatchers.IO) {
                                try { Api.setThumb(server, roomId, t, hostKey, ty) } catch (e: Exception) {}
                            }
                        }
                    }
                }
                delay(2500)
            }
        }
    }
    BackHandler { stop(); onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxSize()) {
            if (kind == "audio") Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (ytId.isNotEmpty()) YtView(Modifier.fillMaxSize(), true, ytCmd) { s ->
                    if (s.optString("id").isNotEmpty()) scope.launch { try { lk.send(s.put("t", "yt")) } catch (e: Exception) {} }
                } else Text("🎙", style = MaterialTheme.typography.displayLarge)
                if (sharing) {
                    Text("🖥 Sharing your screen", color = Color.White, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).background(Color(0x99000000)).padding(8.dp))
                    screenTrack?.let { VideoView(lk, it, Modifier.align(Alignment.BottomEnd).padding(8.dp).size(90.dp, 160.dp)) }
                }
            } else Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val fr = ratioValue(ratio)
                VideoView(lk, cam ?: preview, if (fr != null) Modifier.aspectRatio(fr) else Modifier.fillMaxSize(),
                    onReady = { renderer = it }, scaleFill = if (fr != null) true else null)
                if (held != null) Box(Modifier.fillMaxSize().background(Color.Black)) // possible nudity: black first, no frozen picture
            }
            if (guestV != null) VideoView(lk, guestV, Modifier.weight(1f).fillMaxHeight())
        }
        GiftOverlay(fx)
        if (countdown > 0) Text("$countdown", color = Color.White, fontSize = 140.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(12.dp)
            .graphicsLayer { translationX = offsetX.value; alpha = (1f + offsetX.value / wPx).coerceIn(0f, 1f) },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$status${if (recorder != null) " • ⏺ %d:%02d".format(recSecs / 60, recSecs % 60) else ""}${if (muted) " • 🔇 muted" else ""}${if (sharing) " • 🖥 sharing" else ""} • 👁 $viewers${if (lumis > 0) " • ⭐ $lumis" else ""}", color = Color.White,
                modifier = Modifier.clickable { showViewers = true }
                    .background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(8.dp, 4.dp))
            if (pkActive) PkBoard(scoreL, scoreR, pkLeft, user, guestName, Modifier.fillMaxWidth())
            if (result.isNotEmpty()) Text(result, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            if (notice.isNotEmpty()) Text(notice, color = Color(0xFFFFD54F),
                modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(8.dp))
            if (!live) OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
            if (!live) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SideBtn("🎥 Live", kind == "live") { kind = "live" }
                SideBtn("🎙 Audio", kind == "audio") { kind = "audio" }
            }
            if (!live && kind == "live") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in listOf("auto", "9:16", "16:9", "1:1")) SideBtn(if (r == "auto") "Auto" else r, ratio == r) { ratio = r }
            }
        }
        if (showViewers) ViewerListDialog(remember(viewers, showViewers) { listOf("$user (you) (host)") + viewerNames(lk) }, onGift = { to, g ->
            msgs.addCapped("You sent $g to $to")
            fx.add(g, user)
            scope.launch { lk.send(JSONObject().put("t", "hgift").put("g", g).put("to", to)) }
        }) { showViewers = false }
        if (showYt) AlertDialog(
            onDismissRequest = { showYt = false },
            title = { Text("Play a YouTube video") },
            text = { OutlinedTextField(ytText, { ytText = it }, label = { Text("Paste a YouTube link") }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val id = youtubeId(ytText)
                    if (id == null) notice = "That is not a YouTube link."
                    else { ytId = id; ytCmd = JSONObject().put("id", id).put("play", true).put("pos", 0); showYt = false }
                }) { Text("Play for everyone") }
            },
            dismissButton = {
                if (ytId.isNotEmpty()) TextButton({
                    scope.launch { try { lk.send(JSONObject().put("t", "yt").put("id", "")) } catch (e: Exception) {} }
                    ytId = ""; ytCmd = null; showYt = false
                }) { Text("Stop video") } else TextButton({ showYt = false }) { Text("Cancel") }
            }
        )
        if (showPick) {
            var list by remember { mutableStateOf(listOf<Api.Stream>()) }
            LaunchedEffect(Unit) {
                try { list = withContext(Dispatchers.IO) { Api.streams(server) }.filter { it.id != roomId && it.kind != "audio" } }
                catch (e: Exception) {}
            }
            AlertDialog(
                onDismissRequest = { showPick = false },
                confirmButton = { TextButton({ showPick = false }) { Text("Cancel") } },
                title = { Text("Start a PK") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        fun sendInvite(s: Api.Stream) {
                            showPick = false
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { Api.invite(server, s.id, roomId, user, hostKey) }
                                    status = "Invite sent to ${s.host}"
                                } catch (e: Exception) { status = "Invite failed" }
                            }
                        }
                        if (list.isEmpty()) Text("No other live streams")
                        else {
                            // random PK: one of the other live hosts, picked by chance
                            Button({ sendInvite(list.random()) }, Modifier.fillMaxWidth()) { Text("🎲 Random PK") }
                            Text("or choose who to PK with:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                        list.forEach { s ->
                            TextButton({ sendInvite(s) }) { Text("${s.title} (${s.host})") }
                        }
                    }
                }
            )
        }
        invite?.let { inv ->
            AlertDialog(
                onDismissRequest = { invite = null },
                confirmButton = { TextButton({ accept(inv) }) { Text("Accept") } },
                dismissButton = { TextButton({ invite = null }) { Text("Decline") } },
                title = { Text("PK invite") },
                text = { Text("${inv.second} invites you to a PK battle. Your viewers will follow you.") }
            )
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            if (live) ChatOverlay(msgs, Modifier.graphicsLayer { translationX = offsetX.value; alpha = (1f + offsetX.value / wPx).coerceIn(0f, 1f) }, onSend = {
                msgs.addCapped("$user (broadcaster): $it")
                scope.launch { lk.send(JSONObject().put("t", "chat").put("text", it)) }
            })
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ if (live) stop() else goLive() }, Modifier.weight(1f), enabled = live || countdown == 0) { Text(if (live) "End" else if (countdown > 0) "Starting…" else "Go Live") }
                OutlinedButton({
                    scope.launch {
                        try { lk.localParticipant.setMicrophoneEnabled(muted); muted = !muted }
                        catch (e: Exception) { notice = "Microphone error: ${e.message}" }
                    }
                }, enabled = live) { Text(if (muted) "🔇" else "🎤") }
                if (kind == "live") OutlinedButton({ showPick = true }, enabled = live && !pkActive) { Text("PK") }
                if (kind == "audio") OutlinedButton({
                    if (sharing) scope.launch {
                        try { lk.localParticipant.setScreenShareEnabled(false) } catch (e: Exception) {}
                        sharing = false
                        try { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java)) } catch (e: Exception) {}
                    } else {
                        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        shareLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                }, enabled = live && !pkActive && held == null) { Text(if (sharing) "Unshare" else "Share") }
                if (kind == "audio") OutlinedButton({ showYt = true }, enabled = live && !sharing) { Text("YouTube") }
                if (kind == "live") OutlinedButton({ cam?.switchCamera() }, enabled = live) { Text("Flip") }
                OutlinedButton({ if (recorder != null) stopRec() else startRec() }, enabled = live && held == null && !isGuestAccount(user),
                    contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (recorder != null) "⏹" else "⏺") }
                OutlinedButton({
                    if (FloatChat.canShow(ctx)) android.widget.Toast.makeText(ctx, "Floating chat is on: it shows when you leave the app", android.widget.Toast.LENGTH_LONG).show()
                    else FloatChat.askPermission(ctx)
                }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("💬") }
            }
        }
    }
}

// ---------- Guest (invited broadcaster in someone else's room) ----------
@Composable
fun GuestScreen(server: String, user: String, room: String, from: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lk = remember { LiveKit.create(ctx.applicationContext) }
    val msgs = remember { mutableStateListOf<String>() }
    var hostV by remember { mutableStateOf<VideoTrack?>(null) }
    var cam by remember { mutableStateOf<LocalVideoTrack?>(null) }
    var status by remember { mutableStateOf("Joining PK…") }
    var pk by remember { mutableStateOf<List<Int>?>(null) }
    var result by remember { mutableStateOf("") }
    var hostName by remember { mutableStateOf("Host") }
    var myRenderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
    var gKey by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var held by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        try {
            val j = withContext(Dispatchers.IO) { Api.guestToken(server, room, from, user) }
            gKey = j.key
            launch {
                lk.events.collect { e ->
                    when (e) {
                        is RoomEvent.TrackSubscribed -> {
                            if (isHost(e.participant.identity?.value)) {
                                hostV = e.track as? VideoTrack; hostName = e.participant.name ?: "Host"
                            }
                        }
                        is RoomEvent.DataReceived -> {
                            val o = parseJson(e.data)
                            val fromHost = isHost(e.participant?.identity?.value)
                            if (o != null) when (o.optString("t")) {
                                "chat", "gift" -> chatLine(e.participant?.name ?: "?", o, fromHost)?.let { msgs.addCapped(it) }
                                "pk" -> if (fromHost) {
                                    if (o.optBoolean("end")) {
                                        result = resultText(o.optInt("l"), o.optInt("r"), hostName, user); pk = null
                                    } else pk = listOf(o.optInt("l"), o.optInt("r"), o.optInt("left"))
                                }
                                "warn" -> if (e.participant == null) notice = "⚠️ Warning: nudity detected. Next time you will be removed."
                                "ended" -> if (e.participant == null) {
                            val mins = o.optInt("mins")
                            notice = "You were removed for breaking the nudity policy." + (if (mins > 0) " You cannot join a PK for " + (if (mins < 60) "$mins minutes" else if (mins == 60) "1 hour" else "${mins / 60} hours") + "." else "")
                            delay(4000); onBack()
                        }
                            }
                        }
                        is RoomEvent.ParticipantDisconnected -> {
                            if (isHost(e.participant.identity?.value)) { status = "Host left"; onBack() }
                        }
                        is RoomEvent.Disconnected -> { status = "Disconnected" }
                        else -> {}
                    }
                }
            }
            lk.connect(j.url, j.token)
            lk.localParticipant.setCameraEnabled(true)
            lk.localParticipant.setMicrophoneEnabled(true)
            cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
            hostV = remoteVideo(lk, false)
            status = ""
        } catch (e: Exception) { status = "Error: ${e.message}" }
    }
    LaunchedEffect(result) { if (result.isNotEmpty()) { delay(8000); onBack() } }
    LaunchedEffect(notice) { if (notice.isNotEmpty()) { delay(12000); notice = "" } }
    LaunchedEffect(Unit) {
        while (cam == null) delay(500)
        delay(3000)
        var lastCheck = 0L
        var mixedStrikes = 0
        while (true) {
            val now = System.currentTimeMillis()
            val h = held
            if (h != null) {
                if (notice.isEmpty()) notice = "⚠️ Video blocked while it is checked."
                try { lk.send(JSONObject().put("t", "blk").put("on", true)) } catch (e: Exception) {}
                if (now - lastCheck > 30000) {
                    lastCheck = now
                    val img = jpegB64(h, 75)
                    val clean = withContext(Dispatchers.IO) {
                        try { Api.checkFrame(server, room, img, "guest", gKey) } catch (e: Exception) { false }
                    }
                    if (clean) {
                        held = null; notice = ""
                        try { lk.send(JSONObject().put("t", "blk").put("on", false)) } catch (e: Exception) {}
                        try {
                            lk.localParticipant.setCameraEnabled(true)
                            cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                        } catch (e: Exception) {}
                    }
                }
            } else {
                val frame = myRenderer?.let { captureFrame(it) }
                if (frame != null && withContext(Dispatchers.Default) { LocalGuard.suspicious(frame) }) {
                    held = frame
                    try { lk.send(JSONObject().put("t", "blk").put("on", true)) } catch (e: Exception) {} // viewers see black first
                    notice = "⚠️ Possible nudity detected. Your video is blocked until it is checked."
                    try { lk.localParticipant.setCameraEnabled(false) } catch (e: Exception) {}
                    cam = null
                } else if (frame != null && withContext(Dispatchers.Default) { GenderGuard.mixed(frame) }) {
                    // rule: no man and woman together in one camera
                    mixedStrikes++
                    if (mixedStrikes >= 3) {
                        notice = "You were removed: a man and a woman can't be on camera together."
                        delay(2500); onBack(); break
                    }
                    notice = "⚠️ A man and a woman together on camera isn't allowed. Video paused for 15 seconds."
                    try { lk.localParticipant.setCameraEnabled(false) } catch (e: Exception) {}
                    cam = null
                    delay(15000)
                    try {
                        lk.localParticipant.setCameraEnabled(true)
                        cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                    } catch (e: Exception) {}
                }
            }
            delay(2500)
        }
    }
    DisposableEffect(Unit) { onDispose { lk.disconnect(); lk.release() } }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxSize()) {
            VideoView(lk, hostV, Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) {
                VideoView(lk, cam, Modifier.fillMaxSize(), onReady = { myRenderer = it })
                if (held != null) Box(Modifier.fillMaxSize().background(Color.Black)) // black first, no frozen picture
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding()) {
            Text("PK • $status", color = Color.White, modifier = Modifier.padding(12.dp))
            pk?.let { PkBoard(it[0], it[1], it[2], hostName, user, Modifier.fillMaxWidth()) }
            if (result.isNotEmpty()) Text(result, color = Color.White,
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(12.dp))
            if (notice.isNotEmpty()) Text(notice, color = Color(0xFFFFD54F), modifier = Modifier.padding(12.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            ChatOverlay(msgs, onSend = {
                msgs.addCapped("$user: $it")
                scope.launch { lk.send(JSONObject().put("t", "chat").put("text", it)) }
            })
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onBack, Modifier.weight(1f)) { Text("End PK") }
                OutlinedButton({ cam?.switchCamera() }) { Text("Flip") }
            }
        }
    }
}
