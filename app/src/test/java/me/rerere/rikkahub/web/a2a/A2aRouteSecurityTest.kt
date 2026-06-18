package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aRouteSecurityTest {

    @Test
    fun `static bearer policy covers the full boundary matrix`() {
        listOf(false, true).forEach { enabled ->
            listOf(false, true).forEach { localhostOnly ->
                listOf(false, true).forEach { tokenBlank ->
                    listOf(false, true).forEach { bearerMatch ->
                        val expected = when {
                            !enabled -> A2aAccessResult.DISABLED
                            localhostOnly && tokenBlank -> A2aAccessResult.ALLOWED
                            tokenBlank -> A2aAccessResult.FORBIDDEN
                            bearerMatch -> A2aAccessResult.ALLOWED
                            else -> A2aAccessResult.FORBIDDEN
                        }

                        assertEquals(
                            "enabled=$enabled localhostOnly=$localhostOnly tokenBlank=$tokenBlank bearerMatch=$bearerMatch",
                            expected,
                            evaluateA2aAccess(
                                enabled = enabled,
                                serverLocalhostOnly = localhostOnly,
                                tokenBlank = tokenBlank,
                                bearerMatch = bearerMatch,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a2a bearer token is accepted from authorization header only`() {
        assertTrue(a2aBearerMatches("Bearer static-token", "static-token"))
        assertTrue(a2aBearerMatches("bearer static-token", "static-token"))
        assertFalse(a2aBearerMatches(null, "static-token"))
        assertFalse(a2aBearerMatches("static-token", "static-token"))
        assertFalse(a2aBearerMatches("Bearer wrong-token", "static-token"))
    }
}
