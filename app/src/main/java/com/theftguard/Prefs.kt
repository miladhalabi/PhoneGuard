package com.theftguard

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Settings storage. The PIN is never stored in clear text, only a salted SHA-256 hash. */
object Prefs {
    private const val FILE = "guard"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_TRUSTED = "trusted"
    private const val KEY_ANY = "any_number"
    private const val KEY_SIM_ALERT = "sim_alert"
    private const val KEY_UNLOCK_ALERT = "unlock_alert"
    private const val KEY_KNOWN_SIMS = "known_sims"
    private const val KEY_LAST_UNLOCK_ALERT = "last_unlock_alert"
    private const val KEY_LOW_BATTERY_ALERT = "low_battery_alert"
    private const val KEY_LOW_BATTERY_SENT = "low_battery_sent"
    private const val KEY_LOST_SINCE = "lost_since"
    private const val KEY_LAST_COMMANDER = "last_commander"
    private const val KEY_LOST_MESSAGE = "lost_message"
    private const val KEY_LOST_SCREEN = "lost_screen"
    private const val KEY_LOCK_REPLY = "lock_reply"
    private const val KEY_LOST_CONTACT = "lost_contact"
    private const val KEY_TRACK_TO = "track_to"
    private const val KEY_TRACK_SUB = "track_sub"
    private const val KEY_TRACK_LEFT = "track_left"
    private const val KEY_FAILS = "fails"
    private const val KEY_LOCKED_UNTIL = "locked_until"

    const val DEFAULT_LOST_MESSAGE = "This phone is lost. Please call the owner."

    private const val MAX_FAILS = 5
    private const val LOCKOUT_MS = 30 * 60 * 1000L

    // Last 7 digits are enough to match the same number written as 09xx…, +963 9xx…, 00963 9xx…
    private const val MATCH_DIGITS = 7
    private const val MIN_DIGITS = 6

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasPin(c: Context) = sp(c).contains(KEY_HASH)
    fun setPin(c: Context, pin: String) = storeSecret(c, KEY_SALT, KEY_HASH, pin)
    fun checkPin(c: Context, pin: String) = checkSecret(c, KEY_SALT, KEY_HASH, pin)

    private fun storeSecret(c: Context, saltKey: String, hashKey: String, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp(c).edit()
            .putString(saltKey, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(hashKey, Base64.encodeToString(hash(salt, pin), Base64.NO_WRAP))
            .apply()
    }

    private fun checkSecret(c: Context, saltKey: String, hashKey: String, pin: String): Boolean {
        val p = sp(c)
        val salt = p.getString(saltKey, null) ?: return false
        val stored = p.getString(hashKey, null) ?: return false
        return MessageDigest.isEqual(
            Base64.decode(stored, Base64.NO_WRAP),
            hash(Base64.decode(salt, Base64.NO_WRAP), pin),
        )
    }

    private fun hash(salt: ByteArray, pin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(pin.toByteArray())
        }

    enum class AddResult { ADDED, INVALID, DUPLICATE }

    /** Numbers as the user entered them. Older versions stored them comma-separated, so split on both. */
    fun trustedList(c: Context): List<String> =
        (sp(c).getString(KEY_TRUSTED, "") ?: "")
            .split(',', '،', ';', '\n')
            .map { it.trim() }
            .filter { Text.digitsOnly(it).isNotEmpty() }

    private fun saveTrusted(c: Context, list: List<String>) {
        sp(c).edit().putString(KEY_TRUSTED, list.joinToString("\n")).apply()
    }

    fun addTrusted(c: Context, raw: String): AddResult {
        val number = Text.normalizeDigits(raw).trim()
        if (Text.digitsOnly(number).length < MIN_DIGITS) return AddResult.INVALID
        val list = trustedList(c)
        if (list.any { sameNumber(it, number) }) return AddResult.DUPLICATE
        saveTrusted(c, list + number)
        return AddResult.ADDED
    }

    fun removeTrusted(c: Context, number: String) {
        saveTrusted(c, trustedList(c).filterNot { it == number })
    }

    /** "Any number" mode: the PIN alone authorizes a command. Defaults on until a number is added. */
    fun anyNumber(c: Context): Boolean = sp(c).getBoolean(KEY_ANY, trustedList(c).isEmpty())

    fun setAnyNumber(c: Context, any: Boolean) {
        sp(c).edit().putBoolean(KEY_ANY, any).apply()
    }

    fun isTrusted(c: Context, sender: String): Boolean =
        anyNumber(c) || trustedList(c).any { sameNumber(it, sender) }

    private fun matchKey(n: String) = Text.digitsOnly(n).takeLast(MATCH_DIGITS)

    fun sameNumber(a: String, b: String): Boolean {
        val k = matchKey(a)
        return k.isNotEmpty() && k == matchKey(b)
    }

    fun simAlert(c: Context) = sp(c).getBoolean(KEY_SIM_ALERT, true)
    fun setSimAlert(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_SIM_ALERT, on).apply()

    fun unlockAlert(c: Context) = sp(c).getBoolean(KEY_UNLOCK_ALERT, true)
    fun setUnlockAlert(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_UNLOCK_ALERT, on).apply()

    /** Null until the first look at the SIMs, so the owner's own SIMs never trigger an alert. */
    fun knownSims(c: Context): Set<String>? = sp(c).getStringSet(KEY_KNOWN_SIMS, null)
    fun setKnownSims(c: Context, ids: Set<String>) = sp(c).edit().putStringSet(KEY_KNOWN_SIMS, ids).apply()

    /** True at most once per cooldown, so repeated wrong attempts don't flood the trusted numbers. */
    fun claimUnlockAlert(c: Context, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - sp(c).getLong(KEY_LAST_UNLOCK_ALERT, 0) < cooldownMs) return false
        sp(c).edit().putLong(KEY_LAST_UNLOCK_ALERT, now).apply()
        return true
    }

