package de.marcusleg.alertmanagerreceiver.util

import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.AuthMode

/** Copy-paste configuration for the common ways Alertmanager is deployed. */
object ConfigSnippets {
    const val RECEIVER_NAME = "android"

    fun alertmanagerYaml(url: String, settings: AppSettings): String = buildString {
        appendLine("route:")
        appendLine("  receiver: $RECEIVER_NAME")
        appendLine("  group_by: [alertname, namespace]")
        if (settings.heartbeatEnabled) {
            appendLine("  routes:")
            appendLine("    - receiver: $RECEIVER_NAME")
            appendLine("      matchers: [alertname=\"${settings.heartbeatAlertName}\"]")
            appendLine("      group_wait: 0s")
            appendLine("      group_interval: 1m")
            appendLine("      repeat_interval: ${heartbeatRepeatMinutes(settings)}m")
        }
        appendLine()
        appendLine("receivers:")
        appendLine("  - name: $RECEIVER_NAME")
        appendLine("    webhook_configs:")
        appendLine("      - url: $url")
        appendLine("        send_resolved: true")
        when (settings.authMode) {
            AuthMode.NONE -> Unit
            AuthMode.BEARER -> {
                appendLine("        http_config:")
                appendLine("          authorization:")
                appendLine("            type: Bearer")
                appendLine("            credentials: ${settings.bearerToken}")
            }
            AuthMode.BASIC -> {
                appendLine("        http_config:")
                appendLine("          basic_auth:")
                appendLine("            username: ${settings.basicUsername}")
                appendLine("            password: ${settings.basicPassword}")
            }
        }
    }.trimEnd() + "\n"

    /** prometheus-operator `AlertmanagerConfig` (e.g. kube-prometheus-stack) plus the Secret holding the credentials. */
    fun alertmanagerConfigCrd(url: String, settings: AppSettings, namespace: String = "monitoring"): String = buildString {
        if (settings.authMode != AuthMode.NONE) {
            appendLine("apiVersion: v1")
            appendLine("kind: Secret")
            appendLine("metadata:")
            appendLine("  name: android-receiver")
            appendLine("  namespace: $namespace")
            appendLine("stringData:")
            when (settings.authMode) {
                AuthMode.BEARER -> appendLine("  token: ${settings.bearerToken}")
                AuthMode.BASIC -> {
                    appendLine("  username: ${settings.basicUsername}")
                    appendLine("  password: ${settings.basicPassword}")
                }
                AuthMode.NONE -> Unit
            }
            appendLine("---")
        }
        appendLine("apiVersion: monitoring.coreos.com/v1alpha1")
        appendLine("kind: AlertmanagerConfig")
        appendLine("metadata:")
        appendLine("  name: android-receiver")
        appendLine("  namespace: $namespace")
        appendLine("spec:")
        appendLine("  route:")
        appendLine("    receiver: $RECEIVER_NAME")
        appendLine("    groupBy: [alertname, namespace]")
        if (settings.heartbeatEnabled) {
            appendLine("    routes:")
            appendLine("      - receiver: $RECEIVER_NAME")
            appendLine("        matchers:")
            appendLine("          - name: alertname")
            appendLine("            value: ${settings.heartbeatAlertName}")
            appendLine("        groupWait: 0s")
            appendLine("        groupInterval: 1m")
            appendLine("        repeatInterval: ${heartbeatRepeatMinutes(settings)}m")
        }
        appendLine("  receivers:")
        appendLine("    - name: $RECEIVER_NAME")
        appendLine("      webhookConfigs:")
        appendLine("        - url: $url")
        appendLine("          sendResolved: true")
        when (settings.authMode) {
            AuthMode.NONE -> Unit
            AuthMode.BEARER -> {
                appendLine("          httpConfig:")
                appendLine("            authorization:")
                appendLine("              type: Bearer")
                appendLine("              credentials:")
                appendLine("                name: android-receiver")
                appendLine("                key: token")
            }
            AuthMode.BASIC -> {
                appendLine("          httpConfig:")
                appendLine("            basicAuth:")
                appendLine("              username:")
                appendLine("                name: android-receiver")
                appendLine("                key: username")
                appendLine("              password:")
                appendLine("                name: android-receiver")
                appendLine("                key: password")
            }
        }
    }.trimEnd() + "\n"

    fun curlTest(url: String, settings: AppSettings): String {
        val auth = when (settings.authMode) {
            AuthMode.NONE -> ""
            AuthMode.BEARER -> " \\\n  -H 'Authorization: Bearer ${settings.bearerToken}'"
            AuthMode.BASIC -> " \\\n  -u '${settings.basicUsername}:${settings.basicPassword}'"
        }
        return "curl -X POST $url$auth \\\n  -H 'Content-Type: application/json' \\\n" +
            "  -d '{\"status\":\"firing\",\"alerts\":[{\"status\":\"firing\",\"labels\":{\"alertname\":\"CurlTest\",\"severity\":\"info\"},\"annotations\":{\"summary\":\"Hello from curl\"}}]}'\n"
    }

    /** Repeat the heartbeat well within the timeout so a single dropped delivery does not trigger an alarm. */
    fun heartbeatRepeatMinutes(settings: AppSettings): Int = (settings.heartbeatTimeoutMinutes / 3).coerceIn(1, 60)
}
