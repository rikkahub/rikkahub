package me.rerere.oauth

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class OAuthHttpClientTest {
    private val client = OAuthHttpClient(OkHttpClient())

    @Test
    fun `authorization url contains pkce state scope and resources`() {
        val url = client.buildAuthorizationUrl(
            OAuthHttpClient.AuthorizationRequest(
                authorizationEndpoint = "https://auth.example.com/authorize?audience=existing",
                clientId = "client id",
                redirectUri = "http://127.0.0.1:52134/oauth/callback",
                pkce = OAuthHttpClient.Pkce(
                    verifier = "verifier",
                    challenge = "challenge",
                ),
                state = "expected-state",
                scope = "openid profile",
                resources = listOf("https://mcp.example.com", "https://api.example.com"),
                additionalParameters = mapOf("prompt" to "consent"),
            )
        ).toHttpUrl()

        assertEquals("existing", url.queryParameter("audience"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("client id", url.queryParameter("client_id"))
        assertEquals(
            "http://127.0.0.1:52134/oauth/callback",
            url.queryParameter("redirect_uri"),
        )
        assertEquals("challenge", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("expected-state", url.queryParameter("state"))
        assertEquals("openid profile", url.queryParameter("scope"))
        assertEquals(
            listOf("https://mcp.example.com", "https://api.example.com"),
            url.queryParameterValues("resource"),
        )
        assertEquals("consent", url.queryParameter("prompt"))
    }
}
