package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.ui.toast.ToasterState

val LocalToaster = staticCompositionLocalOf<ToasterState> { error("Not provided") }