"""Run from the repo root (~/livestreaming) AFTER apply_livetype.py and apply_screenshare.py:  python apply_rooms.py

Two room kinds, chosen before Go Live:
  Live  = camera + chat + gifts + PK. No screen share (the worker's host token leaves it out).
  Audio = mic only. The host can share the screen or play a YouTube video.
YouTube is a SYNCED PLAYER: each viewer plays the video in their own embedded YouTube player; the host's
play / pause / position is sent to everyone every 2 s as a small data message. No video goes through your stream.
Nothing is written unless every edit matches.
"""
import re
import sys

KT = "app/src/main/java/com/example/livestream/MainActivity.kt"
API = "app/src/main/java/com/example/livestream/Api.kt"
WK = "worker/worker.js"

kt0 = open(KT, encoding="utf-8").read()
if "shareLauncher" not in kt0:
    sys.exit("Run apply_livetype.py and apply_screenshare.py first.")
if "YtView" in kt0:
    sys.exit("Already applied.")

PENDING = {}


def text(path):
    return PENDING.get(path) or open(path, encoding="utf-8").read()


def patch(path, edits):
    s = text(path)
    for old, new in edits:
        n = s.count(old)
        if n != 1:
            sys.exit(f"STOP (nothing was changed): in {path} expected 1 match, found {n} for:\n{old[:110]}")
        s = s.replace(old, new)
    PENDING[path] = s


def regex(path, pattern, repl):
    s = text(path)
    found = re.findall(pattern, s, flags=re.M)
    if len(found) != 1:
        sys.exit(f"STOP (nothing was changed): in {path} expected 1 match, found {len(found)} for regex:\n{pattern}")
    PENDING[path] = re.sub(pattern, repl, s, count=1, flags=re.M)


# ---------------- worker ----------------
patch(WK, [
    ("const { title, host } = await req.json();",
     "const { title, host, kind } = await req.json();\n        const k = kind === 'audio' ? 'audio' : 'live';"),
    ("metadata: JSON.stringify({ title: t, host: h",
     "metadata: JSON.stringify({ title: t, host: h, kind: k"),
    ("video: { room: id, roomJoin: true, canPublish: true, canSubscribe: true, canPublishData: true } });",
     "video: { room: id, roomJoin: true, canPublish: true, canSubscribe: true, canPublishData: true,\n"
     "            canPublishSources: k === 'audio' ? ['microphone', 'screen_share', 'screen_share_audio'] : ['camera', 'microphone'] } });"),
    ("type: m.type || ''",
     "type: m.kind === 'audio' ? 'audio' : (m.type || ''), kind: m.kind || 'live'"),
])

# ---------------- Api.kt ----------------
regex(API, r'^(\s*data class Stream\(.*)\)\s*$', r'\1, val kind: String = "")')
regex(API, r'^(\s*Stream\(o\.getString\("id"\).*)\)\s*$', r'\1, o.optString("kind"))')
patch(API, [
    ("fun createRoom(server: String, title: String, user: String): Join {",
     'fun createRoom(server: String, title: String, user: String, kind: String = "live"): Join {'),
    ('JSONObject().put("title", title).put("host", user).toString()',
     'JSONObject().put("title", title).put("host", user).put("kind", kind).toString()'),
])

# ---------------- MainActivity.kt ----------------
YT_CODE = '''// ---------- YouTube synced player (audio rooms) ----------
fun youtubeId(s: String): String? {
    val t = s.trim()
    if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) return t
    return Regex("(?:v=|youtu\\\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").find(t)?.groupValues?.get(1)
}

private const val YT_HTML = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}body{pointer-events:__PE__}</style></head>
<body><div id="p"></div>
<script>
var player=null,ready=false,pending=null;
var s=document.createElement('script');s.src='https://www.youtube.com/iframe_api';document.head.appendChild(s);
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{host:'https://www.youtube-nocookie.com',width:'100%',height:'100%',
    playerVars:{playsinline:1,controls:__CT__,rel:0,modestbranding:1,fs:0},
    events:{onReady:function(){ready=true;if(pending){var c=pending;pending=null;apply(c);}}}});
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
        if (host) while (true) {
            delay(2000)
            web?.evaluateJavascript("state()") { r ->
                try { if (r != null && r != "null") onHostState(JSONObject(r)) } catch (e: Exception) {}
            }
        }
    }
    DisposableEffect(web) { onDispose { try { web?.destroy() } catch (e: Exception) {} } }
}

'''

