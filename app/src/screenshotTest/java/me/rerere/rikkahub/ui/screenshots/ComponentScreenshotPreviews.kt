package me.rerere.rikkahub.ui.screenshots

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.LuneBackdrop
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.TextAvatar
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalAmoledDarkMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.LocalExtendColors
import me.rerere.rikkahub.ui.theme.LocalThemeTokenOverrides
import me.rerere.rikkahub.ui.theme.ThemeTokenParseResult
import me.rerere.rikkahub.ui.theme.Typography
import me.rerere.rikkahub.ui.theme.darkExtendColors
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.ui.theme.lightExtendColors

@Composable
private fun ScreenshotTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = findPresetTheme("lune").getColorScheme(dark = darkTheme)
    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalAmoledDarkMode provides false,
        LocalExtendColors provides if (darkTheme) darkExtendColors() else lightExtendColors(),
        LocalThemeTokenOverrides provides ThemeTokenParseResult(overrides = emptyMap()),
        LocalOverscrollFactory provides null,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            shapes = AppShapes,
            typography = Typography,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

@PreviewTest
@Preview(name = "Tag States", widthDp = 280, heightDp = 220)
@Composable
internal fun TagStatesScreenshotPreview() {
    ScreenshotTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            LuneBackdrop()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Status Tags",
                    style = MaterialTheme.typography.titleMedium,
                )
                Tag(type = TagType.SUCCESS) { Text("Build passing") }
                Tag(type = TagType.ERROR) { Text("Deploy blocked") }
                Tag(type = TagType.WARNING) { Text("Review pending") }
                Tag(type = TagType.INFO) { Text("Nightly benchmark") }
                Tag(type = TagType.DEFAULT) { Text("Default") }
            }
        }
    }
}

@PreviewTest
@Preview(name = "Card Group Dark", widthDp = 360, heightDp = 420)
@Composable
internal fun CardGroupScreenshotPreview() {
    ScreenshotTheme(darkTheme = true) {
        Box(modifier = Modifier.fillMaxSize()) {
            LuneBackdrop()
            CardGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                title = { Text("Settings") },
            ) {
                item(
                    headlineContent = { Text("Display") },
                    supportingContent = { Text("Typography, blur, and message layout") },
                )
                item(
                    headlineContent = { Text("Models") },
                    supportingContent = { Text("Providers, API keys, and routing") },
                    trailingContent = { Text("4") },
                )
                item(
                    headlineContent = { Text("Scheduled Tasks") },
                    supportingContent = { Text("Automation and reminders") },
                    trailingContent = { Text("On") },
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "Text Avatar Row", widthDp = 300, heightDp = 140)
@Composable
internal fun TextAvatarScreenshotPreview() {
    ScreenshotTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            LuneBackdrop()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Conversation Identities",
                    style = MaterialTheme.typography.titleMedium,
                )
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextAvatar(text = "Alice")
                    TextAvatar(text = "Builder", color = MaterialTheme.colorScheme.tertiaryContainer)
                    TextAvatar(text = "Codex", color = MaterialTheme.colorScheme.primaryContainer)
                    TextAvatar(text = "Zen")
                }
            }
        }
    }
}
