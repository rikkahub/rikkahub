package me.rerere.rikkahub.ui.pages.setting.providerdetail

import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBrowserLogicTest {

    private fun model(id: String, name: String = id) = Model(modelId = id, displayName = name)

    private val catalog = listOf(
        model("gpt-4o"),
        model("gpt-4o-mini"),
        model("o3", name = "OpenAI o3"),
        model("claude-sonnet-4"),
    )

    @Test
    fun `blank query matches everything`() {
        // compare by modelId — Model has a random id per instance, so structural equality differs
        assertEquals(catalog.map { it.modelId }, filterModels(catalog, "").map { it.modelId })
        assertEquals(catalog.map { it.modelId }, filterModels(catalog, "   ").map { it.modelId })
    }

    @Test
    fun `keywords are ANDed across id and displayName, case-insensitive`() {
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), filterModels(catalog, "GPT 4o").map { it.modelId })
        // matches via displayName, not id
        assertEquals(listOf("o3"), filterModels(catalog, "openai").map { it.modelId })
        assertTrue(filterModels(catalog, "gpt claude").isEmpty())
    }

    @Test
    fun `select-all is disabled without an active filter`() {
        // The footgun guard: an unfiltered catalog of hundreds must NOT offer one-tap enable-all.
        assertFalse(canBulkEnable(query = "", filtered = catalog, enabledIds = emptySet()))
        assertFalse(canBulkEnable(query = "   ", filtered = catalog, enabledIds = emptySet()))
    }

    @Test
    fun `select-all is enabled with a filter and an unselected model present`() {
        val filtered = filterModels(catalog, "gpt")
        assertTrue(canBulkEnable(query = "gpt", filtered = filtered, enabledIds = emptySet()))
    }

    @Test
    fun `select-all applies only to the filtered set`() {
        val filtered = filterModels(catalog, "gpt")
        // Both gpt models already enabled → nothing left to enable in THIS filter, even though
        // other catalog models (o3, claude) are not enabled.
        val enabled = setOf("gpt-4o", "gpt-4o-mini")
        assertFalse(canBulkEnable(query = "gpt", filtered = filtered, enabledIds = enabled))
        assertTrue(canBulkDisable(query = "gpt", filtered = filtered, enabledIds = enabled))
    }

    @Test
    fun `disable-all requires an active filter and a fully-enabled filtered set`() {
        val filtered = filterModels(catalog, "gpt")
        assertFalse(canBulkDisable(query = "", filtered = filtered, enabledIds = setOf("gpt-4o", "gpt-4o-mini")))
        // not fully enabled → no disable-all
        assertFalse(canBulkDisable(query = "gpt", filtered = filtered, enabledIds = setOf("gpt-4o")))
        // empty filtered → no disable-all
        assertFalse(canBulkDisable(query = "zzz", filtered = emptyList(), enabledIds = emptySet()))
    }
}
