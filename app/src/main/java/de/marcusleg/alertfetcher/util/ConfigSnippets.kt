package de.marcusleg.alertfetcher.util

import de.marcusleg.alertfetcher.data.AppSettings

/** Copy-paste configuration for the server side. */
object ConfigSnippets {
    const val DEFAULT_RECEIVER = "phone"

    private fun receiverName(settings: AppSettings): String =
        settings.receiverFilterSet.firstOrNull()?.substringAfterLast('/') ?: DEFAULT_RECEIVER

    /** Optional route that decides which alerts reach the phone (used with the receiver filter). */
    fun alertmanagerRoute(settings: AppSettings): String {
        val name = receiverName(settings)
        return buildString {
            appendLine("# A receiver without integrations: Alertmanager only records")
            appendLine("# which alerts are routed to it; the app reads them via the API.")
            appendLine("receivers:")
            appendLine("  - name: $name")
            appendLine()
            appendLine("route:")
            appendLine("  routes:")
            appendLine("    # Put this first; continue: true keeps your other routes working.")
            appendLine("    - receiver: $name")
            appendLine("      matchers:")
            appendLine("        - severity=~\"critical|warning\"")
            appendLine("      continue: true")
        }.trimEnd()
    }

    /** The same route as a prometheus-operator AlertmanagerConfig. */
    fun alertmanagerConfigCrd(settings: AppSettings): String {
        val name = receiverName(settings)
        return buildString {
            appendLine("apiVersion: monitoring.coreos.com/v1alpha1")
            appendLine("kind: AlertmanagerConfig")
            appendLine("metadata:")
            appendLine("  name: $name")
            appendLine("  namespace: monitoring")
            appendLine("spec:")
            appendLine("  route:")
            appendLine("    receiver: $name")
            appendLine("    matchers:")
            appendLine("      - name: severity")
            appendLine("        matchType: =~")
            appendLine("        value: critical|warning")
            appendLine("    continue: true")
            appendLine("  receivers:")
            appendLine("    - name: $name")
        }.trimEnd()
    }

    /** oauth2-proxy accepts bearer tokens from the app's OIDC client with these flags. */
    fun oauth2ProxyArgs(settings: AppSettings): String {
        val issuer = settings.oidcIssuer.trim().ifEmpty { "https://idp.example.com/realms/homelab" }
        val clientId = settings.oidcClientId.trim().ifEmpty { "alert-fetcher" }
        return buildString {
            appendLine("# oauth2-proxy in front of Alertmanager: accept the app's tokens")
            appendLine("# as bearer tokens in addition to the browser login.")
            appendLine("--skip-jwt-bearer-tokens=true")
            appendLine("--extra-jwt-issuers=$issuer=$clientId")
        }.trimEnd()
    }
}
