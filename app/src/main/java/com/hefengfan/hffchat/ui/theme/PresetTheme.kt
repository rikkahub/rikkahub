package com.hefengfan.hffchat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import com.hefengfan.hffchat.ui.theme.presets.AutumnThemePreset
import com.hefengfan.hffchat.ui.theme.presets.BlackThemePreset
import com.hefengfan.hffchat.ui.theme.presets.OceanThemePreset
import com.hefengfan.hffchat.ui.theme.presets.SakuraThemePreset
import com.hefengfan.hffchat.ui.theme.presets.SpringThemePreset

data class PresetTheme(
    val id: String,
    val name: @Composable () -> Unit,
    val standardLight: ColorScheme,
    val standardDark: ColorScheme,
) {
    fun getColorScheme(dark: Boolean): ColorScheme {
        return if (dark) standardDark else standardLight
    }
}

val PresetThemes by lazy {
    listOf(
        SakuraThemePreset,
        OceanThemePreset,
        SpringThemePreset,
        AutumnThemePreset,
        BlackThemePreset,
    )
}

fun findPresetTheme(id: String): PresetTheme {
    return PresetThemes.find { it.id == id } ?: SakuraThemePreset
}
