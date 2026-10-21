package de.marcusleg.alertfetcher

import de.marcusleg.alertfetcher.model.ApiAlert

object TestFixtures {
    fun alertsJson(): String =
        requireNotNull(javaClass.classLoader?.getResource("alertmanager-alerts.json")) { "fixture missing" }.readText()

    fun alerts(): List<ApiAlert> = ApiAlert.parseList(alertsJson())
}
