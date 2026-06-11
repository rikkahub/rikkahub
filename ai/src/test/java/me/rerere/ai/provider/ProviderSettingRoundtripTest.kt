package me.rerere.ai.provider

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
 * Regression for the Compose-leak fix (#242): ProviderSetting carries no presentation glue any
 * more, so its data-class equals compares only persisted state. The roundtrip therefore holds
 * directly with NO lambda normalization. This assertion cannot compile on the pre-fix model
 * (copyProvider no longer takes description/shortDescription) and would not hold either, since the
 * removed presentation lambda fields produced a fresh identity-based value on every construction.
 */
class ProviderSettingRoundtripTest {

    @Test
    fun `ProviderSetting survives kotlinx encode-decode roundtrip`() {
        runBlocking {
            checkAll(200, ProviderSettingArbs.arbProviderSetting) { x ->
                val encoded = json.encodeToString<ProviderSetting>(x)
                val decoded = json.decodeFromString<ProviderSetting>(encoded)
                assertEquals(x, decoded)
            }
        }
    }
}
