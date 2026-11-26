package com.theftguard

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

object SmsSender {
    private const val TAG = "SmsSender"

    /** Replies on the same SIM the command arrived on (matters on dual-SIM phones). */
    fun send(c: Context, to: String, subId: Int, text: String) {
        try {
            val sm = manager(c, subId)
            val parts = sm.divideMessage(text)
            if (parts.size > 1) {
                sm.sendMultipartTextMessage(to, null, parts, null, null)
            } else {
                sm.sendTextMessage(to, null, text, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
        }
    }

    private fun manager(c: Context, subId: Int): SmsManager {
        val hasSub = subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = c.getSystemService(SmsManager::class.java)
            return if (hasSub) base.createForSubscriptionId(subId) else base
        }
        @Suppress("DEPRECATION")
        return if (hasSub) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
    }
}
