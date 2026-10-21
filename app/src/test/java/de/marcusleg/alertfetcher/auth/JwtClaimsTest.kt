package de.marcusleg.alertfetcher.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class JwtClaimsTest {
    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"RS256"}""".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test
    fun `reads expiry and the friendliest identity`() {
        val claims = JwtClaims.parse(jwt("""{"sub":"1234","email":"alice@example.com","preferred_username":"alice","exp":1792000000}"""))!!
        assertEquals("alice", claims.identity)
        assertEquals(1_792_000_000_000L, claims.expiresAt)
    }

    @Test
    fun `falls back to sub`() {
        assertEquals("1234", JwtClaims.parse(jwt("""{"sub":"1234"}"""))!!.identity)
    }

    @Test
    fun `garbage is not a jwt`() {
        assertNull(JwtClaims.parse(null))
        assertNull(JwtClaims.parse("opaque-token"))
        assertNull(JwtClaims.parse("a.b.c"))
    }
}
