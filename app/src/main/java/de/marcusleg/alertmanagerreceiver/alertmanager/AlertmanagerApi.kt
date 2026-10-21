package de.marcusleg.alertmanagerreceiver.alertmanager

import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.AuthMode
import de.marcusleg.alertmanagerreceiver.model.ApiAlert
import de.marcusleg.alertmanagerreceiver.model.ApiStatus
import de.marcusleg.alertmanagerreceiver.model.Rfc3339
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import javax.net.ssl.HttpsURLConnection

@Serializable
data class Matcher(val name: String, val value: String, val isRegex: Boolean = false, val isEqual: Boolean = true)

@Serializable
data class SilenceRequest(
    val matchers: List<Matcher>,
    val startsAt: String,
    val endsAt: String,
    val createdBy: String,
    val comment: String,
)

@Serializable
private data class SilenceResponse(val silenceID: String = "")

/** A request failed in a way that won't fix itself: the user has to change credentials or sign in again. */
class AuthenticationException(message: String, cause: Throwable? = null) : IOException(message, cause)

class HttpStatusException(val code: Int, message: String) : IOException(message)

/** Minimal client for the Alertmanager v2 API. Only talks HTTPS. */
class AlertmanagerApi(private val authenticator: RequestAuthenticator) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun alerts(settings: AppSettings): List<ApiAlert> =
        request(settings, "GET", "alerts") { ApiAlert.parseList(it) }

    suspend fun status(settings: AppSettings): ApiStatus =
        request(settings, "GET", "status") { ApiAlert.json.decodeFromString(ApiStatus.serializer(), it) }

    suspend fun createSilence(
        settings: AppSettings,
        labels: Map<String, String>,
        startsAt: Long,
        endsAt: Long,
        createdBy: String,
        comment: String,
    ): String {
        val request = SilenceRequest(
            matchers = silenceMatchers(labels),
            startsAt = Rfc3339.format(startsAt),
            endsAt = Rfc3339.format(endsAt),
            createdBy = createdBy,
            comment = comment,
        )
        val body = json.encodeToString(SilenceRequest.serializer(), request)
        return request(settings, "POST", "silences", body) {
            json.decodeFromString(SilenceResponse.serializer(), it).silenceID
        }
    }

    private suspend fun <T> request(
        settings: AppSettings,
        method: String,
        path: String,
        body: String? = null,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val url = apiUrl(settings.alertmanagerUrl, path)
        var response = execute(url, method, authenticator.headers(settings), body)
        if (response.code == 401 && authenticator.canRefresh(settings)) {
            // The token may have been revoked or rotated server side even though it hasn't expired yet.
            response = execute(url, method, authenticator.headers(settings, forceRefresh = true), body)
        }
        checkResponse(response, settings)
        try {
            parse(response.body)
        } catch (e: SerializationException) {
            throw IOException(unexpectedBodyMessage(response), e)
        } catch (e: IllegalArgumentException) {
            throw IOException(unexpectedBodyMessage(response), e)
        }
    }

    private class Response(val code: Int, val contentType: String?, val location: String?, val body: String)

    private fun execute(url: String, method: String, headers: Map<String, String>, body: String?): Response {
        val connection = URI(url).toURL().openConnection() as? HttpsURLConnection
            ?: throw IOException("Only HTTPS connections are supported")
        connection.apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            // A redirect almost always means an auth proxy wants an interactive login; report it instead of following.
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return Response(
                code = code,
                contentType = connection.contentType,
                location = connection.getHeaderField("Location"),
                body = stream?.use { it.readLimited(MAX_RESPONSE_BYTES) }.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun checkResponse(response: Response, settings: AppSettings) {
        val code = response.code
        when {
            code in 200..299 -> return
            code in 300..399 -> throw AuthenticationException(
                buildString {
                    append("Redirected to ")
                    append(response.location?.let(::hostOf) ?: "another page")
                    append(" (HTTP $code). ")
                    append(
                        if (settings.authMode == AuthMode.NONE) {
                            "Alertmanager seems to be behind a login; configure authentication."
                        } else {
                            "The proxy in front of Alertmanager did not accept the credentials."
                        },
                    )
                },
            )
            code == 401 -> throw AuthenticationException("Credentials rejected (HTTP 401)")
            code == 403 -> throw AuthenticationException("Access denied (HTTP 403)")
            else -> throw HttpStatusException(code, "HTTP $code ${response.body.lineSequence().firstOrNull().orEmpty().take(200)}".trim())
        }
    }

    private fun unexpectedBodyMessage(response: Response): String =
        if (response.contentType?.contains("html", ignoreCase = true) == true) {
            "Got an HTML page instead of the Alertmanager API. Check the URL, or whether a login page answered."
        } else {
            "Unexpected response from the Alertmanager API"
        }

    companion object {
        private const val USER_AGENT = "alertmanager-receiver-android"
        private const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024

        /** Builds `<base>/api/v2/<path>`, tolerating base URLs with or without a path prefix or trailing slash. */
        fun apiUrl(baseUrl: String, path: String): String {
            val base = validateBaseUrl(baseUrl)
            return "${base.removeSuffix("/api/v2")}/api/v2/$path"
        }

        /** Returns the normalized base URL or throws [IllegalArgumentException] with a user-facing message. */
        fun validateBaseUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            require(base.isNotEmpty()) { "Enter the Alertmanager URL" }
            require(base.startsWith("https://", ignoreCase = true)) { "Only https:// URLs are supported" }
            val uri = try {
                URI(base)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("Not a valid URL", e)
            }
            require(!uri.host.isNullOrEmpty()) { "Not a valid URL" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "Remove the query string from the URL" }
            require(uri.rawUserInfo == null) { "Don't put credentials into the URL; use the authentication settings" }
            return base
        }

        /** Exact matchers on every label, so the silence only covers this one alert. */
        fun silenceMatchers(labels: Map<String, String>): List<Matcher> =
            labels.toSortedMap().map { (k, v) -> Matcher(name = k, value = v) }

        private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

        private fun InputStream.readLimited(limit: Int): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) throw IOException("Response from Alertmanager is too large")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
