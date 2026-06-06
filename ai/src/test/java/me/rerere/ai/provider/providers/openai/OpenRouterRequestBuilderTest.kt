package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.OpenRouterRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRequestBuilderTest {
    @Test
    fun default_routing_emits_null() {
        assertNull(buildProviderObject(OpenRouterRouting(), hasToolsOrSchema = false))
    }

    @Test
    fun sort_only() {
        val o = buildProviderObject(OpenRouterRouting(sort = "throughput"), false)!!
        assertEquals("throughput", o["sort"]!!.jsonPrimitive.content)
        assertFalse(o.containsKey("allow_fallbacks"))
    }

    @Test
    fun order_emits_allow_fallbacks() {
        val o = buildProviderObject(
            OpenRouterRouting(order = listOf("anthropic"), allowFallbacks = false), false
        )!!
        assertEquals(listOf("anthropic"), o["order"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(o["allow_fallbacks"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun max_price() {
        val o = buildProviderObject(
            OpenRouterRouting(maxPricePrompt = 1.0, maxPriceCompletion = 2.0), false
        )!!
        val mp = o["max_price"]!!.jsonObject
        assertEquals(1.0, mp["prompt"]!!.jsonPrimitive.double, 0.0)
        assertEquals(2.0, mp["completion"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun require_parameters_forced_with_tools() {
        val o = buildProviderObject(OpenRouterRouting(), hasToolsOrSchema = true)!!
        assertTrue(o["require_parameters"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun data_collection_deny() {
        val o = buildProviderObject(OpenRouterRouting(dataCollection = "deny"), false)!!
        assertEquals("deny", o["data_collection"]!!.jsonPrimitive.content)
    }
}
