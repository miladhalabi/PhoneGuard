package com.theftguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DeviceAdminInfo
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.UserHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        UnlockWatcher.onFailed(context, keepAlive())
    }
}

object Device {
    fun adminComponent(c: Context) = ComponentName(c, AdminReceiver::class.java)

    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)

    fun isAdminActive(c: Context): Boolean = dpm(c).isAdminActive(adminComponent(c))

    /** Admins activated before wrong-unlock alerts existed lack this policy until re-enabled. */
    fun canWatchUnlocks(c: Context): Boolean =
        isAdminActive(c) && dpm(c).hasGrantedPolicy(adminComponent(c), DeviceAdminInfo.USES_POLICY_WATCH_LOGIN)

    /** Locks the screen. Only useful if a PIN/pattern/password screen lock is set. */
    fun lock(c: Context): Boolean {
        if (!isAdminActive(c)) return false
        return try {
            dpm(c).lockNow()
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Calls `number` without showing a dialer first; the owner hears what's around the phone. */
    @SuppressLint("MissingPermission")
    fun call(c: Context, number: String): Boolean {
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            c.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
            true
        } catch (e: Exception) {
            false
        }
    }

    fun batteryPercent(c: Context): Int =
        c.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /** The INFO reply: kept short and ASCII so it fits one SMS. */
    @SuppressLint("MissingPermission")
    fun info(c: Context): String = buildString {
        val bm = c.getSystemService(BatteryManager::class.java)
        append("Phone Guard info\nBattery: ${batteryPercent(c)}%")
        if (bm.isCharging) append(" (charging)")

        val sims = if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            runCatching { c.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList }.getOrNull()
        } else {
            null
        }
        append("\nSIM: ")
        append(sims?.takeIf { it.isNotEmpty() }?.joinToString { it.carrierName ?: it.displayName ?: "?" } ?: "none")

        append("\nNetwork: ${network(c)}")
        val since = Prefs.lostSince(c)
        append("\nLost mode: ")
        append(if (since > 0) "on since " + SimpleDateFormat("HH:mm", Locale.US).format(Date(since)) else "off")
        if (Tracker.isOn(c)) append("\nTracking: ${Prefs.trackLeft(c)} updates left")
    }

    private fun network(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                @Suppress("DEPRECATION")
                val ssid = c.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid
                    ?.removeSurrounding("\"")
                if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "Wi-Fi" else "Wi-Fi $ssid"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "connected"
        }
    }
}
