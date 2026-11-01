package com.example.livestream

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

// A small chat window that floats over other apps while the host is away (for example while sharing the screen).
// Plain Android views, so it works outside the app's own screen. Needs the "display over other apps" permission.
object FloatChat {
    private val main = Handler(Looper.getMainLooper())
    private val lines = mutableListOf<String>()
    private var root: LinearLayout? = null
    private var body: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var dismissed = false

    fun canShow(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun askPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {}
    }

    fun add(ctx: Context, line: String) {
        val app = ctx.applicationContext
        main.post {
            synchronized(lines) { lines.add(line); while (lines.size > 6) lines.removeAt(0) }
            if (dismissed) return@post
            try {
                if (root == null) build(app)
                body?.text = synchronized(lines) { lines.joinToString("\n") }
            } catch (e: Exception) {}
        }
    }

    // Called when the host comes back to the app.
    fun hide(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            dismissed = false
            synchronized(lines) { lines.clear() }
            try {
                root?.let { app.getSystemService(Context.WINDOW_SERVICE).let { wm -> (wm as WindowManager).removeView(it) } }
            } catch (e: Exception) {}
            root = null; body = null; params = null
        }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun build(app: Context) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            dp(app, 260), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(app, 12); lp.y = dp(app, 120)

        val box = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(dp(app, 10), dp(app, 6), dp(app, 10), dp(app, 8))
        }
        val header = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(app).apply {
            text = "Live chat  (drag)"; setTextColor(Color.LTGRAY); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val open = TextView(app).apply {
            text = "Open"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(app, 10), dp(app, 4), dp(app, 10), dp(app, 4))
            setOnClickListener {
                try {
                    app.startActivity(
                        Intent(app, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                } catch (e: Exception) {}
            }
        }
        val close = TextView(app).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 14f
            setPadding(dp(app, 10), dp(app, 4), dp(app, 4), dp(app, 4))
            setOnClickListener {
                // hide until the host returns to the app
                dismissed = true
                try { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(box) } catch (e: Exception) {}
                root = null; body = null; params = null
            }
        }
        header.addView(title); header.addView(open); header.addView(close)

        val text = TextView(app).apply { setTextColor(Color.WHITE); textSize = 14f; maxLines = 8 }
        box.addView(header); box.addView(text)

        // drag by the title
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        title.setOnTouchListener { _: View, ev: MotionEvent ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = ev.rawX; ty = ev.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (ev.rawX - tx).toInt(); lp.y = sy + (ev.rawY - ty).toInt()
                    try { wm.updateViewLayout(box, lp) } catch (e: Exception) {}
                    true
                }
                else -> true
            }
        }

        wm.addView(box, lp)
        root = box; body = text; params = lp
    }
}
