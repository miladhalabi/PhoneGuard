package com.theftguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/** TRACK: a location text every few minutes for a limited time, so a moving phone can be followed. */
object Tracker {
    const val INTERVAL_MIN = 10
    const val UPDATES = 12
    private const val INTERVAL_MS = INTERVAL_MIN * 60 * 1000L

    fun start(c: Context, to: String, subId: Int) {
        Prefs.setTrack(c, to, subId, UPDATES)
        scheduleNext(c)
    }

    fun stop(c: Context) {
        Prefs.setTrackLeft(c, 0)
        c.getSystemService(AlarmManager::class.java).cancel(pendingIntent(c))
    }

    fun isOn(c: Context) = Prefs.trackLeft(c) > 0

    /** Alarms don't survive a reboot; called at boot to pick up where tracking left off. */
    fun resume(c: Context) {
        if (isOn(c)) scheduleNext(c)
    }

    fun scheduleNext(c: Context) {
        // Inexact but allowed in Doze; Android spaces these at least ~9 minutes apart, which suits a 10 minute interval.
        c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL_MS, pendingIntent(c)
        )
    }

    private fun pendingIntent(c: Context) = PendingIntent.getBroadcast(
        c, 0, Intent(c, TrackReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

class TrackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val left = Prefs.trackLeft(context)
        val to = Prefs.trackTo(context)
        if (left <= 0 || to == null) return
        Prefs.setTrackLeft(context, left - 1)
        if (left > 1) Tracker.scheduleNext(context)

        val n = Tracker.UPDATES - left + 1
        val header = "Phone Guard tracking $n/${Tracker.UPDATES}"
        Responder.locateAndReply(context, listOf(to), Prefs.trackSub(context), header, keepAlive())
    }
}
