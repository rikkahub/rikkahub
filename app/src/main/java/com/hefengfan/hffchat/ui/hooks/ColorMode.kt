package com.hefengfan.hffchat.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hefengfan.hffchat.ui.theme.ColorMode

@Composable
fun rememberColorMode(): MutableState<ColorMode> {
    var colorModeValue by rememberSharedPreferenceString("colorMode", "SYSTEM")
    val colorMode by remember(colorModeValue) {
        derivedStateOf {
            ColorMode.entries.firstOrNull { it.name == colorModeValue } ?: ColorMode.SYSTEM
        }
    }
    return remember {
        object : MutableState<ColorMode> {
            override var value: ColorMode
                get() = colorMode
                set(value) {
                    colorModeValue = value.name
                }

            override fun component1(): ColorMode = value

            override fun component2(): (ColorMode) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberAmoledDarkMode(): MutableState<Boolean> {
    return rememberSharedPreferenceBoolean("amoledDark", false)
}
