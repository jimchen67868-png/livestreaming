package com.example.livestream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

sealed interface Screen {
    object Home : Screen
    data class Watch(val room: Api.Room) : Screen
    object Live : Screen
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("s", 0) }
    var host by remember { mutableStateOf(sp.getString("host", "") ?: "") }
    var user by remember { mutableStateOf(sp.getString("user", "user" + (100..999).random()) ?: "user") }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    when (val s = screen) {
        Screen.Home -> HomeScreen(host, user,
            { h, u -> host = h; user = u; sp.edit().putString("host", h).putString("user", u).apply() },
            { screen = Screen.Watch(it) }, { screen = Screen.Live })
        is Screen.Watch -> WatchScreen(host, user, s.room) { screen = Screen.Home }
        Screen.Live -> BroadcastScreen(host, user) { screen = Screen.Home }
    }
}

fun handleEvent(e: JSONObject, msgs: MutableList<String>, onViewers: (Int) -> Unit, onEnd: () -> Unit) {
    when (e.optString("type")) {
        "chat" -> msgs.add("${e.optString("user")}: ${e.optString("text")}")
        "gift" -> msgs.add("${e.optString("user")} sent ${e.optString("gift")}")
        "viewers" -> onViewers(e.optInt("count"))
        "end" -> onEnd()
    }
    while (msgs.size > 50) msgs.removeAt(0)
}

@Composable
fun HomeScreen(
    host: String, user: String, onSettings: (String, String) -> Unit,
    onOpen: (Api.Room) -> Unit, onGoLive: () -> Unit
) {
    var rooms by remember { mutableStateOf(listOf<Api.Room>()) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(host) {
        while (true) {
            if (host.isNotBlank()) {
                try { rooms = withContext(Dispatchers.IO) { Api.rooms(host) }; err = "" }
                catch (e: Exception) { err = "Can't reach server" }
            }
            delay(4000)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Live now", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { onSettings(it.trim(), user) }, label = { Text("Server IP") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(user, { onSettings(host, it) }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rooms.isEmpty()) item { Text("No one is live yet") }
            items(rooms, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(r) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleMedium)
                        Text("${r.host} • 👁 ${r.viewers}")
                    }
                }
            }
        }
        Button(onGoLive, Modifier.fillMaxWidth(), enabled = host.isNotBlank()) { Text("Go Live") }
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
fun WatchScreen(host: String, user: String, room: Api.Room, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val msgs = remember { mutableStateListOf<String>() }
    var viewers by remember { mutableIntStateOf(room.viewers) }
    var ended by remember { mutableStateOf(false) }
    val alive = remember { booleanArrayOf(true) }
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.Builder().setUri(Api.hls(host, room.id)).setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2000)
                    .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build()).build())
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Handler(Looper.getMainLooper()).postDelayed({ if (alive[0]) prepare() }, 2000)
                }
            })
            prepare(); playWhenReady = true
        }
    }
    val ws = remember {
        Api.socket(host, room.id, user, false) { e ->
            handleEvent(e, msgs, { c -> viewers = c }, { ended = true })
        }
    }
    DisposableEffect(Unit) { onDispose { alive[0] = false; ws.close(1000, null); player.release() } }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ PlayerView(it).apply { useController = false; this.player = player } }, Modifier.fillMaxSize())
        Text("${room.title} • 👁 $viewers", color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp))
        if (ended) Text("Stream ended", color = Color.White, modifier = Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("❤️", "🌹", "🚀").forEach { g ->
                    FilledTonalButton({ ws.gift(g) }) { Text(g) }
                }
            }
            ChatOverlay(msgs, onSend = { ws.chat(it) })
        }
    }
}

private fun hasPerms(c: Context) = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun BroadcastScreen(host: String, user: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.all { ok -> ok }
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    BackHandler(enabled = !granted, onBack = onBack)
    if (granted) BroadcastContent(host, user, onBack)
    else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Camera and microphone access is needed to broadcast.")
        Spacer(Modifier.height(12.dp))
        Button({ launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
            Text("Grant permissions")
        }
    }
}

@Composable
fun BroadcastContent(host: String, user: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("$user's live") }
    var live by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var viewers by remember { mutableIntStateOf(0) }
    val msgs = remember { mutableStateListOf<String>() }
    var camera by remember { mutableStateOf<RtmpCamera2?>(null) }
    var ws by remember { mutableStateOf<WebSocket?>(null) }

    val checker = remember {
        object : ConnectChecker {
            override fun onConnectionStarted(url: String) { status = "Connecting…" }
            override fun onConnectionSuccess() { status = "LIVE"; live = true }
            override fun onConnectionFailed(reason: String) {
                status = "Failed: $reason"; live = false; camera?.stopStream(); ws?.close(1000, null); ws = null
            }
            override fun onNewBitrate(bitrate: Long) {}
            override fun onDisconnect() { status = "Disconnected"; live = false }
            override fun onAuthError() { status = "Auth error" }
            override fun onAuthSuccess() {}
        }
    }

    fun stop() {
        camera?.let { if (it.isStreaming) it.stopStream() }
        ws?.close(1000, null); ws = null; live = false; status = "Ended"
    }
    fun start() {
        val cam = camera ?: return
        status = "Starting…"
        scope.launch {
            try {
                val id = withContext(Dispatchers.IO) { Api.createRoom(host, title, user) }
                if (!(cam.prepareAudio() &&
                        cam.prepareVideo(1280, 720, 30, 2_500_000, 1, CameraHelper.getCameraOrientation(ctx)))) {
                    status = "Encoder not supported"; return@launch
                }
                ws = Api.socket(host, id, user, true) { e ->
                    handleEvent(e, msgs, { c -> viewers = c }, {})
                }
                cam.startStream(Api.rtmp(host, id))
            } catch (e: Exception) { status = "Error: ${e.message}" }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            camera?.let { if (it.isStreaming) it.stopStream(); if (it.isOnPreview) it.stopPreview() }
            ws?.close(1000, null)
        }
    }
    BackHandler { stop(); onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                OpenGlView(c).also { v ->
                    val cam = RtmpCamera2(v, checker)
                    camera = cam
                    v.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {}
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                            if (!cam.isOnPreview) cam.startPreview(CameraHelper.Facing.BACK)
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            if (cam.isStreaming) cam.stopStream()
                            if (cam.isOnPreview) cam.stopPreview()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$status • 👁 $viewers", color = Color.White,
                modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(8.dp, 4.dp))
            if (!live) OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            if (live) ChatOverlay(msgs, onSend = { ws?.chat(it) })
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ if (live) stop() else start() }, Modifier.weight(1f)) { Text(if (live) "End" else "Go Live") }
                OutlinedButton({ camera?.switchCamera() }) { Text("Flip") }
            }
        }
    }
}
