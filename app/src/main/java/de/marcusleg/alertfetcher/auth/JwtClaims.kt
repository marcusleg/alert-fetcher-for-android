package de.marcusleg.alertfetcher.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Reads claims from a JWT *without verifying it*. Only used for display and to know when a token
 * expires; the server is responsible for validating the tokens it receives.
 */
class JwtClaims private constructor(private val claims: JsonObject) {
    val expiresAt: Long? get() = (claims["exp"] as? JsonPrimitive)?.longOrNull?.times(1000)

    /** The most human friendly identity claim available. */
    val identity: String?
        get() = listOf("preferred_username", "email", "name", "sub")
            .firstNotNullOfOrNull { (claims[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

    companion object {
        fun parse(token: String?): JwtClaims? {
            val payload = token?.split('.')?.takeIf { it.size == 3 }?.get(1) ?: return null
            return runCatching {
                val decoded = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
                JwtClaims(Json.parseToJsonElement(decoded).jsonObject)
            }.getOrNull()
        }
    }
}
