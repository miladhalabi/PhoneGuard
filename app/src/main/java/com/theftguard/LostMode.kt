package com.theftguard

import android.app.KeyguardManager
import android.app.Notification
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
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton

/**
 * "This phone is missing." Entered by commands like LOCK or by a new-SIM alert; left only when the
 * owner turns it off in the app or texts STOP. While on, it shows the lost screen and watches the battery.
 */
object LostMode {
    private const val CHANNEL = "lost"
    private const val NOTIFICATION_ID = 3

    fun isOn(c: Context) = Prefs.lostSince(c) > 0

    fun enter(c: Context, commander: String?) {
        if (commander != null) Prefs.setLastCommander(c, commander)
        if (!isOn(c)) {
            Prefs.setLostSince(c, System.currentTimeMillis())
            Prefs.setLowBatterySent(c, false)
        }
        LowBatteryJob.schedule(c)
    }

    fun exit(c: Context) {
        Prefs.setLostSince(c, 0)
        LowBatteryJob.cancel(c)
        Tracker.stop(c)
        c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** Trusted numbers plus whoever sent the last command, without duplicates. */
    fun recipients(c: Context): List<String> {
        val all = Prefs.trustedList(c) + listOfNotNull(Prefs.lastCommander(c))
        return all.fold(mutableListOf()) { acc, n -> acc.apply { if (none { Prefs.sameNumber(it, n) }) add(n) } }
    }

    /**
     * A full-screen notification is the one way Android 10+ lets a background app put a screen
     * over the lock screen.
     */
    fun showScreen(c: Context) {
        if (!Prefs.lostScreen(c)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.lost_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, LostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(c.getString(R.string.lost_title))
            .setContentText(Prefs.lostMessage(c))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications not allowed; nothing else can draw over the lock screen.
        }
    }
}

/** Shown over the lock screen so an honest finder knows who to call. */
class LostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContentView(R.layout.activity_lost)

        findViewById<TextView>(R.id.lostMessage).text = Prefs.lostMessage(this)
        val contact = Prefs.lostContact(this)
        findViewById<TextView>(R.id.lostContact).apply {
            text = contact
            isVisible = contact.isNotEmpty()
        }
        findViewById<MaterialButton>(R.id.btnOwner).setOnClickListener { openAsOwner() }
    }

    /** Asks for the screen lock first, so only the owner gets past this screen into the app. */
    private fun openAsOwner() {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                startActivity(Intent(this@LostActivity, MainActivity::class.java))
                finish()
            }
        })
    }
}

/** While lost, checks the battery every 15 minutes and texts one last location before it dies. */
class LowBatteryJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        if (!LostMode.isOn(this) || !Prefs.lowBatteryAlert(this)) {
            cancel(this)
            return false
        }
        val bm = getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (bm.isCharging || level > THRESHOLD || Prefs.lowBatterySent(this)) return false

        Prefs.setLowBatterySent(this, true)
        val header = "Phone Guard: battery at $level%, it may switch off soon."
        return Responder.locateAndReply(
            this, LostMode.recipients(this), SubscriptionManager.INVALID_SUBSCRIPTION_ID, header,
            keepAlive = { { jobFinished(params, false) } },
        )
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val JOB_ID = 42
        private const val THRESHOLD = 10
        private const val PERIOD_MS = 15 * 60 * 1000L

        fun schedule(c: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, LowBatteryJob::class.java))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
            c.getSystemService(JobScheduler::class.java).schedule(job)
        }

        fun cancel(c: Context) {
            c.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }
}
