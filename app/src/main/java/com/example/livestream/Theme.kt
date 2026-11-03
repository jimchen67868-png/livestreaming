package com.example.livestream

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// App colors: dark or light, plus an accent color. Saved on this phone.
object AppTheme {
    // name, accent in dark mode, accent in light mode
    val accents = listOf(
        Triple("Purple", 0xFFD0BCFFL, 0xFF6650A4L),
        Triple("Blue", 0xFF8AB4F8L, 0xFF1A73E8L),
        Triple("Green", 0xFF81C995L, 0xFF1E8E3EL),
        Triple("Pink", 0xFFF48FB1L, 0xFFD81B60L),
        Triple("Orange", 0xFFFFB74DL, 0xFFE65100L),
    )
    var dark by mutableStateOf(true)
    var accent by mutableStateOf(0)

    fun load(sp: SharedPreferences) {
        dark = sp.getBoolean("theme_dark", true)
        accent = sp.getInt("theme_accent", 0).coerceIn(0, accents.size - 1)
    }

    fun save(sp: SharedPreferences) {
        sp.edit().putBoolean("theme_dark", dark).putInt("theme_accent", accent).apply()
    }

    fun scheme(): ColorScheme {
        val a = accents[accent]
        return if (dark) darkColorScheme(primary = Color(a.second), onPrimary = Color(0xFF202020))
        else lightColorScheme(primary = Color(a.third), onPrimary = Color.White)
    }
}

@Composable
fun ThemeDialog(sp: SharedPreferences, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Theme") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ AppTheme.dark = true; AppTheme.save(sp) }) { Text(if (AppTheme.dark) "🌙 Dark ✓" else "🌙 Dark") }
                    OutlinedButton({ AppTheme.dark = false; AppTheme.save(sp) }) { Text(if (!AppTheme.dark) "☀️ Light ✓" else "☀️ Light") }
                }
                Text("Color")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTheme.accents.forEachIndexed { i, a ->
                        val c = Color(if (AppTheme.dark) a.second else a.third)
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(c)
                                .then(if (i == AppTheme.accent) Modifier.border(3.dp, Color.Gray, CircleShape) else Modifier)
                                .clickable { AppTheme.accent = i; AppTheme.save(sp) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Done") } }
    )
}
