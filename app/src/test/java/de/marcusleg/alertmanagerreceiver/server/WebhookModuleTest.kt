package de.marcusleg.alertmanagerreceiver.server

import de.marcusleg.alertmanagerreceiver.TestFixtures
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.AuthMode
import de.marcusleg.alertmanagerreceiver.model.WebhookPayload
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookModuleTest {
    private class RecordingHandler(var settings: AppSettings, val fail: Boolean = false) : WebhookHandler {
        val received = mutableListOf<WebhookPayload>()
        val rejected = mutableListOf<Int>()
        override suspend fun currentSettings() = settings
        override suspend fun onWebhook(payload: WebhookPayload, remoteAddress: String) {
            if (fail) error("database on fire")
            received += payload
        }
        override suspend fun onRejected(remoteAddress: String, httpStatus: Int, reason: String) {
            rejected += httpStatus
        }
    }

    private val settings = AppSettings(authMode = AuthMode.BEARER, bearerToken = "token")

    private fun withServer(handler: RecordingHandler, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { webhookModule(handler) }
            block()
        }

    @Test
    fun `accepts an authorized webhook`() {
        val handler = RecordingHandler(settings)
        withServer(handler) {
            val response = client.post(WEBHOOK_PATH) {
                header(HttpHeaders.Authorization, "Bearer token")
                contentType(ContentType.Application.Json)
                setBody(TestFixtures.webhookJson())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, handler.received.single().alerts.size)
        }
    }

    @Test
    fun `root path also accepts webhooks`() {
        val handler = RecordingHandler(settings.copy(authMode = AuthMode.NONE))
        withServer(handler) {
            val response = client.post("/") { setBody("""{"alerts":[]}""") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, handler.received.size)
        }
    }

    @Test
    fun `rejects missing or wrong credentials with a challenge`() {
        val handler = RecordingHandler(settings)
        withServer(handler) {
            val missing = client.post(WEBHOOK_PATH) { setBody(TestFixtures.webhookJson()) }
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertTrue(missing.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Bearer"))

            val wrong = client.post(WEBHOOK_PATH) {
                header(HttpHeaders.Authorization, "Bearer nope")
                setBody(TestFixtures.webhookJson())
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertTrue(handler.received.isEmpty())
            assertEquals(listOf(401, 401), handler.rejected)
        }
    }

    @Test
    fun `rejects malformed JSON`() {
        val handler = RecordingHandler(settings)
        withServer(handler) {
            val response = client.post(WEBHOOK_PATH) {
                header(HttpHeaders.Authorization, "Bearer token")
                setBody("{not json")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(listOf(400), handler.rejected)
        }
    }

    @Test
    fun `processing errors return 500 so Alertmanager retries`() {
        val handler = RecordingHandler(settings, fail = true)
        withServer(handler) {
            val response = client.post(WEBHOOK_PATH) {
                header(HttpHeaders.Authorization, "Bearer token")
                setBody(TestFixtures.webhookJson())
            }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(listOf(500), handler.rejected)
        }
    }

    @Test
    fun `health and index endpoints need no auth`() {
        withServer(RecordingHandler(settings)) {
            assertEquals("ok", client.get("/healthz").bodyAsText())
            assertTrue(client.get("/").bodyAsText().contains(WEBHOOK_PATH))
        }
    }

    @Test
    fun `auth changes apply without restarting`() {
        val handler = RecordingHandler(settings)
        withServer(handler) {
            handler.settings = settings.copy(bearerToken = "rotated")
            val old = client.post(WEBHOOK_PATH) {
                header(HttpHeaders.Authorization, "Bearer token")
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Unauthorized, old.status)
        }
    }
}
