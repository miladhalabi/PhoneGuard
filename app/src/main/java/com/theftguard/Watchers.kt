package com.theftguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * Notices a SIM this phone has never seen before and texts the trusted numbers *from that SIM*,
 * so the alert reveals the new number, followed by the location.
 */
object SimWatcher {
    private const val TAG = "SimWatcher"

    // A freshly inserted SIM needs a moment to register on the network before it can send.
    private const val NETWORK_DELAY_MS = 30_000L

    @SuppressLint("MissingPermission")
    private fun activeSims(c: Context): List<SubscriptionInfo>? {
        if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            c.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            null
        }
    }

    /** Called while the owner is using the app: whatever SIMs are in now are theirs. */
    fun remember(c: Context) {
        val sims = activeSims(c) ?: return
        Prefs.setKnownSims(c, (Prefs.knownSims(c) ?: emptySet()) + sims.map { it.subscriptionId.toString() })
    }

    fun check(c: Context, keepAlive: KeepAlive) {
        val sims = activeSims(c) ?: return
        val current = sims.map { it.subscriptionId.toString() }.toSet()
        val known = Prefs.knownSims(c)
        // Android keeps one subscription id per physical SIM, so re-inserting your own SIM is not "new".
        Prefs.setKnownSims(c, (known ?: emptySet()) + current)
        if (known == null) return

        val fresh = sims.filter { it.subscriptionId.toString() !in known }
        if (fresh.isEmpty() || !Prefs.simAlert(c)) return
        val to = Prefs.trustedList(c)
        if (to.isEmpty()) return

        LostMode.enter(c, null)
        for (sim in fresh) {
            val carrier = sim.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: sim.displayName ?: "unknown"
            Log.i(TAG, "New SIM: $carrier")
            val header = "Phone Guard: a new SIM ($carrier) was put in this phone. This text came from it, so the sender is its number."
            Responder.locateAndReply(c, to, sim.subscriptionId, header, keepAlive, NETWORK_DELAY_MS)
        }
    }
}

/** After a few wrong PIN/pattern attempts on the lock screen, texts the trusted numbers the location. */
object UnlockWatcher {
    private const val THRESHOLD = 3
    private const val COOLDOWN_MS = 10 * 60 * 1000L

    fun onFailed(c: Context, keepAlive: KeepAlive) {
        if (!Prefs.unlockAlert(c)) return
        val attempts = try {
            c.getSystemService(DevicePolicyManager::class.java).currentFailedPasswordAttempts
        } catch (e: SecurityException) {
            return
        }
        if (attempts < THRESHOLD) return
        val to = Prefs.trustedList(c)
        if (to.isEmpty()) return
        if (!Prefs.claimUnlockAlert(c, COOLDOWN_MS)) return

        val header = "Phone Guard: $attempts wrong unlock attempts on this phone."
        Responder.locateAndReply(c, to, SubscriptionManager.INVALID_SUBSCRIPTION_ID, header, keepAlive)
    }
}

/**
 * SIM changes arrive as carrier-config updates; a SIM swapped while the phone was off shows up at boot.
 * Boot is also when tracking alarms, which don't survive a restart, are set again.
 */
class SimReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> SimWatcher.check(context, keepAlive())
            Intent.ACTION_BOOT_COMPLETED -> {
                Tracker.resume(context)
                SimWatcher.check(context, keepAlive())
            }
        }
    }
}
