package de.marcusleg.alertfetcher.alertmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RequestAuthenticatorTest {
    @Test
    fun `basic auth header`() {
        assertEquals("Basic YWxpY2U6c8OkY3JldDpwdw==", RequestAuthenticator.basic("alice", "säcret:pw"))
    }

    @Test
    fun `parses extra headers, skipping comments and blank lines`() {
        val parsed = RequestAuthenticator.parseExtraHeaders(
            """
            # Cloudflare Access service token
            CF-Access-Client-Id: abc.access
            CF-Access-Client-Secret:  s3cr:et

            X-Scope-OrgID: homelab
            """.trimIndent(),
        )
        assertNull(parsed.error)
        assertEquals(
            listOf("CF-Access-Client-Id" to "abc.access", "CF-Access-Client-Secret" to "s3cr:et", "X-Scope-OrgID" to "homelab"),
            parsed.headers,
        )
    }

    @Test
    fun `reports malformed extra headers`() {
        assertNotNull(RequestAuthenticator.parseExtraHeaders("no colon here").error)
        assertNotNull(RequestAuthenticator.parseExtraHeaders("Bad Name: x").error)
        assertNotNull(RequestAuthenticator.parseExtraHeaders(": empty name").error)
        assertNotNull(RequestAuthenticator.parseExtraHeaders("Host: evil.example").error)
        assertNull(RequestAuthenticator.parseExtraHeaders("").error)
    }
}
