package de.marcusleg.alertfetcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.marcusleg.alertfetcher.model.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class AuthMode { NONE, BASIC, BEARER, OIDC }

/** Which OIDC token is sent to the proxy in front of Alertmanager. */
enum class OidcTokenType { ACCESS_TOKEN, ID_TOKEN }

@Serializable
data class AppSettings(
    val monitoringEnabled: Boolean = false,
    /** Base URL of Alertmanager (or of the ingress in front of it), e.g. `https://alertmanager.example.com`. */
    val alertmanagerUrl: String = "",
    val authMode: AuthMode = AuthMode.OIDC,
    val basicUsername: String = "",
    val basicPassword: String = "",
    val bearerToken: String = "",
    val oidcIssuer: String = "",
    val oidcClientId: String = "",
    /** Optional; public clients using PKCE don't need one. */
    val oidcClientSecret: String = "",
    val oidcScopes: String = DEFAULT_OIDC_SCOPES,
    val oidcTokenType: OidcTokenType = OidcTokenType.ACCESS_TOKEN,
    /** Extra request headers, one `Name: value` per line (e.g. Cloudflare Access service tokens). */
    val extraHeaders: String = "",
    val pollIntervalMinutes: Int = DEFAULT_POLL_INTERVAL_MINUTES,
    /** Warn when Alertmanager can't be reached (or the heartbeat is missing) for this long. */
    val staleAfterMinutes: Int = 15,
    /** Comma separated receiver names; when set, only alerts routed to one of them are shown. */
    val receiverFilter: String = "",
    val startOnBoot: Boolean = true,
    val notifyResolved: Boolean = true,
    val minNotifySeverity: Severity = Severity.INFO,
    /** Comma separated alert names that are dropped entirely (kube-prometheus-stack's always-firing helpers by default). */
    val ignoredAlertNames: String = "InfoInhibitor, Watchdog",
    val heartbeatEnabled: Boolean = false,
    val heartbeatAlertName: String = "Watchdog",
    /** Resolved alerts older than this are removed; 0 keeps them forever. */
    val retentionDays: Int = 7,
) {
    val ignoredAlertNameSet: Set<String> get() = commaSeparated(ignoredAlertNames)
    val receiverFilterSet: Set<String> get() = commaSeparated(receiverFilter)
    val isConfigured: Boolean get() = alertmanagerUrl.isNotBlank()
    val pollIntervalMillis: Long get() = pollIntervalMinutes.coerceAtLeast(1) * 60_000L

    /** Never alarm on a single failed check, even with long poll intervals. */
    val effectiveStaleAfterMinutes: Int get() = maxOf(staleAfterMinutes, pollIntervalMinutes * 2)

    companion object {
        const val DEFAULT_POLL_INTERVAL_MINUTES = 5
        const val DEFAULT_OIDC_SCOPES = "openid profile email offline_access"
        val POLL_INTERVAL_OPTIONS = listOf(1, 2, 5, 10, 15, 30, 60)

        private fun commaSeparated(value: String) = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}

/** What the poller knows about the health of the connection; survives process death. */
@Serializable
data class MonitorState(
    /** When monitoring was last switched on; used as reference before the first successful check. */
    val since: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    /** The last failure needs user action (credentials rejected or OIDC sign-in expired). */
    val authProblem: Boolean = false,
    val contactLostNotified: Boolean = false,
    val heartbeatLastSeenAt: Long? = null,
    /** When heartbeat monitoring was switched on; the reference until the first heartbeat is seen. */
    val heartbeatArmedAt: Long? = null,
    val heartbeatMissingNotified: Boolean = false,
) {
    val lastCheckFailed: Boolean get() = consecutiveFailures > 0
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settingsFlow: Flow<AppSettings> = store.data.map { it.decode(SETTINGS, AppSettings.serializer()) ?: AppSettings() }
    val monitorStateFlow: Flow<MonitorState> = store.data.map { it.decode(MONITOR_STATE, MonitorState.serializer()) ?: MonitorState() }

    suspend fun current(): AppSettings = settingsFlow.first()
    suspend fun currentMonitorState(): MonitorState = monitorStateFlow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        edit(SETTINGS, AppSettings.serializer(), AppSettings(), transform)

    suspend fun updateMonitorState(transform: (MonitorState) -> MonitorState): MonitorState =
        edit(MONITOR_STATE, MonitorState.serializer(), MonitorState(), transform)

    /** Serialized AppAuth `AuthState`, kept apart from the settings so UI edits can't race token refreshes. */
    val oidcStateFlow: Flow<String?> = store.data.map { it[OIDC_STATE] }

    suspend fun oidcState(): String? = oidcStateFlow.first()

    suspend fun setOidcState(value: String?) {
        store.edit { if (value == null) it.remove(OIDC_STATE) else it[OIDC_STATE] = value }
    }

    private fun <T> Preferences.decode(key: Preferences.Key<String>, serializer: KSerializer<T>): T? =
        this[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private suspend fun <T> edit(key: Preferences.Key<String>, serializer: KSerializer<T>, default: T, transform: (T) -> T): T {
        var result = default
        store.edit { prefs ->
            result = transform(prefs.decode(key, serializer) ?: default)
            prefs[key] = json.encodeToString(serializer, result)
        }
        return result
    }

    private companion object {
        val SETTINGS = stringPreferencesKey("settings_json")
        val MONITOR_STATE = stringPreferencesKey("monitor_state_json")
        val OIDC_STATE = stringPreferencesKey("oidc_auth_state")
    }
}
