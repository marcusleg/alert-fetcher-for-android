package de.marcusleg.alertfetcher.alertmanager

import de.marcusleg.alertfetcher.auth.OidcManager
import de.marcusleg.alertfetcher.data.AppSettings
import de.marcusleg.alertfetcher.data.AuthMode
import java.util.Base64

/** Builds the headers that authenticate requests to Alertmanager (or the proxy in front of it). */
class RequestAuthenticator(private val oidc: OidcManager) {

    suspend fun headers(settings: AppSettings, forceRefresh: Boolean = false): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val extra = parseExtraHeaders(settings.extraHeaders)
        extra.error?.let { throw AuthenticationException("Additional headers: $it") }
        extra.headers.forEach { (name, value) -> headers[name] = value }
        authorization(settings, forceRefresh)?.let { headers["Authorization"] = it }
        return headers
    }

    fun canRefresh(settings: AppSettings): Boolean = settings.authMode == AuthMode.OIDC

    private suspend fun authorization(settings: AppSettings, forceRefresh: Boolean): String? = when (settings.authMode) {
        AuthMode.NONE -> null
        AuthMode.BASIC -> {
            if (settings.basicUsername.isBlank() || settings.basicPassword.isEmpty()) {
                throw AuthenticationException("Enter a username and password for basic authentication")
            }
            basic(settings.basicUsername, settings.basicPassword)
        }
        AuthMode.BEARER -> {
            val token = settings.bearerToken.trim()
            if (token.isEmpty()) throw AuthenticationException("Enter a bearer token")
            "Bearer $token"
        }
        AuthMode.OIDC -> "Bearer ${oidc.token(settings, forceRefresh)}"
    }

    data class ExtraHeaders(val headers: List<Pair<String, String>>, val error: String? = null)

    companion object {
        private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        fun basic(username: String, password: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

        /** Parses `Name: value` lines; blank lines and lines starting with `#` are ignored. */
        fun parseExtraHeaders(text: String): ExtraHeaders {
            val headers = mutableListOf<Pair<String, String>>()
            text.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val colon = line.indexOf(':')
                if (colon <= 0) return ExtraHeaders(headers, "line ${index + 1} is not \"Name: value\"")
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (!TOKEN.matches(name)) return ExtraHeaders(headers, "invalid header name \"$name\"")
                if (name.equals("Host", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) {
                    return ExtraHeaders(headers, "\"$name\" can't be overridden")
                }
                headers += name to value
            }
            return ExtraHeaders(headers)
        }
    }
}
