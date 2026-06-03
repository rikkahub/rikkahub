package me.rerere.rikkahub.ui.components

import androidx.compose.runtime.Composable
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
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
 * The @Composable description/shortDescription fields participate in data-class equals but hold no
 * persisted state (they are @Transient for serialization) and their default `{}` yields a fresh
 * ComposableLambdaImpl per construction, so original vs decoded never match on those two. Both
 * sides are normalized to one shared lambda before comparison, per the persisted contract.
 */
class ShareSheetPropertyTest {

    private val sharedLambda: @Composable () -> Unit = {}

    private fun ProviderSetting.normalizeLambdas(): ProviderSetting =
        copyProvider(description = sharedLambda, shortDescription = sharedLambda)

    @Test
    fun `encodeForShare then decode equals the provider with models stripped`() {
        runBlocking {
            checkAll(200, ProviderSettingTestArb.arbProviderSetting) { x ->
                val decoded = decodeProviderSetting(x.encodeForShare())
                val expected = x.copyProvider(models = emptyList())
                assertEquals(expected.normalizeLambdas(), decoded.normalizeLambdas())
            }
        }
    }
}