    fun lowBatteryAlert(c: Context) = sp(c).getBoolean(KEY_LOW_BATTERY_ALERT, true)
    fun setLowBatteryAlert(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_LOW_BATTERY_ALERT, on).apply()

    fun lowBatterySent(c: Context) = sp(c).getBoolean(KEY_LOW_BATTERY_SENT, false)
    fun setLowBatterySent(c: Context, sent: Boolean) = sp(c).edit().putBoolean(KEY_LOW_BATTERY_SENT, sent).apply()

    enum class LockReply { NONE, LOCKED, LOCATION }

    /** What LOCK texts back. A failed lock is always reported, whatever this says. */
    fun lockReply(c: Context): LockReply =
        runCatching { LockReply.valueOf(sp(c).getString(KEY_LOCK_REPLY, null) ?: "") }.getOrDefault(LockReply.LOCATION)
    fun setLockReply(c: Context, r: LockReply) = sp(c).edit().putString(KEY_LOCK_REPLY, r.name).apply()

    fun lostScreen(c: Context) = sp(c).getBoolean(KEY_LOST_SCREEN, true)
    fun setLostScreen(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_LOST_SCREEN, on).apply()

    /** 0 when the phone isn't in lost mode, otherwise when it started (epoch millis). */
    fun lostSince(c: Context) = sp(c).getLong(KEY_LOST_SINCE, 0)
    fun setLostSince(c: Context, at: Long) = sp(c).edit().putLong(KEY_LOST_SINCE, at).apply()

    /** Whoever sent the last valid command, so lost-mode alerts reach them even if they're not trusted. */
    fun lastCommander(c: Context): String? = sp(c).getString(KEY_LAST_COMMANDER, null)
    fun setLastCommander(c: Context, number: String) = sp(c).edit().putString(KEY_LAST_COMMANDER, number).apply()

    fun lostMessage(c: Context): String = sp(c).getString(KEY_LOST_MESSAGE, null) ?: DEFAULT_LOST_MESSAGE
    fun setLostMessage(c: Context, text: String) = sp(c).edit().putString(KEY_LOST_MESSAGE, text.trim()).apply()

    /** Number shown on the lost screen. Falls back to the first trusted number. */
    fun lostContact(c: Context): String =
        sp(c).getString(KEY_LOST_CONTACT, null)?.takeIf { it.isNotBlank() } ?: trustedList(c).firstOrNull().orEmpty()
    fun lostContactRaw(c: Context): String = sp(c).getString(KEY_LOST_CONTACT, null).orEmpty()
    fun setLostContact(c: Context, number: String) = sp(c).edit().putString(KEY_LOST_CONTACT, number.trim()).apply()

    fun trackTo(c: Context): String? = sp(c).getString(KEY_TRACK_TO, null)
    fun trackSub(c: Context) = sp(c).getInt(KEY_TRACK_SUB, -1)
    fun trackLeft(c: Context) = sp(c).getInt(KEY_TRACK_LEFT, 0)
    fun setTrack(c: Context, to: String, subId: Int, left: Int) =
        sp(c).edit().putString(KEY_TRACK_TO, to).putInt(KEY_TRACK_SUB, subId).putInt(KEY_TRACK_LEFT, left).apply()
    fun setTrackLeft(c: Context, left: Int) = sp(c).edit().putInt(KEY_TRACK_LEFT, left).apply()

    fun isLockedOut(c: Context) = System.currentTimeMillis() < sp(c).getLong(KEY_LOCKED_UNTIL, 0)

    fun registerFailure(c: Context) {
        val p = sp(c)
        val fails = p.getInt(KEY_FAILS, 0) + 1
        if (fails >= MAX_FAILS) {
            p.edit()
                .putInt(KEY_FAILS, 0)
                .putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + LOCKOUT_MS)
                .apply()
        } else {
            p.edit().putInt(KEY_FAILS, fails).apply()
        }
    }

    fun clearFailures(c: Context) {
        sp(c).edit().putInt(KEY_FAILS, 0).apply()
    }
}
