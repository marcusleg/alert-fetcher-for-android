package de.marcusleg.alertmanagerreceiver.server

import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ServerState {
    data object Stopped : ServerState
    data class Starting(val port: Int) : ServerState
    data class Running(val port: Int, val since: Long) : ServerState
    data class Failed(val port: Int, val message: String) : ServerState
}

/** Owns the embedded Ktor server. Shared app-wide so the UI can observe its state. */
class WebhookServer {
    private val mutex = Mutex()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    // Binding and graceful shutdown can block, so keep them off the caller's (often main) thread.
    suspend fun start(port: Int, handler: WebhookHandler) = withContext(Dispatchers.IO) { mutex.withLock { startLocked(port, handler) } }

    suspend fun stop() = withContext(Dispatchers.IO) { mutex.withLock { stopLocked() } }

    private suspend fun startLocked(port: Int, handler: WebhookHandler) {
        stopLocked()
        _state.value = ServerState.Starting(port)
        val instance = embeddedServer(CIO, port = port, host = "0.0.0.0") { webhookModule(handler) }
        try {
            instance.startSuspend(wait = false)
            // Surfaces bind errors such as "address already in use".
            instance.engine.resolvedConnectors()
            server = instance
            _state.value = ServerState.Running(port, System.currentTimeMillis())
        } catch (e: CancellationException) {
            runCatching { instance.stop(0, 500) }
            _state.value = ServerState.Stopped
            throw e
        } catch (e: Exception) {
            runCatching { instance.stop(0, 500) }
            _state.value = ServerState.Failed(port, e.message ?: e::class.java.simpleName)
        }
    }

    private fun stopLocked() {
        server?.let { runCatching { it.stop(gracePeriodMillis = 250, timeoutMillis = 1_500) } }
        server = null
        _state.value = ServerState.Stopped
    }
}
