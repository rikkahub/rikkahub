package me.rerere.rikkahub.data.sync.importer

import me.rerere.ai.provider.GoogleAccessMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CherryStudioProviderImporterTest {
    @Test
    fun `importProviders should map gemini and vertexai to expected Google access modes`() {
        val file = createCherryStudioBackup(
            providersJson = """
                [
                  {
                    "type": "gemini",
                    "name": "Gemini",
                    "apiKey": "gem-key",
                    "apiHost": "https://generativelanguage.googleapis.com",
                    "models": []
                  },
                  {
                    "type": "vertexai",
                    "name": "Vertex",
                    "apiKey": "vertex-key",
                    "apiHost": "https://aiplatform.googleapis.com",
                    "models": []
                  }
                ]
            """.trimIndent()
        )

        val providers = CherryStudioProviderImporter.importProviders(file)
        val googleProviders = providers.filterIsInstance<ProviderSetting.Google>()

        assertEquals(2, googleProviders.size)

        val gemini = googleProviders.first { it.name == "Gemini" }
        assertEquals(GoogleAccessMode.GEMINI_API, gemini.accessMode)
        assertEquals(GoogleAccessMode.GEMINI_API, gemini.resolvedAccessMode())
        assertEquals(false, gemini.vertexAI)
        assertEquals("https://generativelanguage.googleapis.com/v1beta", gemini.baseUrl)

        val vertex = googleProviders.first { it.name == "Vertex" }
        assertEquals(GoogleAccessMode.VERTEX_API_KEY, vertex.accessMode)
        assertEquals(GoogleAccessMode.VERTEX_API_KEY, vertex.resolvedAccessMode())
        assertEquals(true, vertex.vertexAI)
        assertEquals("https://aiplatform.googleapis.com/v1beta", vertex.baseUrl)
    }

    @Test
    fun `importProviders should keep known model metadata from registry`() {
        val file = createCherryStudioBackup(
            providersJson = """
                [
                  {
                    "type": "gemini",
                    "name": "Gemini",
                    "apiKey": "gem-key",
                    "apiHost": "https://generativelanguage.googleapis.com",
                    "models": [
                      {
                        "id": "gemini-2.5-flash",
                        "name": "Gemini Flash"
                      }
                    ]
                  }
                ]
            """.trimIndent()
        )

        val provider = CherryStudioProviderImporter.importProviders(file)
            .filterIsInstance<ProviderSetting.Google>()
            .single()

        assertEquals(1, provider.models.size)
        assertEquals("gemini-2.5-flash", provider.models.single().modelId)
        assertTrue(provider.models.single().abilities.isNotEmpty())
    }

    private fun createCherryStudioBackup(providersJson: String): File {
        val llm = """{"providers":$providersJson}"""
        val persisted = """{"llm":${JsonInstant.encodeToString(llm)}}"""
        val root = """{"localStorage":{"persist:cherry-studio":${JsonInstant.encodeToString(persisted)}}}"""

        return File.createTempFile("cherry-studio", ".zip").apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(root.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
