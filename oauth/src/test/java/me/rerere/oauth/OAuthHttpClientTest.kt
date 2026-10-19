package me.rerere.oauth

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class OAuthHttpClientTest {
    private val client = OAuthHttpClient(OkHttpClient())

    // 拦截请求并返回固定的令牌响应，不走网络
    private fun capturingClient(onRequest: (Request) -> Unit) = OAuthHttpClient(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                onRequest(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"access_token":"new-access","refresh_token":"new-refresh","id_token":"id"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
    )

    private fun Request.bodyString(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    @Test
    fun `refresh token uses form encoding by default`() = runBlocking {
        var request: Request? = null
        capturingClient { request = it }.refreshToken(
            OAuthHttpClient.RefreshTokenRequest(
                tokenEndpoint = "https://auth.example.com/token",
                clientId = "client",
                refreshToken = "refresh",
            )
        )

        assertEquals("application/x-www-form-urlencoded", request!!.body!!.contentType().toString())
        assertEquals("grant_type=refresh_token&refresh_token=refresh&client_id=client", request!!.bodyString())
    }

    @Test
    fun `refresh token can use json encoding`() = runBlocking {
        var request: Request? = null
        val token = capturingClient { request = it }.refreshToken(
            OAuthHttpClient.RefreshTokenRequest(
                tokenEndpoint = "https://auth.example.com/token",
                clientId = "client",
                refreshToken = "refresh",
                encoding = OAuthHttpClient.TokenEncoding.JSON,
            )
        )

        assertEquals("application", request!!.body!!.contentType()!!.type)
        assertEquals("json", request!!.body!!.contentType()!!.subtype)
        val body = Json.parseToJsonElement(request!!.bodyString()).jsonObject
        assertEquals("refresh_token", body["grant_type"]!!.jsonPrimitive.content)
        assertEquals("refresh", body["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("client", body["client_id"]!!.jsonPrimitive.content)
        assertEquals("new-access", token.accessToken)
        assertEquals("new-refresh", token.refreshToken)
        assertEquals("id", token.idToken)
    }

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
