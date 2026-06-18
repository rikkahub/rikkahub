package me.rerere.rikkahub.data.codex

import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CodexAccountTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `identity is parsed from OAuth ID token`() {
        val payload = """
            {
              "sub": "user-1",
              "email": "user@example.com",
              "name": "Test User",
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "account-1",
                "chatgpt_user_id": "user-1"
              }
            }
        """.trimIndent()
        val token = listOf("{}", payload, "signature")
            .joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray()) }

        val identity = parseCodexIdentity(token, json)

        assertEquals("account-1", identity.accountId)
        assertEquals("user-1", identity.userId)
        assertEquals("user@example.com", identity.email)
        assertEquals("Test User", identity.name)
    }

    @Test
    fun `usage JSON exposes five hour and weekly windows`() {
        val usage = parseCodexUsage(
            json.parseToJsonElement(
                """
                    {
                      "rate_limit": {
                        "primary_window": {
                          "used_percent": 25.5,
                          "limit_window_seconds": 18000,
                          "reset_at": 2000000000
                        },
                        "secondary_window": {
                          "used_percent": 70,
                          "limit_window_seconds": 604800,
                          "reset_at": 2000600000
                        }
                      }
                    }
                """.trimIndent()
            ).let { it as kotlinx.serialization.json.JsonObject }
        )

        assertEquals(300L, usage.primary?.windowMinutes)
        assertEquals(25.5, usage.primary?.usedPercent ?: 0.0, 0.0)
        assertEquals(10_080L, usage.secondary?.windowMinutes)
        assertEquals(70.0, usage.secondary?.usedPercent ?: 0.0, 0.0)
    }

    @Test
    fun `usage headers support reset after seconds`() {
        val before = System.currentTimeMillis() / 1000
        val usage = parseCodexUsage(
            Headers.headersOf(
                "x-codex-primary-used-percent", "50",
                "x-codex-primary-reset-after-seconds", "60",
            )
        )

        assertNotNull(usage)
        assertTrue(usage!!.primary!!.resetsAt!! >= before + 60)
    }

    @Test
    fun `free usage exposes monthly limit without empty secondary window`() {
        val usage = parseCodexUsage(
            json.parseToJsonElement(
                """
                    {
                      "rate_limit": {
                        "primary_window": {
                          "used_percent": 12,
                          "limit_window_seconds": 2592000
                        },
                        "secondary_window": null
                      }
                    }
                """.trimIndent()
            ).let { it as kotlinx.serialization.json.JsonObject }
        )

        assertEquals(43_200L, usage.primary?.windowMinutes)
        assertNull(usage.secondary)
    }

    @Test
    fun `round robin skips disabled invalid and exhausted accounts`() {
        val accounts = listOf(
            account("disabled", enabled = false),
            account("invalid", status = CodexTokenStatus.INVALID),
            account(
                "exhausted",
                usage = CodexUsageSnapshot(
                    primary = CodexUsageWindow(usedPercent = 100.0, resetsAt = 2_000_000_000)
                )
            ),
            account("available"),
        )

        assertEquals(3, selectCodexAccountIndex(accounts, startIndex = 0, nowMillis = 1_000))
        assertNull(selectCodexAccountIndex(accounts.dropLast(1), startIndex = 0, nowMillis = 1_000))
    }

    @Test
    fun `auto reasoning omits Codex effort`() {
        assertNull(codexReasoningEffort(ReasoningLevel.AUTO))
        assertEquals("high", codexReasoningEffort(ReasoningLevel.HIGH))
    }

    @Test
    fun `incomplete response exposes its reason`() {
        val payload = json.parseToJsonElement(
            """
                {
                  "response": {
                    "incomplete_details": {
                      "reason": "max_output_tokens"
                    }
                  }
                }
            """.trimIndent()
        ).let { it as kotlinx.serialization.json.JsonObject }

        assertEquals(
            "Codex response incomplete: max_output_tokens",
            parseCodexIncompleteMessage(payload),
        )
    }

    @Test
    fun `only authentication rejection invalidates refreshed account`() {
        assertTrue(
            isCodexRefreshAuthenticationFailure(
                statusCode = 400,
                responseBody = """{"error":"invalid_grant"}""",
                json = json,
            )
        )
        assertTrue(isCodexRefreshAuthenticationFailure(401, "", json))
        assertEquals(
            false,
            isCodexRefreshAuthenticationFailure(
                statusCode = 500,
                responseBody = """{"error":"server_error"}""",
                json = json,
            )
        )
        assertEquals(
            false,
            isCodexRefreshAuthenticationFailure(
                statusCode = 400,
                responseBody = "temporarily malformed",
                json = json,
            )
        )
    }

    private fun account(
        id: String,
        enabled: Boolean = true,
        status: CodexTokenStatus = CodexTokenStatus.AVAILABLE,
        usage: CodexUsageSnapshot? = null,
    ) = CodexAccount(
        id = id,
        name = id,
        chatgptAccountId = "workspace-$id",
        accessToken = "token",
        refreshToken = "refresh",
        expiresAt = Long.MAX_VALUE,
        enabled = enabled,
        tokenStatus = status,
        usage = usage,
    )
}
