package com.example.livestream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.view.OpenGlView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

@Composable
fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 },
                    { Icon(Icons.Filled.PlayArrow, null) }, label = { Text("Watch") })
                NavigationBarItem(tab == 1, { tab = 1 },
                    { Icon(Icons.Filled.Videocam, null) }, label = { Text("Go Live") })
            }
        }
    ) { pad ->
        Surface(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) WatchScreen() else GoLiveScreen()
        }
    }
}

@Composable
fun WatchScreen() {
    val ctx = LocalContext.current
    var url by rememberSaveable { mutableStateOf("http://SERVER_IP:8888/live/mystream/index.m3u8") }
    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Column(Modifier.fillMaxSize().padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        OutlinedTextField(url, { url = it }, label = { Text("Stream URL (HLS .m3u8)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            player.setMediaItem(
                MediaItem.Builder().setUri(url).setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(2000)
                        .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f)
                        .build()
                ).build()
            ); player.prepare(); player.playWhenReady = true
        }, modifier = Modifier.fillMaxWidth()) { Text("Play") }
    }
}

private fun hasPerms(c: Context) = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun GoLiveScreen() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.all { ok -> ok }
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Camera and microphone access is needed to broadcast.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }) { Text("Grant permissions") }
        }
        return
    }

    var url by rememberSaveable { mutableStateOf("rtmp://SERVER_IP:1935/live/mystream") }
    var live by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var camera by remember { mutableStateOf<RtmpCamera2?>(null) }

    val checker = remember {
        object : ConnectChecker {
            override fun onConnectionStarted(url: String) { status = "Connecting…" }
            override fun onConnectionSuccess() { status = "LIVE"; live = true }
            override fun onConnectionFailed(reason: String) {
                status = "Failed: $reason"; live = false; camera?.stopStream()
            }
            override fun onNewBitrate(bitrate: Long) {}
            override fun onDisconnect() { status = "Disconnected"; live = false }
            override fun onAuthError() { status = "Auth error" }
            override fun onAuthSuccess() {}
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            camera?.let { if (it.isStreaming) it.stopStream(); if (it.isOnPreview) it.stopPreview() }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Text(status, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, { url = it }, label = { Text("RTMP ingest URL + stream key") },
            singleLine = true, enabled = !live, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val cam = camera ?: return@Button
                    if (live) { cam.stopStream(); live = false; status = "Stopped" }
                    else if (cam.prepareAudio() &&
                        cam.prepareVideo(1280, 720, 30, 2_500_000, 1, CameraHelper.getCameraOrientation(ctx))) {
                        cam.startStream(url)
                    } else status = "Encoder not supported"
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (live) "Stop" else "Go Live") }
            OutlinedButton(onClick = { camera?.switchCamera() }) { Text("Flip") }
        }
    }
}
