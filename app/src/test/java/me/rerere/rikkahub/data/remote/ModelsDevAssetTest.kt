package me.rerere.rikkahub.data.remote

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelsDevCatalog
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelsDevAssetTest {
    @Test
    fun bundledCatalogIsReadable() {
        val catalog = File("src/main/assets/models_dev.json").inputStream().use { ModelsDevCatalog.decode(it) }
        assertTrue(catalog.size > 1000)
        assertTrue(Modality.IMAGE in catalog.find("gpt-4o")!!.inputModalities)
        assertTrue(ModelAbility.TOOL in catalog.find("anthropic/claude-sonnet-4.5")!!.abilities)
    }
}
