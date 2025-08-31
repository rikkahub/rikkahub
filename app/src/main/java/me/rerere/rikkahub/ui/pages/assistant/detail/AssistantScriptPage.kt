package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun AssistantScriptsPage(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(text = "Scripts")
        }

        items(assistant.scripts) { script ->
            Column {
                Text(text = script.name)
                ScriptInput(
                    code = script.code,
                    onValueChange = { newCode ->
                        onUpdateAssistant(
                            assistant.copy(
                                scripts = assistant.scripts.map {
                                    if (it.id == script.id) it.copy(
                                        code = newCode
                                    ) else it
                                }
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ScriptInput(
    code: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = code,
        onValueChange = onValueChange,
        visualTransformation = HighlightCodeVisualTransformation(
            language = "javascript",
            highlighter = LocalHighlighter.current,
            darkMode = LocalDarkMode.current
        )
    )
}
