package de.marcusleg.alertmanagerreceiver.server

import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.AuthMode
import java.security.MessageDigest
import java.util.Base64

object AuthValidator {
    /** Checks an `Authorization` header against the configured credentials. Fails closed on empty secrets. */
    fun isAuthorized(authorizationHeader: String?, settings: AppSettings): Boolean = when (settings.authMode) {
        AuthMode.NONE -> true
        AuthMode.BEARER -> {
            val token = credentials(authorizationHeader, "Bearer")
            settings.bearerToken.isNotEmpty() && token != null && constantTimeEquals(token, settings.bearerToken)
        }
        AuthMode.BASIC -> {
            val decoded = credentials(authorizationHeader, "Basic")?.let { encoded ->
                runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
            }
            val separator = decoded?.indexOf(':') ?: -1
            if (decoded == null || separator < 0 || settings.basicPassword.isEmpty()) {
                false
            } else {
                // Evaluate both comparisons to avoid leaking which part was wrong through timing.
                val userOk = constantTimeEquals(decoded.substring(0, separator), settings.basicUsername)
                val passOk = constantTimeEquals(decoded.substring(separator + 1), settings.basicPassword)
                userOk and passOk
            }
        }
    }

    fun challenge(settings: AppSettings): String? = when (settings.authMode) {
        AuthMode.NONE -> null
        AuthMode.BEARER -> "Bearer realm=\"alertmanager-receiver\""
        AuthMode.BASIC -> "Basic realm=\"alertmanager-receiver\", charset=\"UTF-8\""
    }

    /** Builds the header value a client (e.g. the built-in test sender) must send. */
    fun headerFor(settings: AppSettings): String? = when (settings.authMode) {
        AuthMode.NONE -> null
        AuthMode.BEARER -> "Bearer ${settings.bearerToken}"
        AuthMode.BASIC -> "Basic " + Base64.getEncoder()
            .encodeToString("${settings.basicUsername}:${settings.basicPassword}".toByteArray(Charsets.UTF_8))
    }

    private fun credentials(header: String?, scheme: String): String? {
        val value = header?.trim() ?: return null
        if (value.length <= scheme.length || !value.regionMatches(0, scheme, 0, scheme.length, ignoreCase = true)) return null
        if (!value[scheme.length].isWhitespace()) return null
        return value.substring(scheme.length).trim().ifEmpty { null }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
