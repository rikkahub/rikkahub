package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.rikkahub.data.datastore.Settings

val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
