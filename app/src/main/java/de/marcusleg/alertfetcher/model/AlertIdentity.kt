package de.marcusleg.alertfetcher.model

import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

object AlertIdentity {
    /**
     * Alertmanager includes a fingerprint for every alert; in case a proxy or an old version drops it,
     * fall back to a stable hash over the sorted label set (which is what Alertmanager does too).
     */
    fun fingerprint(alert: ApiAlert): String =
        alert.fingerprint.ifBlank { fingerprintOf(alert.labels) }

    fun fingerprintOf(labels: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        labels.toSortedMap().forEach { (k, v) ->
            digest.update(k.toByteArray())
            digest.update(0)
            digest.update(v.toByteArray())
            digest.update(0)
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}

object Rfc3339 {
    /** Parses Alertmanager timestamps; the zero time (`0001-01-01T00:00:00Z`) means "unset". */
    fun parseMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            val instant = OffsetDateTime.parse(value).toInstant()
            if (instant.isBefore(Instant.EPOCH)) null else instant.toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun format(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
