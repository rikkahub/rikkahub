package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TARGET 1: kotlinx polymorphic roundtrip for every [ProviderSetting] subtype.
 *
 * Property (Roundtrip): for any provider x, decode(encode(x)) == x, using the :ai module's
 * internal `json` instance. Covers OpenAI / Google / Claude including OAuth (authType/oauthToken/
 * oauthContext1M) and Vertex (vertexAI/privateKey/serviceAccountEmail/location/projectId) fields
 * plus a non-empty models list.
 *
 * NOTE on the @Composable description/shortDescription fields: contrary to a common assumption,
 * they are NOT excluded from data-class equals — they are constructor `val`s, only marked
 * @Transient for serialization. Their default value `{}` constructs a fresh ComposableLambdaImpl
 * each time, so original and decoded never compare equal on those two identity-based fields. They
 * carry no persisted state (not serialized), so the meaningful roundtrip is over everything else.
 * We normalize both sides to a single shared lambda before comparing, exactly as the persisted
 * contract intends. (The existing ShareSheetTest sidesteps this by asserting fields individually.)
 */
class ProviderSettingRoundtripTest {

    private val sharedLambda: @Composable () -> Unit = {}

    private fun ProviderSetting.normalizeLambdas(): ProviderSetting =
        copyProvider(description = sharedLambda, shortDescription = sharedLambda)

    @Test
    fun `ProviderSetting survives kotlinx encode-decode roundtrip`() {
        runBlocking {
            checkAll(200, ProviderSettingArbs.arbProviderSetting) { x ->
                val encoded = json.encodeToString<ProviderSetting>(x)
                val decoded = json.decodeFromString<ProviderSetting>(encoded)
                assertEquals(x.normalizeLambdas(), decoded.normalizeLambdas())
            }
        }
    }
}
