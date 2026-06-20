package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.rikkahub.data.datastore.Settings

// NOT staticCompositionLocalOf: Settings changes on every toggle/edit (a new
// instance per copy()), and a static local recomposes the WHOLE provider subtree
// on any change with no skipping. compositionLocalOf tracks read sites, so only
// actual readers of LocalSettings.current invalidate when Settings changes.
val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
