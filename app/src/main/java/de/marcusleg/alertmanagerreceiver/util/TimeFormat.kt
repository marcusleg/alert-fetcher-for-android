package de.marcusleg.alertmanagerreceiver.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object TimeFormat {
    private val absolute = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
    private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun duration(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0) / 60_000)
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
            hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            totalMinutes > 0 -> "${minutes}m"
            else -> "${millis.coerceAtLeast(0) / 1000}s"
        }
    }

    fun relative(at: Long, now: Long = System.currentTimeMillis()): String {
        val delta = now - at
        return if (delta < 60_000 && delta > -60_000) "just now" else if (delta >= 0) "${duration(delta)} ago" else "in ${duration(-delta)}"
    }

    fun absolute(at: Long): String = absolute.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    /** Time of day, e.g. "14:05", for texts that aren't refreshed (notifications). */
    fun clock(at: Long): String = clock.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
}
