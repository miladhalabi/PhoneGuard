package com.theftguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * Short-lived foreground service that keeps the app alive while waiting for a GPS fix
 * (a broadcast receiver alone only gets ~10 seconds), then texts the location back.
 */
class CommandService : Service() {

    private data class Request(val to: String, val subId: Int, val header: String?)

    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Request>()
    private var locator: Locator? = null
    private var notBefore = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the system refuses foreground mode we only have a few seconds before being stopped.
        val timeout = try {
            goForeground()
            TIMEOUT_MS
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            SHORT_TIMEOUT_MS
        }
        val recipients = intent?.getStringArrayExtra(EXTRA_TO)
        if (recipients.isNullOrEmpty()) {
            if (locator == null) stopSelf()
            return START_NOT_STICKY
        }
        val subId = intent.getIntExtra(EXTRA_SUB, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val header = intent.getStringExtra(EXTRA_HEADER)
        recipients.forEach { pending += Request(it, subId, header) }
        notBefore = maxOf(notBefore, SystemClock.elapsedRealtime() + intent.getLongExtra(EXTRA_MIN_DELAY, 0))

        // Requests that arrive while a fix is in progress share its result.
        if (locator == null) {
            locator = Locator(this, ::onLocated).also { it.start(timeout) }
        }
        return START_NOT_STICKY
    }

    private fun onLocated(result: Locator.Result) {
        val wait = notBefore - SystemClock.elapsedRealtime()
        if (wait > 0) {
            handler.postDelayed({ onLocated(result) }, wait)
            return
        }
        val body = Locator.describe(this, result)
        pending.forEach { SmsSender.send(this, it.to, it.subId, listOfNotNull(it.header, body).joinToString("\n")) }
        pending.clear()
        locator = null
        notBefore = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_title))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "CommandService"
        private const val CHANNEL = "guard"
        private const val NOTIFICATION_ID = 1
        private const val TIMEOUT_MS = 60_000L
        private const val SHORT_TIMEOUT_MS = 3_000L
        private const val EXTRA_TO = "to"
        private const val EXTRA_SUB = "sub"
        private const val EXTRA_HEADER = "header"
        private const val EXTRA_MIN_DELAY = "min_delay"

        /** Returns false if the system refused to start the service. */
        fun start(c: Context, to: List<String>, subId: Int, header: String?, minDelayMs: Long): Boolean {
            val intent = Intent(c, CommandService::class.java)
                .putExtra(EXTRA_TO, to.toTypedArray())
                .putExtra(EXTRA_SUB, subId)
                .putExtra(EXTRA_HEADER, header)
                .putExtra(EXTRA_MIN_DELAY, minDelayMs)
            return try {
                c.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service", e)
                false
            }
        }
    }
}
