package com.hefengfan.hffchat.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import com.dokar.sonner.ToasterState

val LocalToaster = staticCompositionLocalOf<ToasterState> { error("Not provided") }