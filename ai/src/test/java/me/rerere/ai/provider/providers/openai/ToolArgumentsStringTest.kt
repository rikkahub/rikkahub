package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolArgumentsStringTest {
    @Test
    fun `should stringify json object tool arguments`() {
        val arguments = buildJsonObject {
            put("foo", JsonPrimitive("bar"))
        }

        val str = arguments.asToolArgumentsString()
        val parsed = json.parseToJsonElement(str).jsonObject
        assertEquals("bar", parsed["foo"]?.jsonPrimitive?.content)
    }
}

