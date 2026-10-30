package com.example.livestream

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object Api {
    data class Stream(val id: String, val title: String, val host: String, val viewers: Int, val thumb: String = "")
    data class Join(val id: String, val url: String, val token: String, val key: String = "")

    private val http = OkHttpClient()

    private fun base(s: String): String {
        val t = s.trim().trimEnd('/')
        return if (t.startsWith("http")) t else "https://$t"
    }

    fun streams(server: String): List<Stream> =
        http.newCall(Request.Builder().url("${base(server)}/rooms").build()).execute().use { r ->
            val a = JSONArray(r.body!!.string())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Stream(o.getString("id"), o.getString("title"), o.getString("host"), o.getInt("viewers"), o.optString("thumb"))
            }
        }

    fun createRoom(server: String, title: String, user: String): Join {
        val body = JSONObject().put("title", title).put("host", user).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("${base(server)}/rooms").post(body).build()
        return http.newCall(req).execute().use { r ->
            val o = JSONObject(r.body!!.string())
            Join(o.getString("id"), o.getString("url"), o.getString("token"), o.optString("key"))
        }
    }

    fun setThumb(server: String, id: String, thumb: String, key: String) {
        val body = JSONObject().put("thumb", thumb).toString().toRequestBody("application/json".toMediaType())
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
        return http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val o = JSONObject(r.body!!.string())
            Join(room, o.getString("url"), o.getString("token"), o.optString("key"))
        }
    }

    fun viewerToken(server: String, room: String, user: String): Join {
        val url = "${base(server)}/token".toHttpUrl().newBuilder()
            .addQueryParameter("room", room).addQueryParameter("user", user).build()
        return http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val o = JSONObject(r.body!!.string())
            Join(room, o.getString("url"), o.getString("token"))
        }
    }
}
