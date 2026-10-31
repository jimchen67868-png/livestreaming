"""Run from the repo root (~/livestreaming) AFTER apply_livetype.py:  python apply_location.py

Country-level location from Cloudflare (no GPS, no new app permission):
 - the worker saves the broadcaster's country (2 letters) in the room metadata
 - /rooms returns it, plus "same": true when the viewer is in that country too
 - Home shows a flag on each card and lists same-country rooms first
Nothing is written unless every edit matches.
"""
import sys

KT = "app/src/main/java/com/example/livestream/MainActivity.kt"
API = "app/src/main/java/com/example/livestream/Api.kt"
WK = "worker/worker.js"

if "object LiveType" not in open(KT, encoding="utf-8").read():
    sys.exit("Run apply_livetype.py first.")
if "ccOf" in open(WK, encoding="utf-8").read():
    sys.exit("Already applied.")

PENDING = {}


def patch(path, edits):
    s = open(path, encoding="utf-8").read()
    for old, new in edits:
        n = s.count(old)
        if n != 1:
            sys.exit(f"STOP (nothing was changed): in {path} expected 1 match, found {n} for:\n{old[:90]}")
        s = s.replace(old, new)
    PENDING[path] = s


# ---------------- worker ----------------
patch(WK, [
    ("const json = (o, status = 200) =>",
     """// Country from Cloudflare (IP based, country level only). '' when unknown.
const ccOf = (req) => {
  const c = String((req.cf && req.cf.country) || '').toUpperCase();
  return /^[A-Z]{2}$/.test(c) && c !== 'XX' ? c : '';
};

const json = (o, status = 200) =>"""),
    ("thumb: m.thumb || '', type: m.type || '' };",
     "thumb: m.thumb || '', type: m.type || '', cc: m.cc || '', same: !!m.cc && m.cc === ccOf(req) };"),
    ("{ name: id, metadata: JSON.stringify({ title: t, host: h }), empty_timeout: 30 }",
     "{ name: id, metadata: JSON.stringify({ title: t, host: h, cc: ccOf(req) }), empty_timeout: 30 }"),
])

# ---------------- Api.kt ----------------
patch(API, [
    ('val thumb: String = "", val type: String = "")',
     'val thumb: String = "", val type: String = "", val cc: String = "", val same: Boolean = false)'),
    ('o.optString("thumb"), o.optString("type"))',
     'o.optString("thumb"), o.optString("type"), o.optString("cc"), o.optBoolean("same"))'),
])

# ---------------- MainActivity.kt ----------------
patch(KT, [
    ("object LiveType {",
     """// "US" -> flag emoji + space; "" when unknown.
fun flag(cc: String): String =
    if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) ""
    else cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("") + " "

object LiveType {"""),
    ("compareByDescending<Api.Stream> { Pref.score(hctx, it.type) }.thenByDescending { it.viewers }",
     "compareByDescending<Api.Stream> { it.same }.thenByDescending { Pref.score(hctx, it.type) }.thenByDescending { it.viewers }"),
    ('Text("${LiveType.icon(r.type)}${r.host} • 👁 ${r.viewers}"',
     'Text("${flag(r.cc)}${LiveType.icon(r.type)}${r.host} • 👁 ${r.viewers}"'),
])

for p, s in PENDING.items():
    open(p, "w", encoding="utf-8").write(s)
    print("patched", p)
print("Done. git add -A && git commit -m 'country flag + same-country first' && git push, then redeploy worker.js")
