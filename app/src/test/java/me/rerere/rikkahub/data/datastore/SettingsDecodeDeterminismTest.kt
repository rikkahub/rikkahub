package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsDecodeDeterminismTest {

    @Test
    fun `decoding the same preferences twice yields the same model ids`() {
        // Settings decode must be a pure function of the persisted Preferences. With a
        // Uuid.random() fallback, every DataStore emission of a never-configured install
        // carried fresh ids for imageGenerationModelId/ocrModelId, so consecutive Settings
        // were never equal and distinctUntilChanged on settingsFlow could not collapse them.
        val first = SettingsStore.decodeSettings(emptyPreferences())
        val second = SettingsStore.decodeSettings(emptyPreferences())

        assertEquals(first.imageGenerationModelId, second.imageGenerationModelId)
        assertEquals(first.ocrModelId, second.ocrModelId)
    }

    @Test
    fun `configured model ids decode to the persisted value`() {
        val imageModelId = Uuid.parse("11111111-2222-3333-4444-555555555555")
        val ocrModelId = Uuid.parse("66666666-7777-8888-9999-aaaaaaaaaaaa")
        val preferences = preferencesOf(
            SettingsStore.IMAGE_GENERATION_MODEL to imageModelId.toString(),
            SettingsStore.OCR_MODEL to ocrModelId.toString(),
        )

        val decoded = SettingsStore.decodeSettings(preferences)

        assertEquals(imageModelId, decoded.imageGenerationModelId)
        assertEquals(ocrModelId, decoded.ocrModelId)
    }

    @Test
    fun `old preferences decode new a2a server fields with safe defaults`() {
        val decoded = SettingsStore.decodeSettings(emptyPreferences())

        assertEquals(9000, decoded.a2aServerPort)
        assertEquals(true, decoded.a2aServerLocalhostOnly)
        assertEquals("", decoded.a2aServerToken)
    }

    @Test
    fun `configured a2a server fields decode to persisted values`() {
        val preferences = preferencesOf(
            SettingsStore.A2A_SERVER_PORT to 9100,
            SettingsStore.A2A_SERVER_LOCALHOST_ONLY to false,
            SettingsStore.A2A_SERVER_TOKEN to "static-token",
        )

        val decoded = SettingsStore.decodeSettings(preferences)

        assertEquals(9100, decoded.a2aServerPort)
        assertEquals(false, decoded.a2aServerLocalhostOnly)
        assertEquals("static-token", decoded.a2aServerToken)
    }
}
