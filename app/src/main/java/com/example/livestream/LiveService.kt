package com.example.livestream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

// One valid notification (with its channel) used by every foreground service of the live.
fun liveNotification(ctx: Context, title: String = "You are live"): Notification {
    if (Build.VERSION.SDK_INT >= 26) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("live", "Live", NotificationManager.IMPORTANCE_LOW))
    }
    val open = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    return NotificationCompat.Builder(ctx, "live")
        .setContentTitle(title)
        .setContentText("Tap to return to your live")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentIntent(open)
        .setOngoing(true)
        .build()
}

// Keeps the live (microphone, and the screen share) running while the host is in another app.
class LiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val share = intent?.getBooleanExtra("share", false) == true
        val n = liveNotification(this, if (share) "Sharing your screen" else "You are live")
        if (Build.VERSION.SDK_INT >= 30) {
            val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try {
                // While sharing the screen, Android 14 needs a mediaProjection service running BEFORE the capture starts.
                startForeground(1, n, if (share) mic or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else mic)
            } catch (e: Exception) {
                startForeground(1, n, mic)
            }
        } else startForeground(1, n)
        return START_NOT_STICKY
    }
}
