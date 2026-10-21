package de.marcusleg.alertmanagerreceiver.model

/** Normalised severity derived from the conventional `severity` label. */
enum class Severity(val label: String) {
    CRITICAL("Critical"),
    WARNING("Warning"),
    INFO("Info");

    fun isAtLeast(other: Severity): Boolean = ordinal <= other.ordinal

    companion object {
        private val critical = setOf("critical", "crit", "error", "err", "high", "page", "disaster", "emergency", "fatal", "p1")
        private val warning = setOf("warning", "warn", "medium", "average", "major", "minor", "p2", "p3")
        private val info = setOf("info", "information", "informational", "low", "none", "notice", "debug", "ok", "p4", "p5")

        /** Unknown or missing severities are treated as warnings so they are not silently dropped. */
        fun fromLabel(value: String?): Severity {
            val v = value?.trim()?.lowercase() ?: return WARNING
            return when (v) {
                in critical -> CRITICAL
                in warning -> WARNING
                in info -> INFO
                else -> WARNING
            }
        }
    }
}

enum class AlertStatus {
    FIRING,

    /** Still active in Alertmanager but silenced or inhibited there. */
    SUPPRESSED,

    /** No longer returned by Alertmanager. */
    RESOLVED,
}
