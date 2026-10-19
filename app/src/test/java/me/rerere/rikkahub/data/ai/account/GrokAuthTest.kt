package me.rerere.rikkahub.data.ai.account

import me.rerere.ai.provider.OAuthCredential
import me.rerere.oauth.OAuthHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class GrokAuthTest {
    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"none"}""", payload, "signature")
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) }
    }

    @Test
    fun `login token takes account id from sub and email from id token`() {
        val credential = GrokAuth.toCredential(
            token = OAuthHttpClient.TokenResponse(
                accessToken = jwt("""{"sub":"user-1","exp":1700000000}"""),
                refreshToken = "refresh",
                idToken = jwt("""{"sub":"user-1","email":"grok@example.com"}"""),
            ),
            previous = null,
        )

        assertEquals("user-1", credential.accountId)
        assertEquals("grok@example.com", credential.email)
        assertEquals("refresh", credential.refreshToken)
        assertEquals(1_700_000_000_000L, credential.expiresAt)
        assertNull(credential.plan)
    }

    @Test
    fun `refresh without new refresh token or id token keeps previous values`() {
        val previous = OAuthCredential(
            accessToken = "old",
            refreshToken = "old-refresh",
            accountId = "user-1",
            email = "grok@example.com",
        )

        val credential = GrokAuth.toCredential(
            token = OAuthHttpClient.TokenResponse(accessToken = "opaque-token", expiresIn = 3600),
            previous = previous,
            now = 1_000L,
        )

        assertEquals("opaque-token", credential.accessToken)
        assertEquals("old-refresh", credential.refreshToken)
        assertEquals("user-1", credential.accountId)
        assertEquals("grok@example.com", credential.email)
        assertEquals(1_000L + 3_600_000L, credential.expiresAt)
    }

    @Test
    fun `refresh uses form encoding and device login asks for grok cli scopes`() {
        assertEquals(OAuthHttpClient.TokenEncoding.FORM, GrokAuth.refreshRequest("r").encoding)
        assertEquals(
            "openid profile email offline_access grok-cli:access api:access",
            GrokAuth.deviceAuthorizationRequest().scope,
        )
        assertEquals("https://auth.x.ai/oauth2/token", GrokAuth.deviceTokenRequest("d").tokenEndpoint)
    }
}
