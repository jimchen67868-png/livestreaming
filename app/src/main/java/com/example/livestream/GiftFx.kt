package com.example.livestream

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Full-screen gift effects: hearts float up, roses rain, the rocket flies across, host gifts pop in.
class GiftEvent(val id: Long, val g: String, val from: String)

// The banner showing who sent a gift. The same person sending the same gift again within 4 seconds raises the count (x2, x3 ...).
class GiftBanner(val id: Long, val g: String, val from: String) {
    var count by mutableStateOf(1)
    var ts by mutableStateOf(System.currentTimeMillis())
}

class GiftFx {
    val events = mutableStateListOf<GiftEvent>()
    val banners = mutableStateListOf<GiftBanner>()
    private var n = 0L
    fun add(g: String, from: String) {
        if (g.isBlank()) return
        events.add(GiftEvent(n++, g, from))
        while (events.size > 6) events.removeAt(0)
        val now = System.currentTimeMillis()
        val key = g.replace("\uFE0F", "")
        val same = banners.lastOrNull { it.from == from && it.g.replace("\uFE0F", "") == key && now - it.ts < 4000 }
        if (same != null) { same.count++; same.ts = now }
        else {
            banners.add(GiftBanner(n++, g, from))
            while (banners.size > 4) banners.removeAt(0)
        }
    }
}

@Composable
fun GiftOverlay(fx: GiftFx) {
    val d = LocalDensity.current
    val cfg = LocalConfiguration.current
    val w = with(d) { cfg.screenWidthDp.dp.toPx() }
    val h = with(d) { cfg.screenHeightDp.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        fx.events.forEachIndexed { i, e -> key(e.id) { GiftAnim(e, i, w, h) { fx.events.remove(e) } } }
        fx.banners.forEachIndexed { i, b -> key(b.id) { GiftBannerView(b, i, w) { fx.banners.remove(b) } } }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun fade(t: Float): Float = minOf(1f, t / 0.06f) * (if (t > 0.85f) (1f - (t - 0.85f) / 0.15f).coerceAtLeast(0f) else 1f)
private fun backOut(x: Float): Float { val c = x.coerceIn(0f, 1f) - 1f; return 1f + 2.70158f * c * c * c + 1.70158f * c * c }

@Composable
private fun BoxScope.Fly(emoji: String, size: Int, tx: () -> Float, ty: () -> Float, sc: () -> Float, al: () -> Float, rot: () -> Float = { 0f }) {
    Text(emoji, fontSize = size.sp, modifier = Modifier.align(Alignment.Center).graphicsLayer {
        translationX = tx(); translationY = ty(); scaleX = sc(); scaleY = sc(); alpha = al().coerceIn(0f, 1f); rotationZ = rot()
    })
}

@Composable
private fun GiftAnim(e: GiftEvent, index: Int, w: Float, h: Float, onDone: () -> Unit) {
    val kind = e.g.replace("\uFE0F", "")
    val dur = when (kind) { "🚀" -> 3600; "🌹" -> 3200; "❤" -> 2800; else -> 3000 }
    val p = remember { Animatable(0f) }
    LaunchedEffect(e.id) { p.animateTo(1f, tween(dur, easing = LinearEasing)); onDone() }
    val rnd = remember(e.id) { Random(e.id * 7919L + 13L) }
    val xs = remember(e.id) { List(8) { (rnd.nextFloat() - 0.5f) * 0.9f } }
    val delays = remember(e.id) { List(8) { rnd.nextFloat() * 0.4f } }

    Box(Modifier.fillMaxSize()) {
        when (kind) {
            "🚀" -> {
                Fly("🚀", 84, { lerp(-w * 0.30f, w * 0.30f, p.value) }, { lerp(h * 0.40f, -h * 0.45f, p.value) }, { 1f }, { fade(p.value) })
                for (i in 1..5) {
                    val q = { (p.value - 0.05f * i).coerceAtLeast(0f) }
                    Fly(if (i % 2 == 0) "🔥" else "✨", 38,
                        { lerp(-w * 0.30f, w * 0.30f, q()) - w * 0.02f * i }, { lerp(h * 0.40f, -h * 0.45f, q()) + h * 0.02f * i },
                        { 1f - 0.12f * i }, { if (q() <= 0f) 0f else fade(p.value) * (0.8f - 0.13f * i) })
                }
            }
            "🌹" -> {
                Fly("🌹", 110, { 0f }, { lerp(0f, -h * 0.08f, p.value) }, { 2f * backOut(p.value / 0.25f) }, { fade(p.value) })
                for (i in 0 until 8) {
                    val q = { ((p.value - delays[i]) / 0.6f).coerceIn(0f, 1f) }
                    Fly("🌹", 34, { xs[i] * w + sin(q() * 6f) * w * 0.03f }, { lerp(-h * 0.5f, h * 0.5f, q()) },
                        { 1f }, { if (q() <= 0f || q() >= 1f) 0f else 1f }, { q() * 360f * (if (i % 2 == 0) 1f else -1f) })
                }
            }
            "❤" -> {
                for (i in 0 until 6) {
                    val q = { ((p.value - i * 0.1f) / 0.6f).coerceIn(0f, 1f) }
                    Fly("❤️", 48 + (i % 3) * 8, { xs[i] * w * 0.5f + sin(q() * 8f) * w * 0.04f }, { lerp(h * 0.45f, -h * 0.35f, q()) },
                        { lerp(0.6f, 1.4f, q()) }, { if (q() <= 0f) 0f else (1f - ((q() - 0.7f) / 0.3f).coerceIn(0f, 1f)) })
                }
            }
            else -> { // host gifts: big pop with sparkles
                Fly(e.g, 100, { 0f }, { lerp(0f, -h * 0.10f, p.value) }, { 1.8f * backOut(p.value / 0.25f) }, { fade(p.value) },
                    { sin(p.value * 20f) * 8f * (1f - p.value) })
                for (i in 0 until 8) {
                    val a = (i / 8f) * 2f * PI.toFloat()
                    val dist = { lerp(0f, w * 0.35f, backOut(p.value / 0.5f).coerceAtMost(1.1f)) }
                    Fly("✨", 30, { cos(a) * dist() }, { sin(a) * dist() - h * 0.05f }, { 1f }, { fade(p.value) })
                }
            }
        }
    }
}


// Bigo-style banner: round avatar with the sender's initial, name, "Sent <gift>", a big gift icon and the combo count.
@Composable
private fun GiftBannerView(b: GiftBanner, index: Int, w: Float, onDone: () -> Unit) {
    val slide = remember { Animatable(0f) }
    val bump = remember { Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(1f, tween(350)) }
    LaunchedEffect(b.count) { bump.snapTo(1.7f); bump.animateTo(1f, tween(300)) }
    LaunchedEffect(b.ts) { delay(3200); onDone() }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.CenterStart).offset(y = (-120 + index * 60).dp).padding(start = 8.dp)
                .graphicsLayer { translationX = lerp(-w * 0.7f, 0f, slide.value) }
                .background(Brush.horizontalGradient(listOf(Color(0xCCB0003A), Color(0xCC6A1B9A), Color(0x336A1B9A))), RoundedCornerShape(30.dp))
                .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(b.from, 40.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(b.from, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("Sent ${b.g}", color = Color(0xFFFFE0B2), fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(b.g, fontSize = 34.sp)
            if (b.count > 1) {
                Spacer(Modifier.width(8.dp))
                Text("x${b.count}", color = Color(0xFFFFEB3B), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
                    modifier = Modifier.graphicsLayer { scaleX = bump.value; scaleY = bump.value })
            }
        }
    }
}
