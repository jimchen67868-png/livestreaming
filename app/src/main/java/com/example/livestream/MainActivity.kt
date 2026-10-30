package com.example.livestream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
object LocalGuard {
    private const val THRESHOLD = 0.22f   // raise to check less often, lower to be stricter

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
            out[0][3] + out[0][1] + 0.5f * out[0][4]
        } catch (e: Exception) { null }
    }

    fun suspicious(src: Bitmap): Boolean {
        val m = modelScore(src)
        return if (m != null) m >= 0.4f else skinRatio(src) > THRESHOLD
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
            items(streams, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(r) }) {
                    val img = remember(r.thumb) { decodeThumb(r.thumb) }
                    if (img != null) Image(img, null, Modifier.fillMaxWidth().aspectRatio(1f),
                        contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF333333)),
                        contentAlignment = Alignment.Center) { Text("📺") }
                    Column(Modifier.padding(10.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("${r.host} • 👁 ${r.viewers}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
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
                                } else hostV = t
                            }
                        }
                        is RoomEvent.TrackUnsubscribed -> {
                            if (isGuest(e.participant.identity?.value)) guestV = null
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
            status = ""
        } catch (e: Exception) { status = "Error: ${e.message}" }
    }
    LaunchedEffect(result) { if (result.isNotEmpty()) { delay(8000); result = "" } }
    DisposableEffect(Unit) { onDispose { lk.disconnect(); lk.release() } }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxSize()) {
            VideoView(lk, hostV, Modifier.weight(1f).fillMaxHeight())
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
                is RoomEvent.Disconnected -> { live = false; cam = null; if (!status.startsWith("Ended")) status = "Disconnected" }
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
        lk.disconnect(); live = false; cam = null; status = "Ended"
    }
    fun start() {
        scope.launch {
            try {
                status = "Starting…"
                val j = withContext(Dispatchers.IO) { Api.createRoom(server, title, user) }
                roomId = j.id
                hostKey = j.key
                lk.connect(j.url, j.token)
                lk.localParticipant.setCameraEnabled(true)
                lk.localParticipant.setMicrophoneEnabled(true)
                cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                live = true; status = "LIVE"
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

    DisposableEffect(Unit) { onDispose { lk.disconnect(); lk.release() } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, live) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME && live) scope.launch {
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
            while (true) {
                val frame = renderer?.let { captureFrame(it) }
                if (frame != null) {
                    val now = System.currentTimeMillis()
                    // 1) local check (free, every 5 s)
                    val suspicious = withContext(Dispatchers.Default) { LocalGuard.suspicious(frame) }
                    var clean = !suspicious
                    // 2) only if flagged locally: server double-checks (at most every 30 s)
                    if (suspicious && now - lastCheck > 30000) {
                        lastCheck = now
                        val img = jpegB64(frame, 75)
                        clean = withContext(Dispatchers.IO) {
                            try { Api.checkFrame(server, roomId, img, "host", hostKey) } catch (e: Exception) { false }
                        }
                    }
                    if (clean && now - lastThumb > 30000) {
                        lastThumb = now
                        val t = squareThumb(frame)
                        withContext(Dispatchers.IO) {
                            try { Api.setThumb(server, roomId, t, hostKey) } catch (e: Exception) {}
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
            VideoView(lk, cam, Modifier.weight(1f).fillMaxHeight(), onReady = { renderer = it })
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
        }
        if (showViewers) ViewerListDialog(remember(viewers, showViewers) { viewerNames(lk) }) { showViewers = false }
        if (showPick) {
            var list by remember { mutableStateOf(listOf<Api.Stream>()) }
            LaunchedEffect(Unit) {
                try { list = withContext(Dispatchers.IO) { Api.streams(server) }.filter { it.id != roomId } }
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
                OutlinedButton({ showPick = true }, enabled = live && !pkActive) { Text("PK") }
                OutlinedButton({ cam?.switchCamera() }, enabled = live) { Text("Flip") }
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
    LaunchedEffect(cam) {
        if (cam != null) {
            delay(3000)
            var lastCheck = 0L
            while (true) {
                val frame = myRenderer?.let { captureFrame(it) }
                if (frame != null && System.currentTimeMillis() - lastCheck > 30000 &&
                    withContext(Dispatchers.Default) { LocalGuard.suspicious(frame) }) {
                    lastCheck = System.currentTimeMillis()
                    val img = jpegB64(frame, 75)
                    withContext(Dispatchers.IO) {
                        try { Api.checkFrame(server, room, img, "guest", gKey) } catch (e: Exception) {}
                    }
                }
                delay(5000)
            }
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
