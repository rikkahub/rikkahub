package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import java.io.File

val LocalChatFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun ChatFontProvider(
    displaySetting: DisplaySetting,
    content: @Composable () -> Unit,
) {
    val chatFontFamily = rememberChatFontFamily(displaySetting)
    CompositionLocalProvider(LocalChatFontFamily provides chatFontFamily) {
        content()
    }
}

@Composable
fun rememberChatFontFamily(displaySetting: DisplaySetting): FontFamily {
    val context = LocalContext.current
    return remember(
        displaySetting.chatFontFamily,
        displaySetting.chatCustomFontPath,
    ) {
        displaySetting.resolveChatFontFamily(context)
    }
}

fun DisplaySetting.resolveChatFontFamily(context: Context): FontFamily = when (chatFontFamily) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> loadCustomFontFamily(context, chatCustomFontPath) ?: FontFamily.Default
}

private fun loadCustomFontFamily(context: Context, relativePath: String): FontFamily? {
    val file = resolveFilesDirFile(context, relativePath) ?: return null
    if (!file.isFile) return null
    return runCatching {
        Typeface.createFromFile(file)
        FontFamily(Font(file))
    }.getOrNull()
}

private fun resolveFilesDirFile(context: Context, relativePath: String): File? {
    if (relativePath.isBlank()) return null
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return null
    return file.takeIf { it.path.startsWith("${filesDir.path}${File.separator}") }
}
