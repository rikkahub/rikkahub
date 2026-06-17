package me.rerere.common.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonExpressionTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    // The built-in OpenRouter provider's balance is computed from this expression
    // (DefaultProviders: apiPath="/credits", resultPath="data.total_credits - data.total_usage").
    // Locks the arithmetic-on-nested-fields path so the balance widget can't silently regress.
    @Test
    fun `openrouter credits expression yields remaining balance`() {
        val body = obj("""{"data":{"total_credits":10.0,"total_usage":3.5}}""")

        assertTrue(isJsonExprValid("data.total_credits - data.total_usage"))
        assertEquals(
            6.5,
            body.getByKey("data.total_credits - data.total_usage").toDouble(),
            1e-6,
        )
    }

    @Test
    fun `plain nested key extraction`() {
        val body = obj("""{"data":{"total_usage":3.5}}""")
        assertEquals(3.5, body.getByKey("data.total_usage").toDouble(), 1e-6)
    }
}
