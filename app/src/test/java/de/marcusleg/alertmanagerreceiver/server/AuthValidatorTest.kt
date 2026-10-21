package de.marcusleg.alertmanagerreceiver.server

import de.marcusleg.alertmanagerreceiver.data.AppSettings
import de.marcusleg.alertmanagerreceiver.data.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AuthValidatorTest {
    private val bearer = AppSettings(authMode = AuthMode.BEARER, bearerToken = "s3cret")
    private val basic = AppSettings(authMode = AuthMode.BASIC, basicUsername = "am", basicPassword = "pa:ss")

    private fun basicHeader(user: String, pass: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    @Test
    fun `none accepts everything`() {
        assertTrue(AuthValidator.isAuthorized(null, AppSettings(authMode = AuthMode.NONE)))
        assertNull(AuthValidator.challenge(AppSettings(authMode = AuthMode.NONE)))
    }

    @Test
    fun `bearer token`() {
        assertTrue(AuthValidator.isAuthorized("Bearer s3cret", bearer))
        assertTrue(AuthValidator.isAuthorized("bearer   s3cret ", bearer))
        assertFalse(AuthValidator.isAuthorized("Bearer wrong", bearer))
        assertFalse(AuthValidator.isAuthorized("Bearers3cret", bearer))
        assertFalse(AuthValidator.isAuthorized("Basic s3cret", bearer))
        assertFalse(AuthValidator.isAuthorized(null, bearer))
        assertFalse(AuthValidator.isAuthorized("Bearer ", bearer))
    }

    @Test
    fun `bearer fails closed with an empty token`() {
        val empty = bearer.copy(bearerToken = "")
        assertFalse(AuthValidator.isAuthorized("Bearer ", empty))
        assertFalse(AuthValidator.isAuthorized("Bearer x", empty))
    }

    @Test
    fun `basic auth, password may contain colons`() {
        assertTrue(AuthValidator.isAuthorized(basicHeader("am", "pa:ss"), basic))
        assertFalse(AuthValidator.isAuthorized(basicHeader("am", "pa"), basic))
        assertFalse(AuthValidator.isAuthorized(basicHeader("other", "pa:ss"), basic))
        assertFalse(AuthValidator.isAuthorized("Basic !!notbase64!!", basic))
        assertFalse(AuthValidator.isAuthorized("Basic " + Base64.getEncoder().encodeToString("nocolon".toByteArray()), basic))
    }

    @Test
    fun `headerFor produces headers the validator accepts`() {
        assertTrue(AuthValidator.isAuthorized(AuthValidator.headerFor(bearer), bearer))
        assertTrue(AuthValidator.isAuthorized(AuthValidator.headerFor(basic), basic))
        assertEquals("Basic realm=\"alertmanager-receiver\", charset=\"UTF-8\"", AuthValidator.challenge(basic))
    }
}
