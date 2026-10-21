package de.marcusleg.alertmanagerreceiver.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.marcusleg.alertmanagerreceiver.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

enum class AuthMode { NONE, BEARER, BASIC }

@Serializable
data class AppSettings(
    val serverEnabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val authMode: AuthMode = AuthMode.BEARER,
    val bearerToken: String = "",
    val basicUsername: String = "alertmanager",
    val basicPassword: String = "",
    val startOnBoot: Boolean = true,
    val keepCpuAwake: Boolean = false,
    val notifyResolved: Boolean = true,
    val minNotifySeverity: Severity = Severity.INFO,
    /** Comma separated alert names that are dropped entirely (e.g. kube-prometheus-stack's InfoInhibitor). */
    val ignoredAlertNames: String = "InfoInhibitor",
    val heartbeatEnabled: Boolean = false,
    val heartbeatAlertName: String = "Watchdog",
    val heartbeatTimeoutMinutes: Int = 15,
    /** Resolved alerts older than this are removed; 0 keeps them forever. */
    val retentionDays: Int = 7,
    /** Base URL of the Alertmanager API used for silences; falls back to the payload's externalURL. */
    val alertmanagerUrl: String = "",
    /** Optional value of the Authorization header sent to the Alertmanager API. */
    val alertmanagerAuthorization: String = "",
) {
    val ignoredAlertNameSet: Set<String>
        get() = ignoredAlertNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    companion object {
        const val DEFAULT_PORT = 9095
    }
}

data class HeartbeatState(
    val lastSeenAt: Long? = null,
    /** When monitoring (re)started; the timeout is measured from this if no heartbeat arrived since. */
    val armedAt: Long? = null,
    val missed: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settingsFlow: Flow<AppSettings> = store.data.map { prefs ->
        prefs[SETTINGS]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
            ?: AppSettings()
    }

    /** Hot copy of the settings for synchronous access (e.g. per-request auth checks). */
    val settings: StateFlow<AppSettings> = settingsFlow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val heartbeat: Flow<HeartbeatState> = store.data.map { prefs ->
        prefs.heartbeatState()
    }

    private fun Preferences.heartbeatState() =
        HeartbeatState(this[HEARTBEAT_LAST_SEEN], this[HEARTBEAT_ARMED_AT], this[HEARTBEAT_MISSED] ?: false)

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        var result = AppSettings()
        store.edit { prefs ->
            val current = prefs[SETTINGS]
                ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
                ?: AppSettings()
            result = transform(current)
            prefs[SETTINGS] = json.encodeToString(AppSettings.serializer(), result)
        }
        return result
    }

    /** Generates credentials on first launch so the receiver is never exposed without auth by default. */
    suspend fun ensureDefaults() {
        update { s ->
            s.copy(
                bearerToken = s.bearerToken.ifBlank { generateSecret() },
                basicPassword = s.basicPassword.ifBlank { generateSecret() },
            )
        }
    }

    suspend fun currentHeartbeat(): HeartbeatState = heartbeat.first()

    suspend fun recordHeartbeat(at: Long): HeartbeatState {
        var previous = HeartbeatState()
        store.edit { prefs ->
            previous = prefs.heartbeatState()
            prefs[HEARTBEAT_LAST_SEEN] = at
            prefs[HEARTBEAT_MISSED] = false
        }
        return previous
    }

    suspend fun setHeartbeatMissed(missed: Boolean) {
        store.edit { it[HEARTBEAT_MISSED] = missed }
    }

    suspend fun armHeartbeat(at: Long) {
        store.edit {
            it[HEARTBEAT_ARMED_AT] = at
            it[HEARTBEAT_MISSED] = false
        }
    }

    companion object {
        private val SETTINGS = stringPreferencesKey("settings_json")
        private val HEARTBEAT_LAST_SEEN = longPreferencesKey("heartbeat_last_seen")
        private val HEARTBEAT_MISSED = booleanPreferencesKey("heartbeat_missed")
        private val HEARTBEAT_ARMED_AT = longPreferencesKey("heartbeat_armed_at")

        fun generateSecret(bytes: Int = 24): String {
            val buffer = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
        }
    }
}
