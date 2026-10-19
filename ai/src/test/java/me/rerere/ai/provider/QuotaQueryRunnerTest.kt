package me.rerere.ai.provider

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaQueryRunnerTest {
    private var captured: Request? = null

    private fun client(body: String, code: Int = 200) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            captured = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    @Test
    fun `template variables are replaced and unknown ones are rejected`() {
        val vars = mapOf("baseUrl" to "https://api.example.com/v1", "apiKey" to "sk-1")

        assertEquals(
            "https://api.example.com/v1/credits?key=sk-1",
            renderQuotaTemplate("{{baseUrl}}/credits?key={{ apiKey }}") { vars[it] },
        )
        assertThrows(IllegalArgumentException::class.java) {
            renderQuotaTemplate("{{baseUrl}}/{{privateKey}}") { vars[it] }
        }
    }

    @Test
    fun `query renders url and headers with one key and overrides provider headers`() = runTest {
        val setting = ProviderSetting.OpenAI(
            baseUrl = "https://api.example.com/v1",
            customHeaders = listOf(CustomHeader("Authorization", "provider"), CustomHeader("X-Extra", "1")),
        )
        val query = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/credits?key={{apiKey}}",
            headers = listOf(CustomHeader("Authorization", "Bearer {{apiKey}}"), CustomHeader(" ", "ignored")),
            resultPath = "data.total",
        )
        var keys = 0

        val quota = client("""{"data":{"total":12.5}}""").runQuotaQuery(setting.copy(quotaQuery = query)) {
            keys++
            "sk-$keys"
        }

        val request = captured!!
        assertEquals("https://api.example.com/v1/credits?key=sk-1", request.url.toString())
        assertEquals(listOf("Bearer sk-1"), request.headers("Authorization"))
        assertEquals("1", request.header("X-Extra"))
        assertEquals("GET", request.method)
        assertEquals(1, keys)
        assertEquals(ProviderQuota.Balance("%.2f".format(12.5f)), quota)
    }

    @Test
    fun `non numeric result is returned as is`() = runTest {
        val setting = ProviderSetting.Claude()
        val query = setting.quotaQuery.copy(enabled = true, url = "https://x.example.com/me", resultPath = "plan")

        val quota = client("""{"plan":"unlimited"}""").runQuotaQuery(setting.copy(quotaQuery = query)) { "key" }

        assertEquals(ProviderQuota.Balance("unlimited"), quota)
        assertEquals("key", captured!!.header("x-api-key"))
    }

    @Test
    fun `unsuccessful response fails with status and body`() = runTest {
        val setting = ProviderSetting.OpenAI(quotaQuery = QuotaQuery(enabled = true))
        val error = runCatching {
            client("""{"error":"denied"}""", code = 401).runQuotaQuery(setting) { "key" }
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("401"))
        assertTrue(error?.message.orEmpty().contains("denied"))
    }

    @Test
    fun `supportsQuota follows the query switch and the account sign in`() {
        assertFalse(ProviderSetting.OpenAI().supportsQuota)
        assertTrue(ProviderSetting.OpenAI(quotaQuery = QuotaQuery(enabled = true)).supportsQuota)
        assertFalse(ProviderSetting.ChatGPT().supportsQuota)
        assertTrue(ProviderSetting.ChatGPT(credential = OAuthCredential(accessToken = "a")).supportsQuota)
    }

    @Test
    fun `disabled query sends no request`() = runTest {
        val quota = client("{}").runQuotaQuery(ProviderSetting.OpenAI()) { "key" }

        assertNull(quota)
        assertNull(captured)
    }
}
