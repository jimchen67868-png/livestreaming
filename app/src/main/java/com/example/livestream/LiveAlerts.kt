package com.example.livestream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf

// Every ~15 minutes (Android's minimum for background jobs) the phone asks the server who is live
// and shows a notification when someone you follow has gone live.
class FollowLiveJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            try { LiveAlerts.check(applicationContext) } catch (e: Exception) {}
            try { jobFinished(p, false) } catch (e: Exception) {}
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean = true
}

object LiveAlerts {
    private const val JOB_ID = 4711

    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences("s", 0).getBoolean("livealerts", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("s", 0).edit().putBoolean("livealerts", on).apply()
        if (on) schedule(ctx) else try { ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID) } catch (e: Exception) {}
    }

    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        try {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, FollowLiveJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        } catch (e: Exception) {}
    }

    private fun log(ctx: Context, line: String) {
        ctx.getSharedPreferences("diag", 0).edit().putString("alerts", java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + line).apply()
    }

    fun scheduled(ctx: Context): Boolean = try { ctx.getSystemService(JobScheduler::class.java).allPendingJobs.any { it.id == JOB_ID } } catch (e: Exception) { false }

    // force = used by "Check alerts now" in the diagnostics: shows the alert even if the app is open or it was shown before
    fun check(ctx: Context, force: Boolean = false) {
        if (!enabled(ctx)) { log(ctx, "alerts are off"); return }
        val sp = ctx.getSharedPreferences("s", 0)
        val follows = Follows.list(ctx).toSet() // already lower case
        if (follows.isEmpty()) { log(ctx, "you follow nobody on this phone"); return }
        val server = if (DEFAULT_SERVER.isNotBlank()) DEFAULT_SERVER else (sp.getString("server", "") ?: "")
        if (server.isBlank()) { log(ctx, "no server"); return }
        val live = try { Api.streams(server) } catch (e: Exception) { log(ctx, "can't reach the server"); return }
        val seen = sp.getStringSet("alerted", emptySet()) ?: emptySet()
        val liveIds = live.map { it.id }.toSet()
        val mine = live.filter { it.host.trim().lowercase() in follows }
        val fresh = if (force) mine else mine.filter { it.id !in seen }
        val canShow = notifAllowed(ctx)
        // a live is only remembered as "alerted" once the notification could really be shown
        val remembered = if (canShow) fresh.map { it.id } else emptyList()
        // forget rooms that ended, so the same host going live again later notifies again
        sp.edit().putStringSet("alerted", (seen.intersect(liveIds) + remembered).toSet()).apply()
        log(ctx, "follows=" + follows.size + " live=" + live.size + " followed live=" + mine.size + " new=" + fresh.size +
            (if (!canShow) " NOT SHOWN: notifications are not allowed for this app" else if (appVisible && !force) " (app is open: no pop-up)" else ""))
        if ((appVisible && !force) || !canShow || fresh.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("followlive", "Followed hosts go live", NotificationManager.IMPORTANCE_HIGH))
        fresh.forEach { r ->
            // tapping the notification goes straight into this live
            val open = PendingIntent.getActivity(ctx, 2000 + (r.id.hashCode() and 0xffff),
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("open_room", r.id).putExtra("open_title", r.title).putExtra("open_host", r.host)
                    .putExtra("open_kind", r.kind).putExtra("open_ratio", r.ratio),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, "followlive")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(r.host + " is live")
                .setContentText(if (r.title.isBlank()) "Tap to watch" else r.title)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(1000 + (r.id.hashCode() and 0xffff), n)
        }
    }
}

// A tapped "is live" notification hands the stream over to the app through this, and the app opens it.
object OpenRoom {
    val pending = mutableStateOf<Api.Stream?>(null)

    fun fromIntent(i: Intent?) {
        if (i == null) return
        val id = i.getStringExtra("open_room") ?: return
        i.removeExtra("open_room")
        pending.value = Api.Stream(id, i.getStringExtra("open_title") ?: "", i.getStringExtra("open_host") ?: "", 0,
            kind = i.getStringExtra("open_kind") ?: "", ratio = i.getStringExtra("open_ratio") ?: "")
    }
}
