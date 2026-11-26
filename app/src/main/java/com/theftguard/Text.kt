package com.theftguard

object Text {
    /** Converts Arabic-Indic (٠-٩) and Persian (۰-۹) digits to ASCII, so "قفل ١٢٣٤" works too. */
    fun normalizeDigits(s: String): String = buildString(s.length) {
        for (ch in s) {
            append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    else -> ch
                }
            )
        }
    }

    fun digitsOnly(s: String): String = normalizeDigits(s).filter { it in '0'..'9' }
}

enum class Command { LOCATE, LOCK, ALARM, STOP, TRACK, CALL, INFO }

object Commands {
    private val LOCATE_WORDS = setOf("loc", "locate", "where", "موقع", "وين")
    private val LOCK_WORDS = setOf("lock", "قفل", "اقفل")
    private val ALARM_WORDS = setOf("alarm", "ring", "siren", "صفارة", "صفاره", "انذار", "إنذار")
    private val STOP_WORDS = setOf("stop", "وقف", "اوقف", "أوقف")
    private val TRACK_WORDS = setOf("track", "follow", "تتبع", "تعقب")
    private val CALL_WORDS = setOf("call", "اتصل", "دق")
    private val INFO_WORDS = setOf("info", "status", "معلومات", "حالة")

    /** Parses "<command> <pin>". Anything else is an ordinary SMS and is ignored. */
    fun parse(body: String): Pair<Command, String>? {
        val parts = Text.normalizeDigits(body).trim().split(Regex("\\s+"))
        if (parts.size != 2) return null
        val word = parts[0].lowercase()
        val cmd = when (word) {
            in LOCATE_WORDS -> Command.LOCATE
            in LOCK_WORDS -> Command.LOCK
            in ALARM_WORDS -> Command.ALARM
            in STOP_WORDS -> Command.STOP
            in TRACK_WORDS -> Command.TRACK
            in CALL_WORDS -> Command.CALL
            in INFO_WORDS -> Command.INFO
            else -> return null
        }
        return cmd to parts[1]
    }
}
