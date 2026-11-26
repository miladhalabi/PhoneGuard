package com.theftguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val sender = messages[0].originatingAddress ?: return
        // Long messages arrive split in several parts.
        val body = messages.joinToString("") { it.messageBody ?: "" }

        val (command, pin) = Commands.parse(body) ?: return
        if (!Prefs.hasPin(context)) return
        if (!Prefs.isTrusted(context, sender)) return
        if (Prefs.isLockedOut(context)) return
        // Wrong PINs get no reply, so a guesser learns nothing.
        if (!Prefs.checkPin(context, pin)) {
            Prefs.registerFailure(context)
            return
        }
        Prefs.clearFailures(context)

        val subId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID),
        )
        fun reply(text: String) = SmsSender.send(context, sender, subId, text)

        val header = when (command) {
            Command.LOCATE -> null
            Command.LOCK -> {
                LostMode.enter(context, sender)
                val locked = Device.lock(context)
                LostMode.showScreen(context)
                if (!locked) {
                    reply("Phone Guard: $LOCK_FAILED")
                    return
                }
                when (Prefs.lockReply(context)) {
                    Prefs.LockReply.NONE -> return
                    Prefs.LockReply.LOCKED -> {
                        reply("Phone Guard: screen locked")
                        return
                    }
                    Prefs.LockReply.LOCATION -> "Phone Guard: screen locked"
                }
            }
            Command.ALARM -> {
                LostMode.enter(context, sender)
                // Lock first, so only the owner can stop it by unlocking.
                val locked = Device.lock(context)
                LostMode.showScreen(context)
                when {
                    !AlarmService.start(context) -> "Phone Guard: alarm could not start"
                    locked -> "Phone Guard: alarm ringing for ${AlarmService.DURATION_MS / 60_000} min. Unlock the phone or text STOP and your PIN to end it."
                    else -> "Phone Guard: alarm ringing, but $LOCK_FAILED"
                }
            }
            Command.TRACK -> {
                LostMode.enter(context, sender)
                Tracker.start(context, sender, subId)
                "Phone Guard: tracking on, a location every ${Tracker.INTERVAL_MIN} min, ${Tracker.UPDATES} times. STOP and your PIN ends it."
            }
            Command.CALL -> {
                LostMode.enter(context, sender)
                if (!Device.call(context, sender)) reply("Phone Guard: could not call, phone permission is off")
                return
            }
            Command.INFO -> {
                reply(Device.info(context))
                return
            }
            Command.STOP -> {
                AlarmService.stop(context)
                LostMode.exit(context)
                reply("Phone Guard: alarm, tracking and lost mode stopped")
                return
            }
        }

        Responder.locateAndReply(context, listOf(sender), subId, header, keepAlive())
    }

    companion object {
        // Replies never repeat the PIN: Android copies sent texts into this phone's Sent folder.
        private const val LOCK_FAILED = "lock failed, lock permission is off"
    }
}
