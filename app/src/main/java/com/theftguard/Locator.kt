package com.theftguard

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Gets the best location fix available within a time limit, using GPS and network together.
 * Stops early once a fix is accurate enough; falls back to the last known location otherwise.
 */
class Locator(context: Context, private val onDone: (Result) -> Unit) {

    data class Result(val location: Location?, val fresh: Boolean, val servicesOff: Boolean)

    private val lm = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var best: Location? = null
    private var servicesOff = false
    private var done = false

    // All four methods are implemented: before API 30 they have no default implementation.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (isBetter(location, best)) best = location
            if (location.hasAccuracy() && location.accuracy <= GOOD_ACCURACY_M) finish()
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start(timeoutMs: Long) {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in lm.allProviders && lm.isProviderEnabled(it) }
        servicesOff = providers.isEmpty()
        if (servicesOff) {
            finish()
            return
        }
        try {
            providers.forEach { lm.requestLocationUpdates(it, 1000L, 0f, listener, Looper.getMainLooper()) }
        } catch (e: SecurityException) {
            finish()
            return
        }
        handler.postDelayed({ finish() }, timeoutMs)
    }

    private fun finish() {
        if (done) return
        done = true
        lm.removeUpdates(listener)
        handler.removeCallbacksAndMessages(null)
        val fix = best
        onDone(if (fix != null) Result(fix, true, servicesOff) else Result(lastKnown(), false, servicesOff))
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? =
        lm.allProviders
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }

    private fun isBetter(a: Location, b: Location?): Boolean =
        b == null || (a.hasAccuracy() && (!b.hasAccuracy() || a.accuracy < b.accuracy))

    companion object {
        private const val GOOD_ACCURACY_M = 25f

        /** The SMS reply text. Plain ASCII keeps it to a single SMS; Locale.US keeps the map link valid. */
        fun describe(c: Context, r: Result): String = buildString {
            val loc = r.location
            if (loc == null) {
                append(if (r.servicesOff) "Location is off and no earlier fix exists" else "Could not get a location")
            } else {
                if (!r.fresh) {
                    val ageMin = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 60_000_000_000L
                    append("Last known location ($ageMin min ago)\n")
                }
                append(String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", loc.latitude, loc.longitude))
                if (loc.hasAccuracy()) append("\nAccuracy: ${loc.accuracy.roundToInt()} m")
                if (r.servicesOff) append("\nLocation services are off")
            }
            append("\nBattery: ${Device.batteryPercent(c)}%")
        }
    }
}
