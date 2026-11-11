package com.example.livestream

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object Api {
    data class Stream(val id: String, val title: String, val host: String, val viewers: Int, val thumb: String = "", val type: String = "", val cc: String = "", val same: Boolean = false, val kind: String = "", val ratio: String = "")
    data class Join(val id: String, val url: String, val token: String, val key: String = "")

    private val http = OkHttpClient()

    /** Login session from the server ("" = guest). Sent with room requests so the server knows the account. */
    @Volatile var auth: String = ""

    /** Account calls (register, login, verify, resend). Returns the server's JSON (it may hold "error", "verify", or "token" + "name"). */
    fun accountCall(server: String, path: String, body: JSONObject): JSONObject =
        http.newCall(Request.Builder().url("${base(server)}/$path")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            try { JSONObject(r.body!!.string()) } catch (e: Exception) { JSONObject().put("error", "Server error " + r.code) }
        }

    data class GiftResult(val ok: Boolean, val diamonds: Int, val error: String, val needDiamonds: Boolean)

    /** Pays a gift with diamonds. ok = paid (the host earns lumis); otherwise error says why. */
    fun sendGift(server: String, room: String, g: String): GiftResult = try {
        val body = JSONObject().put("room", room).put("g", g).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("${base(server)}/gift").header("X-Auth", auth).post(body).build()).execute().use { r ->
            val o = try { JSONObject(r.body!!.string()) } catch (e: Exception) { JSONObject() }
            if (r.isSuccessful) GiftResult(true, o.optInt("diamonds", -1), "", false)
            else GiftResult(false, o.optInt("diamonds", -1), o.optString("error", "Gift failed"), o.optBoolean("needDiamonds"))
        }
    } catch (e: Exception) { GiftResult(false, -1, "No connection", false) }

    data class Wallet(val lumis: Int, val diamonds: Int)

    fun walletInfo(server: String): Wallet? = try {
        http.newCall(Request.Builder().url("${base(server)}/wallet").header("X-Auth", auth).build()).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body!!.string()).let { Wallet(it.optInt("lumis", 0), it.optInt("diamonds", 0)) }
        }
    } catch (e: Exception) { null }

    /** Starts a Stripe checkout for a diamond pack: returns (payment page url, "") or (null, error). */
    fun buy(server: String, diamonds: Int): Pair<String?, String> = try {
        val body = JSONObject().put("diamonds", diamonds).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("${base(server)}/buy").header("X-Auth", auth).post(body).build()).execute().use { r ->
            val o = try { JSONObject(r.body!!.string()) } catch (e: Exception) { JSONObject() }
            if (r.isSuccessful && o.optString("url").isNotEmpty()) Pair(o.getString("url"), "") else Pair(null, o.optString("error", "Could not start the payment"))
        }
    } catch (e: Exception) { Pair(null, "No connection") }

    /** Lumis balance of the logged-in account, or -1 if it cannot be read. */
    fun wallet(server: String): Int = try {
        http.newCall(Request.Builder().url("${base(server)}/wallet").header("X-Auth", auth).build()).execute().use { r ->
            JSONObject(r.body!!.string()).optInt("lumis", -1)
        }
    } catch (e: Exception) { -1 }

    /** The server address, set by the app so small helpers (profile photos) can use it. */
    @Volatile var serverUrl: String = ""

    /** Profile photo of an account, or null if it has none. */
    fun fetchAvatar(server: String, name: String): ImageBitmap? = try {
        val url = "${base(server)}/avatar?u=" + java.net.URLEncoder.encode(name, "UTF-8")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null
            else r.body!!.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    } catch (e: Exception) { null }

    /** Saves the logged-in account's profile photo (base64 JPEG). Returns an error message, or "" when saved. */
    fun uploadAvatar(server: String, b64: String): String = try {
        val body = JSONObject().put("img", b64).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("${base(server)}/avatar").header("X-Auth", auth).post(body).build()).execute().use { r ->
            val o = try { JSONObject(r.body!!.string()) } catch (e: Exception) { JSONObject() }
            if (o.optBoolean("ok")) "" else o.optString("error", "Upload failed")
        }
    } catch (e: Exception) { "Can't reach server" }

    /** Saves the account's live cover (base64 JPEG). Returns "" when saved, otherwise the error message. */
    fun uploadCover(server: String, b64: String): String = try {
        val body = JSONObject().put("img", b64).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("${base(server)}/cover").header("X-Auth", auth).post(body).build()).execute().use { r ->
            val o = try { JSONObject(r.body!!.string()) } catch (e: Exception) { JSONObject() }
            if (o.optBoolean("ok")) "" else o.optString("error", "Upload failed")
        }
    } catch (e: Exception) { "Can't reach server" }

    data class Post(val id: String, val user: String, val kind: String, val caption: String, val ts: Long, val cleared: Boolean = false)

    data class ProfileInfo(val followers: Int, val following: Int, val received: Long, val spent: Long, val posts: List<Post>)

    /** Follower / following numbers and latest posts of an account, or null if the server can't be reached. */
    fun profile(server: String, name: String): ProfileInfo? = try {
        val url = "${base(server)}/profile?u=" + java.net.URLEncoder.encode(name, "UTF-8")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else {
                val o = JSONObject(r.body!!.string())
                val a = o.optJSONArray("posts") ?: JSONArray()
                ProfileInfo(o.optInt("followers"), o.optInt("following"), o.optLong("received"), o.optLong("spent"), (0 until a.length()).map {
                    val p = a.getJSONObject(it)
                    Post(p.getString("id"), name, p.optString("kind", "photo"), p.optString("caption"), p.optLong("ts"), p.optBoolean("v"))
                })
            }
        }
    } catch (e: Exception) { null }

    /** Names that follow [name] (kind "followers") or that [name] follows (kind "following"); null if the server can't be reached. */
    fun people(server: String, name: String, kind: String): List<String>? = try {
        val url = "${base(server)}/people?t=$kind&u=" + java.net.URLEncoder.encode(name, "UTF-8")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else {
                val a = JSONObject(r.body!!.string()).optJSONArray("names") ?: JSONArray()
                (0 until a.length()).map { a.getString(it) }
            }
        }
    } catch (e: Exception) { null }

    /** Who sent my account lumis, as name to total, most first; null if the server can't be reached. */
    fun lumisFrom(server: String): List<Pair<String, Long>>? = try {
        http.newCall(Request.Builder().url("${base(server)}/lumis-from").header("X-Auth", auth).build()).execute().use { r ->
            if (!r.isSuccessful) null else {
                val a = JSONObject(r.body!!.string()).optJSONArray("from") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("name") to o.optLong("lumis") } }
            }
        }
    } catch (e: Exception) { null }

    /** Who I sent diamonds to, as name to total, most first; null if the server can't be reached. */
    fun diamondsTo(server: String): List<Pair<String, Long>>? = try {
        http.newCall(Request.Builder().url("${base(server)}/diamonds-to").header("X-Auth", auth).build()).execute().use { r ->
            if (!r.isSuccessful) null else {
                val a = JSONObject(r.body!!.string()).optJSONArray("to") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("name") to o.optLong("diamonds") } }
            }
        }
    } catch (e: Exception) { null }

    /** Tells the server about a follow / unfollow (fire and forget; the phone's own list still works without it). */
    fun followAsync(server: String, to: String, on: Boolean) {
        if (auth.isEmpty() || server.isBlank()) return
        Thread {
            try {
                val body = JSONObject().put("to", to).put("on", on).toString().toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder().url("${base(server)}/follow").header("X-Auth", auth).post(body).build()).execute().close()
            } catch (e: Exception) {}
        }.start()
    }

    @Volatile var followsSynced = false

    /** Uploads the follows saved on this phone to the account once per app start. */
    fun syncFollows(server: String, names: List<String>) {
        if (auth.isEmpty() || followsSynced || server.isBlank()) return
        try {
            val body = JSONObject().put("names", JSONArray(names)).toString().toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url("${base(server)}/followsync").header("X-Auth", auth).post(body).build()).execute().use { followsSynced = it.isSuccessful }
        } catch (e: Exception) {}
    }

    // uploads of videos can take a while
    private val httpLong = http.newBuilder()
        .callTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build()

    /** Newest posts first. */
    fun posts(server: String): List<Post> = try {
        http.newCall(Request.Builder().url("${base(server)}/posts").build()).execute().use { r ->
            val a = JSONArray(r.body!!.string())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Post(o.getString("id"), o.optString("user"), o.optString("kind", "photo"), o.optString("caption"), o.optLong("ts"), o.optBoolean("v"))
            }
        }
    } catch (e: Exception) { emptyList() }

    /** Step 1 of posting: details and a small preview. Returns the new post id or throws with the server's message. */
    fun createPost(server: String, kind: String, caption: String, thumbB64: String, frames: List<String> = emptyList()): String {
        val body = JSONObject().put("kind", kind).put("caption", caption).put("thumb", thumbB64).put("frames", JSONArray(frames)).toString()
            .toRequestBody("application/json".toMediaType())
        return http.newCall(Request.Builder().url("${base(server)}/posts").header("X-Auth", auth).post(body).build()).execute().use { r ->
            val o = JSONObject(r.body!!.string())
            if (o.has("error")) throw Exception(o.getString("error"))
            o.getString("id")
        }
    }

    /** Step 2: the photo or video file. Returns "" when saved, otherwise the error message. */
    fun uploadPostData(server: String, id: String, file: java.io.File): String = try {
        val body = file.asRequestBody("application/octet-stream".toMediaType())
        httpLong.newCall(Request.Builder().url("${base(server)}/post/$id/data").header("X-Auth", auth).put(body).build()).execute().use { r ->
            val o = try { JSONObject(r.body!!.string()) } catch (e: Exception) { JSONObject() }
            if (o.optBoolean("ok")) "" else o.optString("error", "Upload failed (" + r.code + ")")
        }
    } catch (e: Exception) { "Upload failed: " + (e.message ?: "no connection") }

    /** Poster's phone flagged a picture: asks the server for a second opinion. true = the server agrees (or is unavailable), so refuse. */
    fun confirmNudity(server: String, imgB64: String): Boolean = try {
        val body = JSONObject().put("img", imgB64).toString().toRequestBody("application/json".toMediaType())
        httpLong.newCall(Request.Builder().url("${base(server)}/posts/check").header("X-Auth", auth).post(body).build()).execute().use { r ->
            JSONObject(r.body!!.string()).optBoolean("confirmed", true)
        }
    } catch (e: Exception) { true }

    /** Viewer's phone flagged a post: server second opinion. "removed" = confirmed and deleted, otherwise "ok". */
    fun confirmPost(server: String, id: String, imgB64: String): String = try {
        val body = JSONObject().put("img", imgB64).toString().toRequestBody("application/json".toMediaType())
        httpLong.newCall(Request.Builder().url("${base(server)}/post/$id/check").post(body).build()).execute().use { r ->
            if (JSONObject(r.body!!.string()).optBoolean("removed")) "removed" else "ok"
        }
    } catch (e: Exception) { "ok" }

    data class PostComment(val user: String, val text: String, val ts: Long)
    data class PostInfo(val likes: Int, val liked: Boolean, val comments: List<PostComment>)

    /** Likes + comments of a post. body == null reads (GET); otherwise act is like / comment / uncomment. Null = failed. */
    private fun postCall(server: String, id: String, act: String, body: JSONObject?): PostInfo? = try {
        val b = Request.Builder().url("${base(server)}/post/$id/$act")
        if (auth.isNotEmpty()) b.header("X-Auth", auth)
        if (body != null) b.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) null else {
                val o = JSONObject(r.body!!.string())
                val a = o.optJSONArray("comments") ?: JSONArray()
                PostInfo(o.optInt("likes"), o.optBoolean("liked"), (0 until a.length()).map {
                    val c = a.getJSONObject(it)
                    PostComment(c.optString("u"), c.optString("t"), c.optLong("ts"))
                })
            }
        }
    } catch (e: Exception) { null }

    fun postInfo(server: String, id: String) = postCall(server, id, "info", null)
    fun likePost(server: String, id: String, on: Boolean) = postCall(server, id, "like", JSONObject().put("on", on))
    fun commentPost(server: String, id: String, text: String) = postCall(server, id, "comment", JSONObject().put("text", text))
    fun uncomment(server: String, id: String, ts: Long) = postCall(server, id, "uncomment", JSONObject().put("ts", ts))

    fun deletePost(server: String, id: String): Boolean = try {
        http.newCall(Request.Builder().url("${base(server)}/post/$id").header("X-Auth", auth).delete().build()).execute().use { it.isSuccessful }
    } catch (e: Exception) { false }

    fun fetchBytes(server: String, path: String): ByteArray? = try {
        httpLong.newCall(Request.Builder().url("${base(server)}/$path").build()).execute().use { r ->
            if (r.isSuccessful) r.body!!.bytes() else null
        }
    } catch (e: Exception) { null }

    fun downloadTo(server: String, path: String, file: java.io.File): Boolean = try {
        httpLong.newCall(Request.Builder().url("${base(server)}/$path").build()).execute().use { r ->
            if (!r.isSuccessful) false
            else { r.body!!.byteStream().use { i -> file.outputStream().use { o -> i.copyTo(o) } }; true }
        }
    } catch (e: Exception) { false }

    private fun base(s: String): String {
        val t = s.trim().trimEnd('/')
        return if (t.startsWith("http")) t else "https://$t"
    }

    fun streams(server: String): List<Stream> =
        http.newCall(Request.Builder().url("${base(server)}/rooms").build()).execute().use { r ->
            val a = JSONArray(r.body!!.string())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Stream(o.getString("id"), o.getString("title"), o.getString("host"), o.getInt("viewers"), o.optString("thumb"), o.optString("type"), o.optString("cc"), o.optBoolean("same"), o.optString("kind"), o.optString("ratio"))
            }
        }

    fun createRoom(server: String, title: String, user: String, kind: String = "live", ratio: String = "auto"): Join {
        val body = JSONObject().put("title", title).put("host", user).put("kind", kind).put("ratio", ratio).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("${base(server)}/rooms").header("X-Auth", auth).post(body).build()
        return http.newCall(req).execute().use { r ->
            val o = JSONObject(r.body!!.string())
            if (o.has("error")) throw Exception(o.getString("error"))
            Join(o.getString("id"), o.getString("url"), o.getString("token"), o.optString("key"))
        }
    }

    fun setThumb(server: String, id: String, thumb: String, key: String, type: String = "") {
        val body = JSONObject().put("thumb", thumb).put("type", type).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("${base(server)}/rooms/$id/thumb").header("X-Key", key).post(body).build())
            .execute().close()
    }

    /** Asks the server to double-check a frame the on-device detector flagged. true = server says clean. */
    fun checkFrame(server: String, id: String, img: String, who: String, key: String): Boolean {
        val body = JSONObject().put("img", img).put("who", who).toString()
            .toRequestBody("application/json".toMediaType())
        return http.newCall(Request.Builder().url("${base(server)}/rooms/$id/check").header("X-Key", key)
            .post(body).build()).execute().use { r ->
            try { JSONObject(r.body!!.string()).optBoolean("clean", false) } catch (e: Exception) { false }
        }
    }

    fun invite(server: String, to: String, from: String, name: String, key: String) {
        val body = JSONObject().put("room", from).put("name", name).toString()
            .toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("${base(server)}/rooms/$to/invite").header("X-Key", key).post(body).build())
            .execute().close()
    }

    fun guestToken(server: String, room: String, from: String, user: String): Join {
        val url = "${base(server)}/guest".toHttpUrl().newBuilder().addQueryParameter("room", room)
            .addQueryParameter("from", from).addQueryParameter("user", user).build()
        return http.newCall(Request.Builder().url(url).header("X-Auth", auth).build()).execute().use { r ->
            val o = JSONObject(r.body!!.string())
            if (o.has("error")) throw Exception(o.getString("error"))
            Join(room, o.getString("url"), o.getString("token"), o.optString("key"))
        }
    }

    fun viewerToken(server: String, room: String, user: String): Join {
        val url = "${base(server)}/token".toHttpUrl().newBuilder()
            .addQueryParameter("room", room).addQueryParameter("user", user).build()
        return http.newCall(Request.Builder().url(url).header("X-Auth", auth).build()).execute().use { r ->
            val o = JSONObject(r.body!!.string())
            if (o.has("error")) throw Exception(o.getString("error"))
            Join(room, o.getString("url"), o.getString("token"))
        }
    }
}
