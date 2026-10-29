package com.example.livestream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

sealed interface Screen {
    object Home : Screen
    data class Watch(val stream: Api.Stream) : Screen
    object Live : Screen
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
        is Screen.Watch -> WatchScreen(server, user, s.stream) { screen = Screen.Home }
        Screen.Live -> BroadcastScreen(server, user) { screen = Screen.Home }
    }
}

// ---------- chat over LiveKit data messages ----------
suspend fun Room.sendChat(text: String) {
    localParticipant.publishData(JSONObject().put("t", "chat").put("text", text).toString().toByteArray())
}
suspend fun Room.sendGift(g: String) {
    localParticipant.publishData(JSONObject().put("t", "gift").put("g", g).toString().toByteArray())
}
fun parseData(name: String, bytes: ByteArray): String? = try {
    val o = JSONObject(String(bytes))
    when (o.optString("t")) {
        "chat" -> "$name: ${o.optString("text")}"
        "gift" -> "$name sent ${o.optString("g")}"
        else -> null
    }
} catch (e: Exception) { null }

fun MutableList<String>.addCapped(s: String) { add(s); while (size > 50) removeAt(0) }

fun remoteVideo(room: Room): VideoTrack? = room.remoteParticipants.values.firstNotNullOfOrNull {
    it.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
}

fun snapshot(v: TextureViewRenderer): String? {
    try {
        if (v.width == 0 || v.height == 0) return null
        val h = (240f * v.height / v.width).toInt().coerceAtLeast(1)
        val full = v.getBitmap(240, h) ?: return null
        val side = minOf(full.width, full.height)
        val sq = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
        val out = ByteArrayOutputStream()
        Bitmap.createScaledBitmap(sq, 160, 160, true).compress(Bitmap.CompressFormat.JPEG, 60, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) { return null }
}

fun decodeThumb(b64: String): ImageBitmap? = try {
    if (b64.isEmpty()) null else {
        val b = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
    }
} catch (e: Exception) { null }

fun viewerNames(room: Room): List<String> = room.remoteParticipants.values
    .filter { it.identity?.value?.startsWith("host-") != true }
    .map { it.name ?: it.identity?.value ?: "guest" }

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

// ---------- screens ----------
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

@Composable
fun WatchScreen(server: String, user: String, stream: Api.Stream, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lk = remember { LiveKit.create(ctx.applicationContext) }
    val msgs = remember { mutableStateListOf<String>() }
    var viewers by remember { mutableIntStateOf(0) }
    var video by remember { mutableStateOf<VideoTrack?>(null) }
    var ended by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connecting…") }
    var showViewers by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val j = withContext(Dispatchers.IO) { Api.viewerToken(server, stream.id, user) }
            launch {
                lk.events.collect { e ->
                    when (e) {
                        is RoomEvent.TrackSubscribed -> (e.track as? VideoTrack)?.let { video = it }
                        is RoomEvent.DataReceived ->
                            parseData(e.participant?.name ?: "?", e.data)?.let { msgs.addCapped(it) }
                        is RoomEvent.ParticipantConnected -> viewers = lk.remoteParticipants.size
                        is RoomEvent.ParticipantDisconnected -> {
                            viewers = lk.remoteParticipants.size
                            if (e.participant.identity?.value?.startsWith("host-") == true) ended = true
                        }
                        is RoomEvent.Disconnected -> ended = true
                        else -> {}
                    }
                }
            }
            lk.connect(j.url, j.token)
            viewers = lk.remoteParticipants.size
            video = remoteVideo(lk)
            status = ""
        } catch (e: Exception) { status = "Error: ${e.message}" }
    }
    DisposableEffect(Unit) { onDispose { lk.disconnect(); lk.release() } }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoView(lk, video, Modifier.fillMaxSize())
        Text("${stream.title} • 👁 $viewers", color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()
                .clickable { showViewers = true }.padding(12.dp))
        if (showViewers) ViewerListDialog(
            remember(viewers, showViewers) { viewerNames(lk) + "$user (you)" }) { showViewers = false }
        if (ended) Text("Stream ended", color = Color.White, modifier = Modifier.align(Alignment.Center))
        else if (status.isNotEmpty()) Text(status, color = Color.White, modifier = Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("❤️", "🌹", "🚀").forEach { g ->
                    FilledTonalButton({ msgs.addCapped("$user sent $g"); scope.launch { lk.sendGift(g) } }) { Text(g) }
                }
            }
            ChatOverlay(msgs, onSend = { msgs.addCapped("$user: $it"); scope.launch { lk.sendChat(it) } })
        }
    }
}

private fun hasPerms(c: Context) = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun BroadcastScreen(server: String, user: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.all { ok -> ok }
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    BackHandler(enabled = !granted, onBack = onBack)
    if (granted) BroadcastContent(server, user, onBack)
    else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Camera and microphone access is needed to broadcast.")
        Spacer(Modifier.height(12.dp))
        Button({ launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
            Text("Grant permissions")
        }
    }
}

@Composable
fun BroadcastContent(server: String, user: String, onBack: () -> Unit) {
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
    val msgs = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        lk.events.collect { e ->
            when (e) {
                is RoomEvent.DataReceived ->
                    parseData(e.participant?.name ?: "?", e.data)?.let { msgs.addCapped(it) }
                is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected ->
                    viewers = lk.remoteParticipants.size
                is RoomEvent.Disconnected -> { live = false; cam = null; status = "Disconnected" }
                else -> {}
            }
        }
    }

    fun stop() {
        lk.disconnect(); live = false; cam = null; status = "Ended"
    }
    fun start() {
        scope.launch {
            try {
                status = "Starting…"
                val j = withContext(Dispatchers.IO) { Api.createRoom(server, title, user) }
                roomId = j.id
                lk.connect(j.url, j.token)
                lk.localParticipant.setCameraEnabled(true)
                lk.localParticipant.setMicrophoneEnabled(true)
                cam = lk.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                live = true; status = "LIVE"
            } catch (e: Exception) { status = "Error: ${e.message}" }
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
            while (true) {
                val b64 = renderer?.let { snapshot(it) }
                if (b64 != null) withContext(Dispatchers.IO) {
                    try { Api.setThumb(server, roomId, b64) } catch (e: Exception) {}
                }
                delay(30000)
            }
        }
    }
    BackHandler { stop(); onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoView(lk, cam, Modifier.fillMaxSize(), onReady = { renderer = it })
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$status • 👁 $viewers", color = Color.White,
                modifier = Modifier.clickable { showViewers = true }
                    .background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(8.dp, 4.dp))
            if (showViewers) ViewerListDialog(
                remember(viewers, showViewers) { viewerNames(lk) }) { showViewers = false }
            if (!live) OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            if (live) ChatOverlay(msgs, onSend = { msgs.addCapped("$user: $it"); scope.launch { lk.sendChat(it) } })
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ if (live) stop() else start() }, Modifier.weight(1f)) { Text(if (live) "End" else "Go Live") }
                OutlinedButton({ cam?.switchCamera() }, enabled = live) { Text("Flip") }
            }
        }
    }
}
