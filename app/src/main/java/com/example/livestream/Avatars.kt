package com.example.livestream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

val AVATAR_COLORS = listOf(0xFFE91E63L, 0xFF9C27B0L, 0xFF3F51B5L, 0xFF009688L, 0xFFFF9800L, 0xFFF44336L, 0xFF2196F3L, 0xFF4CAF50L)

// Profile photos fetched from the server and kept in memory for a few minutes.
object Avatars {
    private class Entry(val bmp: ImageBitmap?, val ts: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    var version by mutableStateOf(0)

    private fun key(n: String) = n.trim().lowercase()

    suspend fun load(name: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val k = key(name)
        if (k.isEmpty() || isGuestAccount(k)) return@withContext null // guests have no profile
        val now = System.currentTimeMillis()
        cache[k]?.let { if (now - it.ts < (if (it.bmp != null) 600_000L else 120_000L)) return@withContext it.bmp }
        val server = Api.serverUrl
        val bmp = if (server.isBlank()) null else Api.fetchAvatar(server, name)
        cache[k] = Entry(bmp, now)
        bmp
    }

    fun put(name: String, bmp: ImageBitmap) {
        cache[key(name)] = Entry(bmp, System.currentTimeMillis())
        version++
    }
}

// Round profile photo, or a colored circle with the first letter when there is none.
@Composable
fun Avatar(name: String, size: Dp) {
    val v = Avatars.version
    val bmp by produceState<ImageBitmap?>(null, name, v) { value = Avatars.load(name) }
    val color = Color(AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size])
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Reads a picked picture, turns it the right way up, crops the middle to a square, scales it to out x out
 * and compresses it to a JPEG small enough (<= maxChars as base64). Returns (base64, bitmap) or null.
 */
fun encodeSquare(ctx: Context, uri: Uri, out: Int, maxChars: Int): Pair<String, Bitmap>? {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= out * 2 && bounds.outHeight / (sample * 2) >= out * 2) sample *= 2
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orient = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            } ?: 1
        } catch (e: Exception) { 1 }
        val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        val upright = if (deg != 0f) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg) }, true) else raw
        val side = minOf(upright.width, upright.height)
        val sq = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(sq, out, out, true)
        var q = 85
        var b64 = ""
        while (q >= 30) {
            val bo = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, q, bo)
            b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= maxChars) break
            q -= 10
        }
        return if (b64.length <= maxChars) Pair(b64, scaled) else null
    } catch (e: Exception) {
        return null
    }
}

/** A small picture downloaded from a web address (used for YouTube search results). */
@Composable
fun UrlThumb(url: String, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            try { java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap() } catch (e: Exception) { null }
        }
    }
    Box(modifier.background(Color.DarkGray)) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