patch(KT, [
    ("import android.os.Bundle\n",
     "import android.os.Bundle\nimport android.webkit.WebChromeClient\nimport android.webkit.WebView\n"),
    ('"screen" -> "🖥 "; else -> ""', '"screen" -> "🖥 "; "audio" -> "🎙 "; else -> ""'),
    ('listOf("talk", "group", "scene", "pk", "screen")', 'listOf("talk", "group", "scene", "pk", "screen", "audio")'),
    ("fun decodeThumb(b64: String): ImageBitmap? = try {", YT_CODE + "fun decodeThumb(b64: String): ImageBitmap? = try {"),

    # Home card placeholder
    ('contentAlignment = Alignment.Center) { Text("📺") }',
     'contentAlignment = Alignment.Center) { Text(if (r.kind == "audio") "🎙" else "📺") }'),

    # ---- broadcaster ----
    ("    var sharing by remember { mutableStateOf(false) }\n    val shareLauncher",
     '''    var sharing by remember { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf("live") }
    var ytId by remember { mutableStateOf("") }
    var ytCmd by remember { mutableStateOf<JSONObject?>(null) }
    var showYt by remember { mutableStateOf(false) }
    var ytText by remember { mutableStateOf("") }
    val shareLauncher'''),
    ("val j = withContext(Dispatchers.IO) { Api.createRoom(server, title, user) }",
     "val j = withContext(Dispatchers.IO) { Api.createRoom(server, title, user, kind) }"),
    ("                lk.localParticipant.setCameraEnabled(true)\n                lk.localParticipant.setMicrophoneEnabled(true)\n                cam =",
     "                if (kind == \"live\") lk.localParticipant.setCameraEnabled(true)\n                lk.localParticipant.setMicrophoneEnabled(true)\n                cam ="),
    ("if (ev == Lifecycle.Event.ON_RESUME && live && held == null) scope.launch {\n                try {\n                    lk.localParticipant.setCameraEnabled(false)",
     "if (ev == Lifecycle.Event.ON_RESUME && live && held == null && kind == \"live\") scope.launch {\n                try {\n                    lk.localParticipant.setCameraEnabled(false)"),
    ("                    val frame = renderer?.let { captureFrame(it) }\n                    if (frame != null) {\n                        if (withContext(Dispatchers.Default) { LocalGuard.suspicious(frame) }) {\n                            // local detection",
     "                    val frame = if (kind == \"audio\") null else renderer?.let { captureFrame(it) }\n                    if (frame != null) {\n                        if (withContext(Dispatchers.Default) { LocalGuard.suspicious(frame) }) {\n                            // local detection"),
    ("            VideoView(lk, cam, Modifier.weight(1f).fillMaxHeight(), onReady = { renderer = it })",
     '''            if (kind == "audio") Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (ytId.isNotEmpty()) YtView(Modifier.fillMaxSize(), true, ytCmd) { s ->
                    if (s.optString("id").isNotEmpty()) scope.launch { try { lk.send(s.put("t", "yt")) } catch (e: Exception) {} }
                } else Text("🎙", style = MaterialTheme.typography.displayLarge)
            } else VideoView(lk, cam, Modifier.weight(1f).fillMaxHeight(), onReady = { renderer = it })'''),
    ('            if (!live) OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)',
     '''            if (!live) OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
            if (!live) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SideBtn("🎥 Live", kind == "live") { kind = "live" }
                SideBtn("🎙 Audio", kind == "audio") { kind = "audio" }
            }'''),
    # buttons: PK / Flip only in live rooms; Share / YouTube only in audio rooms
    ('OutlinedButton({ showPick = true }, enabled = live && !pkActive) { Text("PK") }',
     'if (kind == "live") OutlinedButton({ showPick = true }, enabled = live && !pkActive) { Text("PK") }'),
    ('                OutlinedButton({ cam?.switchCamera() }, enabled = live) { Text("Flip") }',
     '                if (kind == "live") OutlinedButton({ cam?.switchCamera() }, enabled = live) { Text("Flip") }'),
    ("                OutlinedButton({\n                    if (sharing) scope.launch {",
     "                if (kind == \"audio\") OutlinedButton({\n                    if (sharing) scope.launch {"),
    ('}, enabled = live && !pkActive && held == null) { Text(if (sharing) "Unshare" else "Share") }',
     '''}, enabled = live && !pkActive && held == null) { Text(if (sharing) "Unshare" else "Share") }
                if (kind == "audio") OutlinedButton({ showYt = true }, enabled = live) { Text("YouTube") }'''),
    ("        if (showViewers) ViewerListDialog(remember(viewers, showViewers) { viewerNames(lk) }) { showViewers = false }",
     '''        if (showViewers) ViewerListDialog(remember(viewers, showViewers) { viewerNames(lk) }) { showViewers = false }
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
        )'''),
    # PK picker: only live rooms
    (".filter { it.id != roomId }", ".filter { it.id != roomId && it.kind != \"audio\" }"),

    # ---- viewer (WatchScreen) ----
    ("    var screenV by remember { mutableStateOf<VideoTrack?>(null) }\n",
     "    var screenV by remember { mutableStateOf<VideoTrack?>(null) }\n    var yt by remember { mutableStateOf<JSONObject?>(null) }\n"),
    ('"goto" -> if (fromHost) onSwitch(o.optString("room"))',
     '"goto" -> if (fromHost) onSwitch(o.optString("room"))\n                                "yt" -> if (fromHost) yt = if (o.optString("id").isEmpty()) null else o'),
    ("                VideoView(lk, screenV ?: hostV, Modifier.fillMaxSize())\n",
     '''                if (yt != null) YtView(Modifier.fillMaxSize(), false, yt)
                else VideoView(lk, screenV ?: hostV, Modifier.fillMaxSize())
                if (stream.kind == "audio" && yt == null && screenV == null)
                    Text("🎙", style = MaterialTheme.typography.displayLarge, modifier = Modifier.align(Alignment.Center))
'''),
])

for p, s in PENDING.items():
    open(p, "w", encoding="utf-8").write(s)
    print("patched", p)
print("Done. git add -A && git commit -m 'live + audio rooms, YouTube synced player' && git push, then redeploy worker.js")
