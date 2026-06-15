package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Test

class A2aRouteSecurityTest {

    @Test
    fun `disabled a2a exposes no usable card or rpc`() {
        assertEquals(
            A2aAccessResult.DISABLED,
            evaluateA2aAccess(enabled = false, jwtProtectedAtStartup = true, serverLocalhostOnly = false),
        )
    }

    @Test
    fun `jwt disabled lan exposure is forbidden`() {
        assertEquals(
            A2aAccessResult.FORBIDDEN,
            evaluateA2aAccess(enabled = true, jwtProtectedAtStartup = false, serverLocalhostOnly = false),
        )
    }

    @Test
    fun `jwt enabled permits protected a2a routes`() {
        assertEquals(
            A2aAccessResult.ALLOWED,
            evaluateA2aAccess(enabled = true, jwtProtectedAtStartup = true, serverLocalhostOnly = false),
        )
    }

    @Test
    fun `localhost only permits jwt disabled a2a routes`() {
        assertEquals(
            A2aAccessResult.ALLOWED,
            evaluateA2aAccess(enabled = true, jwtProtectedAtStartup = false, serverLocalhostOnly = true),
        )
    }
}
