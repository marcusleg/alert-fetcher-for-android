package de.marcusleg.alertmanagerreceiver.server

import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.model.WebhookPayload
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/** Callbacks the HTTP layer uses to hand data to the rest of the app. */
interface WebhookHandler {
    suspend fun currentSettings(): AppSettings
    suspend fun onWebhook(payload: WebhookPayload, remoteAddress: String)
    suspend fun onRejected(remoteAddress: String, httpStatus: Int, reason: String)
}

const val WEBHOOK_PATH = "/webhook"
const val MAX_BODY_BYTES = 4L * 1024 * 1024

private const val INDEX_TEXT = """Alertmanager Receiver for Android

POST Alertmanager webhook notifications to $WEBHOOK_PATH
GET /healthz for a liveness check
"""

fun Application.webhookModule(handler: WebhookHandler) {
    routing {
        get("/") { call.respondText(INDEX_TEXT, ContentType.Text.Plain) }
        get("/healthz") { call.respondText("ok", ContentType.Text.Plain) }
        post(WEBHOOK_PATH) { handleWebhook(call, handler) }
        post("/") { handleWebhook(call, handler) }
    }
}

private suspend fun handleWebhook(call: ApplicationCall, handler: WebhookHandler) {
    val remote = call.request.origin.remoteAddress
    val settings = handler.currentSettings()

    if (!AuthValidator.isAuthorized(call.request.headers[HttpHeaders.Authorization], settings)) {
        AuthValidator.challenge(settings)?.let { call.response.header(HttpHeaders.WWWAuthenticate, it) }
        handler.onRejected(remote, 401, "Unauthorized")
        call.respondJson(HttpStatusCode.Unauthorized, "unauthorized")
        return
    }

    if ((call.request.contentLength() ?: 0L) > MAX_BODY_BYTES) {
        handler.onRejected(remote, 413, "Payload too large")
        call.respondJson(HttpStatusCode.PayloadTooLarge, "payload too large")
        return
    }

    val payload = try {
        WebhookPayload.parse(call.receiveText())
    } catch (e: SerializationException) {
        handler.onRejected(remote, 400, "Invalid JSON: ${e.message?.take(200)}")
        call.respondJson(HttpStatusCode.BadRequest, "invalid payload")
        return
    } catch (e: IllegalArgumentException) {
        handler.onRejected(remote, 400, "Invalid payload: ${e.message?.take(200)}")
        call.respondJson(HttpStatusCode.BadRequest, "invalid payload")
        return
    }

    try {
        handler.onWebhook(payload, remote)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A 5xx makes Alertmanager retry the notification, which is what we want on transient failures.
        handler.onRejected(remote, 500, "Processing failed: ${e.message?.take(200)}")
        call.respondJson(HttpStatusCode.InternalServerError, "processing failed")
        return
    }
    call.respondJson(HttpStatusCode.OK, "ok")
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, message: String) {
    respondText("{\"status\":\"$message\"}", ContentType.Application.Json, status)
}
