package de.marcusleg.alertmanagerreceiver.alertmanager

import de.marcusleg.alertmanagerreceiver.model.Rfc3339
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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

/** Tiny client for the Alertmanager v2 API (only what the app needs). */
class AlertmanagerClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun createSilence(
        baseUrl: String,
        authorization: String?,
        labels: Map<String, String>,
        startsAt: Long,
        endsAt: Long,
        createdBy: String,
        comment: String,
    ): String = withContext(Dispatchers.IO) {
        val request = SilenceRequest(
            matchers = silenceMatchers(labels),
            startsAt = Rfc3339.format(startsAt),
            endsAt = Rfc3339.format(endsAt),
            createdBy = createdBy,
            comment = comment,
        )
        val body = post(apiUrl(baseUrl, "silences"), authorization, json.encodeToString(SilenceRequest.serializer(), request))
        json.decodeFromString(SilenceResponse.serializer(), body).silenceID
    }

    private fun post(url: String, authorization: String?, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            authorization?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", it.trim()) }
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${response.take(200)}".trim())
            return response
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Builds `<base>/api/v2/<path>`, tolerating base URLs with or without a path prefix or trailing slash. */
        fun apiUrl(baseUrl: String, path: String): String {
            val base = baseUrl.trim().trimEnd('/')
            require(base.startsWith("http://") || base.startsWith("https://")) { "Alertmanager URL must start with http:// or https://" }
            val root = base.removeSuffix("/api/v2")
            return "$root/api/v2/$path"
        }

        /** Exact matchers on every label, so the silence only covers this one alert. */
        fun silenceMatchers(labels: Map<String, String>): List<Matcher> =
            labels.toSortedMap().map { (k, v) -> Matcher(name = k, value = v) }
    }
}
