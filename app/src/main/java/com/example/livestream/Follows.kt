package com.example.livestream

import android.content.Context

// Follow list kept on this phone (by display name). Followed people who go live are shown first on Home.
object Follows {
    private fun key(name: String) = name.trim().lowercase()

    private fun all(ctx: Context): Set<String> =
        (ctx.getSharedPreferences("s", 0).getString("follows", "") ?: "")
            .split("\n").filter { it.isNotBlank() }.toSet()

    fun has(ctx: Context, name: String): Boolean = key(name) in all(ctx)

    // returns true if the person is now followed
    fun toggle(ctx: Context, name: String): Boolean {
        val k = key(name)
        if (k.isEmpty()) return false
        val s = all(ctx).toMutableSet()
        val now = if (k in s) { s.remove(k); false } else { s.add(k); true }
        ctx.getSharedPreferences("s", 0).edit().putString("follows", s.joinToString("\n")).apply()
        return now
    }
}
