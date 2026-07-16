package me.rerere.rikkahub.data.sync.cloud

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.theme.CustomTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncableSettingsThemeTest {
    @Test
    fun extractAndApply_roundTripsCompleteThemeSelection() {
        val custom = CustomTheme(
            id = "cloud-blue",
            name = "Cloud blue",
            primaryColorArgb = 0xFF2563EB,
        )
        val source = Settings(
            dynamicColor = false,
            themeId = custom.id,
            customThemes = listOf(custom),
        )
        val extracted = SyncableSettings.extract(source)

        var restored = Settings(dynamicColor = true)
        SyncableSettings.THEME_KEYS.forEach { key ->
            restored = SyncableSettings.applyKey(restored, key, extracted.getValue(key))
        }

        assertFalse(restored.dynamicColor)
        assertEquals(custom.id, restored.themeId)
        assertEquals(listOf(custom), restored.customThemes)
        assertTrue(SyncableSettings.CUSTOM_THEMES in SyncableSettings.ALL_KEYS)
    }
}
