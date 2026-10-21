package de.marcusleg.alertmanagerreceiver

import de.marcusleg.alertmanagerreceiver.model.ApiAlert

object TestFixtures {
    fun alertsJson(): String =
        requireNotNull(javaClass.classLoader?.getResource("alertmanager-alerts.json")) { "fixture missing" }.readText()

    fun alerts(): List<ApiAlert> = ApiAlert.parseList(alertsJson())
}
