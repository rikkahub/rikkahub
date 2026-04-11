package com.hefengfan.hffchat.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import com.hefengfan.hffchat.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
