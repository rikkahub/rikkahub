package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import me.rerere.rikkahub.ui.theme.ColorMode

private const val COLOR_MODE_KEY = "colorMode"

@Composable
fun rememberColorMode(): MutableState<ColorMode> {
    val colorModeState = rememberSharedPreferenceString(COLOR_MODE_KEY, ColorMode.SYSTEM.name)
    return remember(colorModeState) {
        object : MutableState<ColorMode> {
            override var value: ColorMode
                get() = colorModeState.value.toColorMode()
                set(value) {
                    colorModeState.value = value.name
                }

            override fun component1(): ColorMode = value

            override fun component2(): (ColorMode) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberCurrentColorMode(): ColorMode {
    val colorModeValue by rememberSharedPreferenceString(COLOR_MODE_KEY, ColorMode.SYSTEM.name)
    return colorModeValue.toColorMode()
}

@Composable
fun rememberAmoledDarkMode(): MutableState<Boolean> {
    return rememberSharedPreferenceBoolean("amoledDark", false)
}

private fun String?.toColorMode(): ColorMode {
    return ColorMode.entries.firstOrNull { it.name == this } ?: ColorMode.SYSTEM
}
