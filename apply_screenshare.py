"""Run from the repo root (~/livestreaming) AFTER apply_livetype.py:  python apply_screenshare.py

Host can share the phone screen (Android MediaProjection through LiveKit's built-in service).
 - Broadcast screen: a "Share" / "Unshare" button (not during PK)
 - Viewers: the shared screen fills the left pane, the host camera becomes a small corner picture
 - Home: shared rooms get the type "screen" (🖥)
KNOWN GAP: the on-phone nudity check watches the CAMERA only. The shared screen is NOT checked yet.
Nothing is written unless every edit matches.
"""
import sys

KT = "app/src/main/java/com/example/livestream/MainActivity.kt"
MF = "app/src/main/AndroidManifest.xml"
WK = "worker/worker.js"

kt = open(KT, encoding="utf-8").read()
if "object LiveType" not in kt:
    sys.exit("Run apply_livetype.py first.")
if "shareLauncher" in kt:
    sys.exit("Already applied.")

PENDING = {}


def patch(path, edits):
    s = open(path, encoding="utf-8").read()
    for old, new in edits:
        n = s.count(old)
        if n != 1:
            sys.exit(f"STOP (nothing was changed): in {path} expected 1 match, found {n} for:\n{old[:100]}")
        s = s.replace(old, new)
    PENDING[path] = s


# ---------------- manifest ----------------
patch(MF, [
    ('<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />',
     '''<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />'''),
    ("    </application>",
     '''        <service
            android:name="io.livekit.android.room.track.screencapture.ScreenCaptureService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
    </application>'''),
])

# ---------------- worker: allow type "screen" ----------------
patch(WK, [
    ("['talk', 'group', 'scene', 'pk'].includes(type)", "['talk', 'group', 'scene', 'pk', 'screen'].includes(type)"),
])

# ---------------- MainActivity.kt ----------------
patch(KT, [
    # imports
    ("import android.Manifest\n",
     "import android.Manifest\nimport android.app.Activity\nimport android.media.projection.MediaProjectionManager\n"),
    ("import io.livekit.android.room.track.Track\n",
     "import io.livekit.android.room.track.Track\nimport io.livekit.android.room.track.screencapture.ScreenCaptureParams\n"),
    # type "screen"
    ('"pk" -> "⚔️ "; else -> ""', '"pk" -> "⚔️ "; "screen" -> "🖥 "; else -> ""'),
    ('listOf("talk", "group", "scene", "pk")', 'listOf("talk", "group", "scene", "pk", "screen")'),
    ('val ty = if (pkActive) "pk" else withContext(Dispatchers.Default) { LiveType.detect(frame) }',
     'val ty = if (pkActive) "pk" else if (sharing) "screen" else withContext(Dispatchers.Default) { LiveType.detect(frame) }'),

    # ---- viewer (WatchScreen) ----
    ('    var hostV by remember { mutableStateOf<VideoTrack?>(null) }\n    var guestV by remember { mutableStateOf<VideoTrack?>(null) }\n    var ended',
     '    var hostV by remember { mutableStateOf<VideoTrack?>(null) }\n    var screenV by remember { mutableStateOf<VideoTrack?>(null) }\n    var guestV by remember { mutableStateOf<VideoTrack?>(null) }\n    var ended'),
    ("                                } else hostV = t\n",
     "                                } else if (e.publication.source == Track.Source.SCREEN_SHARE) screenV = t\n                                else hostV = t\n"),
    ("                            if (isGuest(e.participant.identity?.value)) guestV = null\n                        }\n                        is RoomEvent.DataReceived",
     "                            if (isGuest(e.participant.identity?.value)) guestV = null\n                            else if (e.publication.source == Track.Source.SCREEN_SHARE) screenV = null\n                        }\n                        is RoomEvent.DataReceived"),
    ("            hostV = remoteVideo(lk, false)\n            guestV = remoteVideo(lk, true)\n",
     "            hostV = remoteVideo(lk, false)\n            guestV = remoteVideo(lk, true)\n            screenV = lk.remoteParticipants.values.filter { !isGuest(it.identity?.value) }\n                .firstNotNullOfOrNull { it.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack }\n"),
    ("""            VideoView(lk, hostV, Modifier.weight(1f).fillMaxHeight())
            if (guestV != null) VideoView(lk, guestV, Modifier.weight(1f).fillMaxHeight())
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding()) {
            Text("${stream.title}""",
     """            Box(Modifier.weight(1f).fillMaxHeight()) {
                VideoView(lk, screenV ?: hostV, Modifier.fillMaxSize())
                if (screenV != null && hostV != null) VideoView(lk, hostV,
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(84.dp, 120.dp))
            }
            if (guestV != null) VideoView(lk, guestV, Modifier.weight(1f).fillMaxHeight())
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding()) {
            Text("${stream.title}"""),

    # ---- broadcaster ----
    ("""    var held by remember { mutableStateOf<Bitmap?>(null) }

    fun startPk()""",
     """    var held by remember { mutableStateOf<Bitmap?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) scope.launch {
            try {
                lk.localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(data))
                sharing = true
            } catch (e: Exception) { status = "Share error: ${e.message}" }
        }
    }

    fun startPk()"""),
    # leaving / ending: stop sharing state
    ("        lk.disconnect(); live = false; cam = null; status = \"Ended\"\n",
     "        lk.disconnect(); live = false; cam = null; sharing = false; status = \"Ended\"\n"),
    # camera flagged: stop the share too
    ("""                            held = frame
                            notice = "⚠️ Possible nudity detected. Your video is blocked until it is checked."
""",
     """                            held = frame
                            notice = "⚠️ Possible nudity detected. Your video is blocked until it is checked."
                            if (sharing) { try { lk.localParticipant.setScreenShareEnabled(false) } catch (e: Exception) {}; sharing = false }
"""),
    # no gender rule while sharing (faces on the shared screen are not the host's camera)
    ("} else if (withContext(Dispatchers.Default) { GenderGuard.mixed(frame) }) {",
     "} else if (!sharing && withContext(Dispatchers.Default) { GenderGuard.mixed(frame) }) {"),
    # button
    ("""                OutlinedButton({ cam?.switchCamera() }, enabled = live) { Text("Flip") }""",
     """                OutlinedButton({
                    if (sharing) scope.launch {
                        try { lk.localParticipant.setScreenShareEnabled(false) } catch (e: Exception) {}
                        sharing = false
                    } else {
                        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        shareLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                }, enabled = live && !pkActive && held == null) { Text(if (sharing) "Unshare" else "Share") }
                OutlinedButton({ cam?.switchCamera() }, enabled = live) { Text("Flip") }"""),
])

for p, s in PENDING.items():
    open(p, "w", encoding="utf-8").write(s)
    print("patched", p)
print("Done. git add -A && git commit -m 'host screen share' && git push, then redeploy worker.js")
