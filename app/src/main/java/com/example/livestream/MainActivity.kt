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
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalGuard.load(applicationContext)
        GenderGuard.load(applicationContext)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

sealed interface Screen {
    object Home : Screen
    data class Watch(val stream: Api.Stream) : Screen
    object Live : Screen
    data class Guest(val room: String, val from: String) : Screen
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("s", 0) }
    var server by remember { mutableStateOf(sp.getString("server", "") ?: "") }
    var user by remember { mutableStateOf(sp.getString("user", "user" + (100..999).random()) ?: "user") }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    when (val s = screen) {
        Screen.Home -> HomeScreen(server, user,
            { sv, u -> server = sv; user = u; sp.edit().putString("server", sv).putString("user", u).apply() },
            { screen = Screen.Watch(it) }, { screen = Screen.Live })
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

fun chatLine(name: String, o: JSONObject): String? = when (o.optString("t")) {
    "chat" -> "$name: ${o.optString("text")}"
    "gift" -> "$name sent ${o.optString("g")}"
    else -> null
}

fun parseJson(b: ByteArray): JSONObject? = try { JSONObject(String(b)) } catch (e: Exception) { null }
fun MutableList<String>.addCapped(s: String) { add(s); while (size > 50) removeAt(0) }
fun giftValue(g: String) = when (g) { "🌹" -> 5; "🚀" -> 20; else -> 1 }
fun isGuest(id: String?) = id?.startsWith("guest-") == true
fun isHost(id: String?) = id?.startsWith("host-") == true
fun pkJson(l: Int, r: Int, left: Int, end: Boolean) =
    JSONObject().put("t", "pk").put("l", l).put("r", r).put("left", left).put("end", end)
fun resultText(l: Int, r: Int, ln: String, rn: String) =
    if (l > r) "🏆 $ln wins!" else if (r > l) "🏆 $rn wins!" else "🤝 Draw"

fun viewerCount(room: Room) = room.remoteParticipants.values.count { !isGuest(it.identity?.value) }
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

private const val YT_HTML = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}body{pointer-events:__PE__}</style></head>
<body><div id="p"></div><div id="msg" style="position:absolute;left:0;right:0;top:45%;text-align:center;color:#fff;font:14px sans-serif;padding:8px"></div>
<script>
function msg(t){document.getElementById('msg').textContent=t;}
msg('Loading YouTube…');
var player=null,ready=false,pending=null;
var s=document.createElement('script');s.src='https://www.youtube.com/iframe_api';s.onerror=function(){msg('Cannot reach YouTube. Check the internet.');};document.head.appendChild(s);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{host:'https://www.youtube-nocookie.com',width:'100%',height:'100%',
    playerVars:{playsinline:1,controls:__CT__,rel:0,modestbranding:1,fs:0,origin:location.origin,widget_referrer:location.href},
    events:{onReady:function(){ready=true;msg('');if(pending){var c=pending;pending=null;apply(c);}},
      onError:function(e){msg('YouTube cannot play this video here (error '+e.data+')');}}});
}
function apply(c){
  if(!ready){pending=c;return;}
  var cur='';try{cur=player.getVideoData().video_id||'';}catch(e){}
  if(c.id!==cur){player.loadVideoById(c.id,c.pos||0);if(!c.play){setTimeout(function(){player.pauseVideo();},800);}return;}
  var st=player.getPlayerState();
  if(c.play&&st!==1&&st!==3){player.playVideo();}
  if(!c.play&&st===1){player.pauseVideo();}
  if(Math.abs(player.getCurrentTime()-(c.pos||0))>3){player.seekTo(c.pos||0,true);}
}
function state(){
  if(!ready)return null;
  try{return {id:player.getVideoData().video_id||'',play:player.getPlayerState()===1,pos:player.getCurrentTime()};}catch(e){return null;}
}
</script></body></html>"""

// Host: has controls and reports its state every 2 s. Viewer: no controls, follows `cmd`.
@Composable
fun YtView(modifier: Modifier, host: Boolean, cmd: JSONObject?, onHostState: (JSONObject) -> Unit = {}) {
    var web by remember { mutableStateOf<WebView?>(null) }
    val latest by rememberUpdatedState(cmd)
    AndroidView(modifier = modifier, factory = { c ->
        WebView(c).also { w ->
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.settings.mediaPlaybackRequiresUserGesture = false
            w.webChromeClient = WebChromeClient()
            w.setBackgroundColor(android.graphics.Color.BLACK)
            val html = YT_HTML.replace("__PE__", if (host) "auto" else "none").replace("__CT__", if (host) "1" else "0")
            w.loadDataWithBaseURL("https://yt.livestream-app.com/", html, "text/html", "utf-8", null)
            web = w
        }
    })
    LaunchedEffect(cmd, web) {
        if (cmd != null) web?.evaluateJavascript("apply(" + cmd.toString() + ")", null)
    }
    LaunchedEffect(web, host) {
        var loaded = false
        if (host) while (true) {
            delay(if (loaded) 2000 else 700)
            web?.evaluateJavascript("state()") { r ->
                var st: JSONObject? = null
                try { if (r != null && r != "null") st = JSONObject(r) } catch (e: Exception) {}
                if (st != null && st.optString("id").isNotEmpty()) { loaded = true; onHostState(st) }
                else latest?.let { web?.evaluateJavascript("apply(" + it.toString() + ")", null) }
            }
        }
    }
    DisposableEffect(web) { onDispose { try { web?.destroy() } catch (e: Exception) {} } }
}

fun decodeThumb(b64: String): ImageBitmap? = try {
    if (b64.isEmpty()) null else {
        val b = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
    }
} catch (e: Exception) { null }

// ---------- shared UI ----------
@Composable
fun VideoView(room: Room, track: VideoTrack?, modifier: Modifier = Modifier, onReady: (TextureViewRenderer) -> Unit = {}) {
    var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
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
fun ViewerListDialog(names: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("Close") } },
        title = { Text("Viewers (${names.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (names.isEmpty()) Text("No viewers yet")
                names.forEach { Text(it) }
            }
        }
    )
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
        msgs.takeLast(6).forEach {
            Text(it, color = Color.White, modifier = Modifier
                .background(Color(0x66000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        granted = it.values.all { ok -> ok }
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    BackHandler(enabled = !granted, onBack = onBack)
    if (granted) content()
    else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Camera and microphone access is needed to broadcast.")
        Spacer(Modifier.height(12.dp))
        Button({ launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
            Text("Grant permissions")
        }
    }
}

// ---------- Home ----------
@Composable
fun HomeScreen(
    server: String, user: String, onSettings: (String, String) -> Unit,
    onOpen: (Api.Stream) -> Unit, onGoLive: () -> Unit
) {
    var streams by remember { mutableStateOf(listOf<Api.Stream>()) }
    var err by remember { mutableStateOf("") }
    val hctx = LocalContext.current
    val sorted = remember(streams) {
        streams.sortedWith(compareByDescending<Api.Stream> { it.same }.thenByDescending { Pref.score(hctx, it.type) }.thenByDescending { it.viewers })
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
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Live now", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(server, { onSettings(it.trim(), user) }, label = { Text("Server URL (your Worker)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { onSettings(server, it) }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
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
                        Text("${flag(r.cc)}${LiveType.icon(r.type)}${r.host} • 👁 ${r.viewers}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
        Button(onGoLive, Modifier.fillMaxWidth(), enabled = server.isNotBlank()) { Text("Go Live") }
    }
}

// ---------- Viewer ----------
@Composable
fun WatchScreen(server: String, user: String, stream: Api.Stream, onSwitch: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lk = remember { LiveKit.create(ctx.applicationContext) }
    val msgs = remember { mutableStateListOf<String>() }
    var viewers by remember { mutableIntStateOf(0) }
    var hostV by remember { mutableStateOf<VideoTrack?>(null) }
    var screenV by remember { mutableStateOf<VideoTrack?>(null) }
    var yt by remember { mutableStateOf<JSONObject?>(null) }
    var guestV by remember { mutableStateOf<VideoTrack?>(null) }
    var ended by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connecting…") }
    var showViewers by remember { mutableStateOf(false) }
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
                                "chat", "gift" -> chatLine(e.participant?.name ?: "?", o)?.let { msgs.addCapped(it) }
                                "pk" -> if (fromHost) {
                                    if (o.optBoolean("end")) {
                                        result = resultText(o.optInt("l"), o.optInt("r"), stream.host, guestName); pk = null
                                    } else pk = listOf(o.optInt("l"), o.optInt("r"), o.optInt("left"))
                                }
                                "goto" -> if (fromHost) onSwitch(o.optString("room"))
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
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (yt != null) YtView(Modifier.fillMaxSize(), false, yt)
                else VideoView(lk, screenV ?: hostV, Modifier.fillMaxSize())
                if (stream.kind == "audio" && yt == null && screenV == null)
                    Text("🎙", style = MaterialTheme.typography.displayLarge, modifier = Modifier.align(Alignment.Center))
                if (screenV != null && hostV != null) VideoView(lk, hostV,
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(84.dp, 120.dp))
            }
            if (guestV != null) VideoView(lk, guestV, Modifier.weight(1f).fillMaxHeight())
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding()) {
            Text("${stream.title} • 👁 $viewers", color = Color.White,
                modifier = Modifier.clickable { showViewers = true }.padding(12.dp))
            pk?.let { PkBoard(it[0], it[1], it[2], stream.host, guestName, Modifier.fillMaxWidth()) }
            if (result.isNotEmpty()) Text(result, color = Color.White,
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(12.dp))
        }
        if (showViewers) ViewerListDialog(
            remember(viewers, showViewers) { viewerNames(lk) + "$user (you)" }) { showViewers = false }
        if (ended) Text("Stream ended", color = Color.White, modifier = Modifier.align(Alignment.Center))
        else if (status.isNotEmpty()) Text(status, color = Color.White, modifier = Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            if (pk != null) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SideBtn("Support ${stream.host}", side == "L") { side = "L" }
                SideBtn("Support $guestName", side == "R") { side = "R" }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("❤️", "🌹", "🚀").forEach { g ->
                    FilledTonalButton({
                        msgs.addCapped("$user sent $g")
                        scope.launch { lk.send(JSONObject().put("t", "gift").put("g", g).put("s", side)) }
                    }) { Text(g) }
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
    var viewers by remember { mutableIntStateOf(0) }
    var cam by remember { mutableStateOf<LocalVideoTrack?>(null) }
    var showViewers by remember { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
    var roomId by remember { mutableStateOf("") }
    var hostKey by remember { mutableStateOf("") }
    val msgs = remember { mutableStateListOf<String>() }
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
    var kind by rememberSaveable { mutableStateOf("live") }
    var ytId by remember { mutableStateOf("") }
    var ytCmd by remember { mutableStateOf<JSONObject?>(null) }
    var showYt by remember { mutableStateOf(false) }
    var ytText by remember { mutableStateOf("") }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) scope.launch {
            try {
                lk.localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(data))
                sharing = true
            } catch (e: Exception) { status = "Share error: ${e.message}" }
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
                        "chat" -> chatLine(e.participant?.name ?: "?", o)?.let { msgs.addCapped(it) }
                        "gift" -> {
                            chatLine(e.participant?.name ?: "?", o)?.let { msgs.addCapped(it) }
                            if (pkActive && !isGuest(e.participant?.identity?.value)) {
                                val v = giftValue(o.optString("g"))
                                if (o.optString("s") == "R") scoreR += v else scoreL += v
                            }
                        }
                        "pk_invite" -> if (!pkActive && live) invite = Pair(o.optString("room"), o.optString("name"))
                        "warn" -> if (e.participant == null) notice = "⚠️ Warning: nudity detected. Next time your live will be ended."
                        "ended" -> if (e.participant == null) { status = "Ended: policy violation"; notice = "Your live was ended for breaking the nudity policy." }
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
                is RoomEvent.Disconnected -> { live = false; cam = null; if (!status.startsWith("Ended")) status = "Disconnected"; try { ctx.stopService(Intent(ctx, LiveService::class.java)) } catch (e: Exception) {} }
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

    fun stop() {
        lk.disconnect(); live = false; cam = null; sharing = false; status = "Ended"
        try { ctx.stopService(Intent(ctx, LiveService::class.java)) } catch (e: Exception) {}
    }
    fun start() {
        scope.launch {
            try {
                status = "Starting…"
                val j = withContext(Dispatchers.IO) { Api.createRoom(server, title, user, kind) }
                roomId = j.id
                hostKey = j.key
                lk.connect(j.url, j.token)
                if (kind == "live") lk.localParticipant.setCameraEnabled(true)
                lk.localParticipant.setMicrophoneEnabled(true)
                cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                live = true; status = "LIVE"
                try { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java)) } catch (e: Exception) {}
            } catch (e: Exception) { status = "Error: ${e.message}" }
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

    DisposableEffect(Unit) { onDispose { lk.disconnect(); lk.release(); try { ctx.stopService(Intent(ctx, LiveService::class.java)) } catch (e: Exception) {} } }
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
                        }
                        // not clean: the server ends the live and the "ended" message does the rest
                    }
                } else {
                    val frame = if (kind == "audio") null else renderer?.let { captureFrame(it) }
                    if (frame != null) {
                        if (withContext(Dispatchers.Default) { LocalGuard.suspicious(frame) }) {
                            // local detection: block the video on this phone right away
                            held = frame
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
                            val t = squareThumb(frame)
                            val ty = if (pkActive) "pk" else if (sharing) "screen" else withContext(Dispatchers.Default) { LiveType.detect(frame) }
                            withContext(Dispatchers.IO) {
                                try { Api.setThumb(server, roomId, t, hostKey, ty) } catch (e: Exception) {}
                            }
                        }
                    }
                }
                delay(5000)
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
            } else VideoView(lk, cam, Modifier.weight(1f).fillMaxHeight(), onReady = { renderer = it })
            if (guestV != null) VideoView(lk, guestV, Modifier.weight(1f).fillMaxHeight())
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$status • 👁 $viewers", color = Color.White,
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
        }
        if (showViewers) ViewerListDialog(remember(viewers, showViewers) { viewerNames(lk) }) { showViewers = false }
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
                title = { Text("PK with…") },
                text = {
                    Column {
                        if (list.isEmpty()) Text("No other live streams")
                        list.forEach { s ->
                            TextButton({
                                showPick = false
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { Api.invite(server, s.id, roomId, user, hostKey) }
                                        status = "Invite sent to ${s.host}"
                                    } catch (e: Exception) { status = "Invite failed" }
                                }
                            }) { Text("${s.title} (${s.host})") }
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
            if (live) ChatOverlay(msgs, onSend = {
                msgs.addCapped("$user: $it")
                scope.launch { lk.send(JSONObject().put("t", "chat").put("text", it)) }
            })
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ if (live) stop() else start() }, Modifier.weight(1f)) { Text(if (live) "End" else "Go Live") }
                if (kind == "live") OutlinedButton({ showPick = true }, enabled = live && !pkActive) { Text("PK") }
                if (kind == "audio") OutlinedButton({
                    if (sharing) scope.launch {
                        try { lk.localParticipant.setScreenShareEnabled(false) } catch (e: Exception) {}
                        sharing = false
                    } else {
                        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        shareLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                }, enabled = live && !pkActive && held == null) { Text(if (sharing) "Unshare" else "Share") }
                if (kind == "audio") OutlinedButton({ showYt = true }, enabled = live) { Text("YouTube") }
                if (kind == "live") OutlinedButton({ cam?.switchCamera() }, enabled = live) { Text("Flip") }
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
                                "chat", "gift" -> chatLine(e.participant?.name ?: "?", o)?.let { msgs.addCapped(it) }
                                "pk" -> if (fromHost) {
                                    if (o.optBoolean("end")) {
                                        result = resultText(o.optInt("l"), o.optInt("r"), hostName, user); pk = null
                                    } else pk = listOf(o.optInt("l"), o.optInt("r"), o.optInt("left"))
                                }
                                "warn" -> if (e.participant == null) notice = "⚠️ Warning: nudity detected. Next time you will be removed."
                                "ended" -> if (e.participant == null) { notice = "You were removed for breaking the nudity policy."; delay(2500); onBack() }
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
                if (now - lastCheck > 30000) {
                    lastCheck = now
                    val img = jpegB64(h, 75)
                    val clean = withContext(Dispatchers.IO) {
                        try { Api.checkFrame(server, room, img, "guest", gKey) } catch (e: Exception) { false }
                    }
                    if (clean) {
                        held = null; notice = ""
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
            delay(5000)
        }
    }
    DisposableEffect(Unit) { onDispose { lk.disconnect(); lk.release() } }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxSize()) {
            VideoView(lk, hostV, Modifier.weight(1f).fillMaxHeight())
            VideoView(lk, cam, Modifier.weight(1f).fillMaxHeight(), onReady = { myRenderer = it })
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
