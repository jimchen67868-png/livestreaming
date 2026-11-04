package com.example.livestream

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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

    /** Tells the server a gift was sent, so the host earns lumis. Failures are ignored (the gift itself still shows). */
    fun sendGift(server: String, room: String, g: String) {
        try {
            val body = JSONObject().put("room", room).put("g", g).toString().toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url("${base(server)}/gift").header("X-Auth", auth).post(body).build()).execute().close()
        } catch (e: Exception) {}
    }

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
