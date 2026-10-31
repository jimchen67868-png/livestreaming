"""Run from the repo root (~/livestreaming):  python apply_livetype.py

Adds: auto-detected live type (talk / group / scene / pk), the worker stores and returns it,
the phone learns which types you watch, and Home sorts rooms by that preference.
Safe to re-run: it stops if a piece is already applied or an anchor is missing.
"""
import sys

KT = "app/src/main/java/com/example/livestream/MainActivity.kt"
API = "app/src/main/java/com/example/livestream/Api.kt"
WK = "worker/worker.js"


PENDING = {}


def patch(path, edits):
    s = open(path, encoding="utf-8").read()
    for old, new in edits:
        n = s.count(old)
        if n != 1:
            sys.exit(f"STOP (nothing was changed): in {path} expected 1 match, found {n} for:\n{old[:90]}")
        s = s.replace(old, new)
    PENDING[path] = s


if "LiveType" in open(KT, encoding="utf-8").read():
    sys.exit("Already applied.")

# ---------------- worker ----------------
patch(WK, [
    ("thumb: m.thumb || '' };",
     "thumb: m.thumb || '', type: m.type || '' };"),
    ("const { thumb } = await req.json();",
     "const { thumb, type } = await req.json();"),
    ("m.thumb = thumb;\n",
     "m.thumb = thumb;\n        if (['talk', 'group', 'scene', 'pk'].includes(type)) m.type = type;\n"),
])

# ---------------- Api.kt ----------------
patch(API, [
    ('val viewers: Int, val thumb: String = "")',
     'val viewers: Int, val thumb: String = "", val type: String = "")'),
    ('o.getInt("viewers"), o.optString("thumb"))',
     'o.getInt("viewers"), o.optString("thumb"), o.optString("type"))'),
    ("fun setThumb(server: String, id: String, thumb: String, key: String) {",
     'fun setThumb(server: String, id: String, thumb: String, key: String, type: String = "") {'),
    ('JSONObject().put("thumb", thumb).toString()',
     'JSONObject().put("thumb", thumb).put("type", type).toString()'),
])

# ---------------- MainActivity.kt ----------------
NEW_OBJECTS = '''object LiveType {
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

    fun icon(t: String) = when (t) { "talk" -> "💬 "; "group" -> "👥 "; "scene" -> "🎬 "; "pk" -> "⚔️ "; else -> "" }
}

// What this viewer watches: +1 per 30 s visit, older visits fade (x0.95 each time).
object Pref {
    private val types = listOf("talk", "group", "scene", "pk")
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

'''

patch(KT, [
    # objects
    ("fun decodeThumb(b64: String): ImageBitmap? = try {",
     NEW_OBJECTS + "fun decodeThumb(b64: String): ImageBitmap? = try {"),
    # broadcaster sends the type with the thumbnail
    ("""                            val t = squareThumb(frame)
                            withContext(Dispatchers.IO) {
                                try { Api.setThumb(server, roomId, t, hostKey) } catch (e: Exception) {}
                            }""",
     """                            val t = squareThumb(frame)
                            val ty = if (pkActive) "pk" else withContext(Dispatchers.Default) { LiveType.detect(frame) }
                            withContext(Dispatchers.IO) {
                                try { Api.setThumb(server, roomId, t, hostKey, ty) } catch (e: Exception) {}
                            }"""),
    # Home: sort by preference, show type icon
    ("""    var err by remember { mutableStateOf("") }
    LaunchedEffect(server) {""",
     """    var err by remember { mutableStateOf("") }
    val hctx = LocalContext.current
    val sorted = remember(streams) {
        streams.sortedWith(compareByDescending<Api.Stream> { Pref.score(hctx, it.type) }.thenByDescending { it.viewers })
    }
    LaunchedEffect(server) {"""),
    ("items(streams, key = { it.id }) { r ->", "items(sorted, key = { it.id }) { r ->"),
    ('Text("${r.host} • 👁 ${r.viewers}"', 'Text("${LiveType.icon(r.type)}${r.host} • 👁 ${r.viewers}"'),
    # Watch: count a visit after 30 s
    ("""    LaunchedEffect(result) { if (result.isNotEmpty()) { delay(8000); result = "" } }
    DisposableEffect(Unit) { onDispose { lk.disconnect(); lk.release() } }""",
     """    LaunchedEffect(result) { if (result.isNotEmpty()) { delay(8000); result = "" } }
    LaunchedEffect(Unit) { delay(30000); Pref.bump(ctx, stream.type) }
    DisposableEffect(Unit) { onDispose { lk.disconnect(); lk.release() } }"""),
])
for _p, _s in PENDING.items():
    open(_p, "w", encoding="utf-8").write(_s)
    print("patched", _p)
print("Done. Now: git add -A && git commit -m 'live type + preference sort' && git push, then redeploy worker.js")
