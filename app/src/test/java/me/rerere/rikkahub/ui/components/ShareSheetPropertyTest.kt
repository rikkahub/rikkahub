package me.rerere.rikkahub.ui.components

import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.components.ui.encodeForShare
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TARGET 2: encodeForShare/decodeProviderSetting roundtrip-MINUS-models.
 *
 * encodeForShare deliberately strips the model list (copyProvider(models = emptyList())) — a shared
 * provider config never carries the sender's models. The generators produce NON-EMPTY model lists
 * so the strip is actually exercised.
 *
 * Property: decodeProviderSetting(x.encodeForShare()) == x.copyProvider(models = emptyList()).
 *
 * After the Compose-leak fix (#242), ProviderSetting carries no presentation lambdas, so equals
 * compares only persisted state — the assertion holds directly with no normalization.
 */
class ShareSheetPropertyTest {

    @Test
    fun `encodeForShare then decode equals the provider with models stripped`() {
        runBlocking {
            checkAll(200, ProviderSettingTestArb.arbProviderSetting) { x ->
                val decoded = decodeProviderSetting(x.encodeForShare())
                val expected = x.copyProvider(models = emptyList())
                assertEquals(expected, decoded)
            }
        }
    }
}
