package me.rerere.oauth

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.Executors

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

    // 令牌端点依次返回给定的响应，记录收到的请求
    private fun deviceTokenClient(vararg responses: Pair<Int, String>, requests: MutableList<Request>) =
        OAuthHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests += chain.request()
                    val (code, body) = responses[requests.size - 1]
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        )

    private val authorization = OAuthHttpClient.DeviceAuthorization(
        deviceCode = "device",
        userCode = "ABCD-EFGH",
        verificationUri = "https://auth.example.com/device",
        expiresIn = 600,
        interval = 5,
    )

    private val deviceTokenRequest = OAuthHttpClient.DeviceTokenRequest(
        tokenEndpoint = "https://auth.example.com/token",
        clientId = "client",
        deviceCode = "device",
    )

    @Test
    fun `device token polls until authorization completes and slows down on request`() = runTest {
        val requests = mutableListOf<Request>()
        val client = deviceTokenClient(
            400 to """{"error":"authorization_pending"}""",
            400 to """{"error":"slow_down"}""",
            200 to """{"access_token":"access","refresh_token":"refresh","expires_in":3600}""",
            requests = requests,
        )

        val token = client.awaitDeviceToken(deviceTokenRequest, authorization)

        assertEquals("access", token.accessToken)
        assertEquals(3, requests.size)
        // 5 + 5 + (5 + 5)：slow_down 之后间隔加 5 秒
        assertEquals(20_000L, currentTime)
        assertEquals(
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&device_code=device&client_id=client",
            requests.first().bodyString(),
        )
    }

    @Test
    fun `device token reports denial`() = runTest {
        val client = deviceTokenClient(400 to """{"error":"access_denied"}""", requests = mutableListOf())

        val error = runCatching { client.awaitDeviceToken(deviceTokenRequest, authorization) }.exceptionOrNull()

        assertEquals("access_denied", (error as OAuthHttpClient.DeviceAuthorizationException).error)
    }

    @Test
    fun `device token gives up when the code expires`() = runTest {
        val requests = mutableListOf<Request>()
        val client = deviceTokenClient(
            *Array(3) { 400 to """{"error":"authorization_pending"}""" },
            requests = requests,
        )

        val error = runCatching {
            client.awaitDeviceToken(deviceTokenRequest, authorization.copy(expiresIn = 15))
        }.exceptionOrNull()

        assertEquals("expired_token", (error as OAuthHttpClient.DeviceAuthorizationException).error)
        assertEquals(3, requests.size)
    }

    @Test
    fun `device authorization request sends client id and scope`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = deviceTokenClient(
            200 to """{"device_code":"d","user_code":"U","verification_uri":"https://x/device",""" +
                """"verification_uri_complete":"https://x/device?code=U","expires_in":900,"interval":7}""",
            requests = requests,
        )

        val result = client.requestDeviceAuthorization(
            OAuthHttpClient.DeviceAuthorizationRequest(
                deviceAuthorizationEndpoint = "https://auth.example.com/device/code",
                clientId = "client",
                scope = "openid offline_access",
            )
        )

        assertEquals("client_id=client&scope=openid+offline_access", requests.single().bodyString())
        assertEquals("https://x/device?code=U", result.verificationUriComplete)
        assertEquals(7L, result.interval)
        assertEquals(900L, result.expiresIn)
    }

    @Test
    fun `device token response body is not read on the caller thread`() = runBlocking {
        var readThread: Thread? = null
        val client = OAuthHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("")
                        .body(object : ResponseBody() {
                            override fun contentType() = "application/json".toMediaType()
                            override fun contentLength() = -1L
                            override fun source(): BufferedSource {
                                readThread = Thread.currentThread()
                                return Buffer().writeUtf8("""{"access_token":"access"}""")
                            }
                        })
                        .build()
                }
                .build()
        )
        // 模拟主线程：登录可能从主线程调度器发起
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()

        main.use {
            withContext(it) {
                client.awaitDeviceToken(deviceTokenRequest, authorization.copy(interval = 1))
            }
        }

        assertNotEquals("fake-main", readThread?.name)
    }
}
