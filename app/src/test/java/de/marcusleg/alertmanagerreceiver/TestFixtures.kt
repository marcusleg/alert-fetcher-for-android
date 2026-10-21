package de.marcusleg.alertmanagerreceiver

object TestFixtures {
    fun webhookJson(): String =
        requireNotNull(javaClass.classLoader?.getResource("alertmanager-webhook.json")) { "fixture missing" }.readText()
}
