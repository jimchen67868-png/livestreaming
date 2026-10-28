package com.example.livestream

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Api {
    data class Room(val id: String, val title: String, val host: String, val viewers: Int)

    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private fun base(h: String) = "http://$h:3000"

    fun rtmp(h: String, id: String) = "rtmp://$h:1935/live/$id"
    fun hls(h: String, id: String) = "http://$h:8888/live/$id/index.m3u8"

    fun rooms(h: String): List<Room> =
        http.newCall(Request.Builder().url("${base(h)}/rooms").build()).execute().use { r ->
            val a = JSONArray(r.body!!.string())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Room(o.getString("id"), o.getString("title"), o.getString("host"), o.getInt("viewers"))
            }
        }

    fun createRoom(h: String, title: String, user: String): String {
        val body = JSONObject().put("title", title).put("host", user).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("${base(h)}/rooms").post(body).build()
        return http.newCall(req).execute().use { JSONObject(it.body!!.string()).getString("id") }
    }

    fun socket(h: String, id: String, user: String, isHost: Boolean, onEvent: (JSONObject) -> Unit): WebSocket {
        val url = HttpUrl.Builder().scheme("http").host(h).port(3000).addPathSegment("ws")
            .addQueryParameter("room", id).addQueryParameter("user", user)
            .addQueryParameter("host", if (isHost) "1" else "0").build()
        val main = Handler(Looper.getMainLooper())
        return http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { val o = JSONObject(text); main.post { onEvent(o) } } catch (_: Exception) {}
            }
        })
    }
}

fun WebSocket.chat(t: String) = send(JSONObject().put("type", "chat").put("text", t).toString())
fun WebSocket.gift(g: String) = send(JSONObject().put("type", "gift").put("gift", g).toString())
