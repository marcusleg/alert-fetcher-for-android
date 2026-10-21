package de.marcusleg.alertmanagerreceiver.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import de.marcusleg.alertmanagerreceiver.alertmanager.AuthenticationException
import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.OidcTokenType
import de.marcusleg.alertmanagerreceiver.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientSecretBasic
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the UI shows about the current OIDC sign-in. */
data class OidcSession(
    val issuer: String,
    val clientId: String,
    val identity: String?,
    val accessTokenExpiresAt: Long?,
    val idTokenExpiresAt: Long?,
    val canRefresh: Boolean,
)

/**
 * OpenID Connect sign-in using the authorization code flow with PKCE in a browser tab (AppAuth),
 * so the app never sees the user's password. Refresh tokens keep the session alive in the background.
 */
class OidcManager(private val context: Context, private val settingsRepository: SettingsRepository) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Registered with the identity provider as an allowed redirect URI. */
    val redirectUri: Uri get() = "${context.packageName}:/oauth2redirect".toUri()

    val session: Flow<OidcSession?> = settingsRepository.oidcStateFlow.map { raw -> decode(raw)?.toSession() }

    /** Discovers the provider and returns the intent that opens its login page in a browser tab. */
    suspend fun authorizationIntent(settings: AppSettings): Intent {
        val issuer = validateIssuer(settings.oidcIssuer)
        val clientId = settings.oidcClientId.trim()
        require(clientId.isNotEmpty()) { "Enter the client ID" }
        val configuration = discover(issuer.toUri())
        val request = AuthorizationRequest.Builder(configuration, clientId, ResponseTypeValues.CODE, redirectUri)
            .setScope(settings.oidcScopes.trim().ifEmpty { AppSettings.DEFAULT_OIDC_SCOPES })
            .build()
        val service = AuthorizationService(context)
        try {
            return service.getAuthorizationRequestIntent(request)
        } finally {
            service.dispose()
        }
    }

    /** Exchanges the authorization code from the browser redirect for tokens and stores them. */
    suspend fun completeAuthorization(data: Intent?, settings: AppSettings) {
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val error = data?.let { AuthorizationException.fromIntent(it) }
        if (response == null) {
            throw when {
                error == null || error == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW ->
                    IOException("Sign-in cancelled")
                else -> IOException("Sign-in failed: ${error.describe()}")
            }
        }
        val state = AuthState(response, error)
        val service = AuthorizationService(context)
        try {
            val tokens = suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(response.createTokenExchangeRequest(), clientAuthentication(settings)) { tokens, ex ->
                    state.update(tokens, ex)
                    if (tokens != null) continuation.resume(tokens) else continuation.resumeWithException(IOException("Token exchange failed: ${ex.describe()}"))
                }
            }
            if (settings.oidcTokenType == OidcTokenType.ID_TOKEN && tokens.idToken == null) {
                throw IOException("The identity provider returned no ID token; is the openid scope requested?")
            }
        } finally {
            service.dispose()
        }
        mutex.withLock {
            store(StoredSession(settings.oidcIssuer.trim(), settings.oidcClientId.trim(), state.jsonSerializeString()))
        }
    }

    /**
     * Returns a valid token for the configured token type, refreshing it first if needed.
     * Throws [AuthenticationException] when the user has to sign in again.
     */
    suspend fun token(settings: AppSettings, forceRefresh: Boolean = false): String = mutex.withLock {
        val stored = decode(settingsRepository.oidcState()) ?: throw AuthenticationException("Not signed in to the identity provider")
        if (stored.issuer != settings.oidcIssuer.trim() || stored.clientId != settings.oidcClientId.trim()) {
            throw AuthenticationException("OIDC settings changed; sign in again")
        }
        val state = runCatching { AuthState.jsonDeserialize(stored.state) }.getOrNull()
            ?: throw AuthenticationException("Stored sign-in is unreadable; sign in again")
        val idTokenExpiry = JwtClaims.parse(state.idToken)?.expiresAt
        val idTokenStale = settings.oidcTokenType == OidcTokenType.ID_TOKEN &&
            idTokenExpiry != null && idTokenExpiry - EXPIRY_MARGIN_MS < System.currentTimeMillis()
        if (forceRefresh || idTokenStale) state.needsTokenRefresh = true

        val service = AuthorizationService(context)
        try {
            val (accessToken, idToken) = suspendCancellableCoroutine { continuation ->
                state.performActionWithFreshTokens(service, clientAuthentication(settings)) { access, id, ex ->
                    if (ex == null) {
                        continuation.resume(access to id)
                    } else {
                        Log.w(TAG, "Token refresh failed", ex)
                        continuation.resumeWithException(ex.toIoException())
                    }
                }
            }
            when (settings.oidcTokenType) {
                OidcTokenType.ACCESS_TOKEN -> accessToken ?: throw AuthenticationException("The identity provider issued no access token")
                OidcTokenType.ID_TOKEN -> idToken ?: throw AuthenticationException("The identity provider issued no ID token")
            }
        } finally {
            // Refresh tokens may rotate, so persist whatever AppAuth ended up with.
            store(stored.copy(state = state.jsonSerializeString()))
            service.dispose()
        }
    }

    suspend fun signOut() = mutex.withLock { settingsRepository.setOidcState(null) }

    private suspend fun discover(issuer: Uri): AuthorizationServiceConfiguration = suspendCancellableCoroutine { continuation ->
        AuthorizationServiceConfiguration.fetchFromIssuer(issuer) { configuration, ex ->
            if (configuration != null) {
                continuation.resume(configuration)
            } else {
                continuation.resumeWithException(IOException("Discovery failed for $issuer: ${ex.describe()}"))
            }
        }
    }

    private fun clientAuthentication(settings: AppSettings): ClientAuthentication =
        settings.oidcClientSecret.takeIf { it.isNotEmpty() }?.let { ClientSecretBasic(it) } ?: NoClientAuthentication.INSTANCE

    @Serializable
    private data class StoredSession(val issuer: String, val clientId: String, val state: String)

    private fun decode(raw: String?): StoredSession? =
        raw?.let { runCatching { json.decodeFromString(StoredSession.serializer(), it) }.getOrNull() }

    private suspend fun store(session: StoredSession) =
        settingsRepository.setOidcState(json.encodeToString(StoredSession.serializer(), session))

    private fun StoredSession.toSession(): OidcSession? {
        val state = runCatching { AuthState.jsonDeserialize(this.state) }.getOrNull() ?: return null
        if (!state.isAuthorized) return null
        val idClaims = JwtClaims.parse(state.idToken)
        return OidcSession(
            issuer = issuer,
            clientId = clientId,
            identity = idClaims?.identity ?: JwtClaims.parse(state.accessToken)?.identity,
            accessTokenExpiresAt = state.accessTokenExpirationTime,
            idTokenExpiresAt = idClaims?.expiresAt,
            canRefresh = state.refreshToken != null,
        )
    }

    companion object {
        private const val TAG = "OidcManager"
        private const val EXPIRY_MARGIN_MS = 60_000L

        fun validateIssuer(issuer: String): String {
            val value = issuer.trim().trimEnd('/')
            require(value.isNotEmpty()) { "Enter the issuer URL" }
            require(value.startsWith("https://", ignoreCase = true)) { "The issuer URL must start with https://" }
            return value
        }

        private fun AuthorizationException?.describe(): String {
            if (this == null) return "unknown error"
            val summary = listOfNotNull(error, errorDescription).joinToString(": ").ifEmpty { message ?: "error $code" }
            // General errors (network, TLS, JSON) wrap the exception that explains them.
            val reason = cause?.let { "${it.javaClass.simpleName}: ${it.message}" }
            return if (reason != null && type == AuthorizationException.TYPE_GENERAL_ERROR) "$summary ($reason)" else summary
        }

        /** Rejected refresh tokens need a new sign-in; network trouble is transient. */
        private fun AuthorizationException.toIoException(): IOException = when (type) {
            AuthorizationException.TYPE_OAUTH_TOKEN_ERROR, AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR ->
                AuthenticationException("Sign-in expired (${describe()}); sign in again", this)
            else -> IOException("Token refresh failed: ${describe()}", this)
        }
    }
}
