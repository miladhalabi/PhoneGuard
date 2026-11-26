package com.theftguard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Keeps the caller alive past its normal time budget; returns the function that releases it.
 * Receivers use goAsync(), jobs use jobFinished().
 */
typealias KeepAlive = () -> (() -> Unit)

fun BroadcastReceiver.keepAlive(): KeepAlive = {
    val pending = goAsync()
    ({ pending.finish() })
}

/** Texts `header` plus the phone's location to each recipient. Shared by commands and alerts. */
object Responder {
    private const val FALLBACK_TIMEOUT_MS = 8_000L

    /** Returns true if it is still working and will release [keepAlive] itself. */
    fun locateAndReply(
        context: Context,
        to: List<String>,
        subId: Int,
        header: String?,
        keepAlive: KeepAlive,
        minDelayMs: Long = 0,
    ): Boolean {
        if (to.isEmpty()) return false
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val text = listOfNotNull(header, "Location permission is not granted").joinToString("\n")
            to.forEach { SmsSender.send(context, it, subId, text) }
            return false
        }

        // Without "allow all the time", Android 10+ won't let a background service use location.
        val hasBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasBackground && CommandService.start(context, to, subId, header, minDelayMs)) return false

        // Fallback: use what we can get within the caller's short time budget.
        val note = if (hasBackground) null else "Tip: allow location all the time for better results"
        val release = keepAlive()
        Locator(context.applicationContext) { result ->
            val text = listOfNotNull(header, Locator.describe(context, result), note).joinToString("\n")
            to.forEach { SmsSender.send(context, it, subId, text) }
            release()
        }.start(FALLBACK_TIMEOUT_MS)
        return true
    }
}
