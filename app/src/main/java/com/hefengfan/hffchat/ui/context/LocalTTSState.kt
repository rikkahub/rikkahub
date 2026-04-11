package com.hefengfan.hffchat.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.hefengfan.hffchat.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
