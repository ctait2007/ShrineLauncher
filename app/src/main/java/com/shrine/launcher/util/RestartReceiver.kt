package com.shrine.launcher.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Fired by AlarmManager after a self-update to relaunch the launcher in a fresh process. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private const val RESTART_CODE = 9001

private fun restartIntent(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
        context, RESTART_CODE,
        Intent(context, RestartReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

/**
 * Schedule a launcher restart [delayMs] milliseconds from now.
 * Call this BEFORE `pm install -r` — the install kills our process before
 * we can set any alarm afterwards.
 */
fun scheduleRestart(context: Context, delayMs: Long) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val triggerAt = System.currentTimeMillis() + delayMs
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, restartIntent(context))
    } else {
        am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, restartIntent(context))
    }
}

/** Cancel a previously scheduled restart (e.g. when install failed). */
fun cancelRestart(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    am.cancel(restartIntent(context))
}
