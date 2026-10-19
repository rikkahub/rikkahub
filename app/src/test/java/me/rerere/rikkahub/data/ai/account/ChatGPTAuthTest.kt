package me.rerere.rikkahub.data.ai.account

import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.oauth.OAuthHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Base64

class ChatGPTAuthTest {
    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"none"}""", payload, "signature")
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) }
    }

    private val idToken = jwt(
        """
        {
          "email": "user@example.com",
          "https://api.openai.com/auth": {
            "chatgpt_account_id": "account-1",
            "chatgpt_plan_type": "plus"
          }
        }
        """.trimIndent()
    )
    private val accessToken = jwt("""{"exp": 1700000000}""")

    @Test
    fun `login token is converted with account info from id token`() {
        val credential = ChatGPTAuth.toCredential(
            token = OAuthHttpClient.TokenResponse(
                accessToken = accessToken,
                refreshToken = "refresh-1",
                idToken = idToken,
            ),
            previous = null,
        )

        assertEquals(accessToken, credential.accessToken)
        assertEquals("refresh-1", credential.refreshToken)
        // 没有 expires_in 时取 access token 的 exp
        assertEquals(1_700_000_000_000L, credential.expiresAt)
        assertEquals("account-1", credential.accountId)
        assertEquals("user@example.com", credential.email)
        assertEquals("plus", credential.plan)
    }

    @Test
    fun `expires_in takes precedence over jwt exp`() {
        val credential = ChatGPTAuth.toCredential(
            token = OAuthHttpClient.TokenResponse(accessToken = accessToken, expiresIn = 3600),
            previous = null,
            now = 1_000L,
        )

        assertEquals(1_000L + 3_600_000L, credential.expiresAt)
    }

    @Test
    fun `refresh without id token or refresh token keeps previous values`() {
        val previous = OAuthCredential(
            accessToken = "old-access",
            refreshToken = "old-refresh",
            accountId = "account-1",
            email = "user@example.com",
            plan = "pro",
        )

        val credential = ChatGPTAuth.toCredential(
            token = OAuthHttpClient.TokenResponse(accessToken = "opaque-access"),
            previous = previous,
        )

        assertEquals("opaque-access", credential.accessToken)
        assertEquals("old-refresh", credential.refreshToken)
        assertEquals(0L, credential.expiresAt)
        assertEquals("account-1", credential.accountId)
        assertEquals("user@example.com", credential.email)
        assertEquals("pro", credential.plan)
    }

    @Test
    fun `plan display names follow codex`() {
        assertEquals("Plus", ChatGPTAuth.planDisplayName("plus"))
        assertEquals("Pro", ChatGPTAuth.planDisplayName("prolite"))
        assertEquals("Pro (More)", ChatGPTAuth.planDisplayName("pro"))
        assertEquals("Pro (Max)", ChatGPTAuth.planDisplayName("PROMAX"))
        assertEquals("Enterprise", ChatGPTAuth.planDisplayName("hc"))
        assertEquals("Edu", ChatGPTAuth.planDisplayName("education"))
        assertEquals("Self Serve Business Usage Based", ChatGPTAuth.planDisplayName("self_serve_business_usage_based"))
        // 不认识的值按单词首字母大写
        assertEquals("Free Workspace", ChatGPTAuth.planDisplayName("free_workspace"))
    }

    @Test
    fun `malformed jwt yields null payload`() {
        assertNull(decodeJwtPayload("not-a-jwt"))
        assertNull(decodeJwtPayload("a.!!!.c"))
    }

    @Test
    fun `refresh request uses json encoding`() {
        val request = ChatGPTAuth.refreshRequest("refresh-1")

        assertEquals(OAuthHttpClient.TokenEncoding.JSON, request.encoding)
        assertEquals(ChatGPTAuth.CLIENT_ID, request.clientId)
        assertEquals(ChatGPTAuth.TOKEN_ENDPOINT, request.tokenEndpoint)
    }

    @Test
    fun `authorization url matches codex cli parameters`() {
        val client = OAuthHttpClient(okhttp3.OkHttpClient())
        val url = client.buildAuthorizationUrl(
            ChatGPTAuth.authorizationRequest(
                pkce = OAuthHttpClient.Pkce(verifier = "v", challenge = "c"),
                state = "s",
                redirectUri = "http://127.0.0.1:1455/auth/callback",
            )
        )

        assertEquals(
            "https://auth.openai.com/oauth/authorize?response_type=code" +
                "&client_id=app_EMoamEEZ73f0CkXaXp7hrann" +
                "&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback" +
                "&code_challenge=c&code_challenge_method=S256&state=s" +
                "&scope=openid%20profile%20email%20offline_access" +
                "&id_token_add_organizations=true&codex_cli_simplified_flow=true&originator=codex_cli_rs",
            url,
        )
    }

    @Test
    fun `ui edits keep the stored credential`() {
        val stored = ProviderSetting.ChatGPT(credential = OAuthCredential(accessToken = "rotated"))
        val edited = stored.copy(name = "Renamed", credential = OAuthCredential(accessToken = "stale"))

        val merged = edited.preserveCredential(stored) as ProviderSetting.ChatGPT

        assertEquals("Renamed", merged.name)
        assertEquals("rotated", merged.credential?.accessToken)
    }

    @Test
    fun `preserveCredential leaves api key providers untouched`() {
        val provider = ProviderSetting.OpenAI(apiKey = "key")

        assertSame(provider, provider.preserveCredential(ProviderSetting.OpenAI()))
    }
}
