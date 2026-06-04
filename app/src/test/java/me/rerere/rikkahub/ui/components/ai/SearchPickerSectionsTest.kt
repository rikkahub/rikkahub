package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the empty search-settings sheet.
 *
 * Bug: a model whose family does not support server-side built-in search (e.g. Claude)
 * could still have [BuiltInTools.Search] toggled on in provider settings. The sheet then
 * hid the provider list (flag present) but also hid the built-in toggle (wrong family),
 * rendering nothing. The invariant below is the guard.
 */
class SearchPickerSectionsTest {

    private fun model(id: String, search: Boolean) = Model(
        modelId = id,
        tools = if (search) setOf(BuiltInTools.Search) else emptySet(),
    )

    @Test
    fun claudeWithBuiltInSearchFlagStillShowsProviderList() {
        // The reported bug: Claude Opus with Search flag set must NOT yield an empty sheet.
        val s = searchSheetSections(model("claude-opus-4-8", search = true))
        assertFalse("Claude does not support built-in search; no toggle", s.showBuiltInToggle)
        assertTrue("provider list is the fallback and must show", s.showProviderList)
    }

    @Test
    fun geminiShowsToggleAndProviderListWhenSearchOff() {
        val s = searchSheetSections(model("gemini-2.5-pro", search = false))
        assertTrue(s.showBuiltInToggle)
        assertTrue(s.showProviderList)
    }

    @Test
    fun geminiHidesProviderListWhenBuiltInSearchOn() {
        val s = searchSheetSections(model("gemini-2.5-pro", search = true))
        assertTrue(s.showBuiltInToggle)
        assertFalse("built-in search active hides the provider list", s.showProviderList)
    }

    @Test
    fun gptHidesProviderListWhenBuiltInSearchOn() {
        val s = searchSheetSections(model("gpt-4o", search = true))
        assertTrue(s.showBuiltInToggle)
        assertFalse(s.showProviderList)
    }

    @Test
    fun plainModelShowsOnlyProviderList() {
        val s = searchSheetSections(model("mimo-v2.5-pro", search = false))
        assertFalse(s.showBuiltInToggle)
        assertTrue(s.showProviderList)
    }

    @Test
    fun nullModelShowsProviderList() {
        val s = searchSheetSections(null)
        assertFalse(s.showBuiltInToggle)
        assertTrue(s.showProviderList)
    }

    @Test
    fun sheetIsNeverEmptyForAnyModel() {
        val ids = listOf(
            "gemini-2.5-pro", "gemini-3-pro", "gpt-4o", "gpt-5.5",
            "claude-opus-4-8", "claude-sonnet-4-6", "mimo-v2.5-pro", "", "unknown-model-x",
        )
        for (id in ids) {
            for (search in listOf(true, false)) {
                val s = searchSheetSections(model(id, search))
                assertTrue(
                    "sheet rendered empty for modelId='$id' search=$search",
                    s.showBuiltInToggle || s.showProviderList,
                )
            }
        }
        // null model is also covered by the never-empty invariant.
        searchSheetSections(null).let {
            assertTrue(it.showBuiltInToggle || it.showProviderList)
        }
    }
}
