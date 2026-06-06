package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Setting07
import me.rerere.rikkahub.R

@Composable
fun ConversationSystemPromptButton(
    customSystemPrompt: String?,
    onSystemPromptChange: (String?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editText by rememberSaveable(customSystemPrompt) {
        mutableStateOf(customSystemPrompt ?: "")
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(
            onClick = { expanded = !expanded },
        ) {
            Icon(
                imageVector = HugeIcons.Setting07,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = if (!customSystemPrompt.isNullOrBlank()) {
                    stringResource(R.string.chat_page_conversation_system_prompt) + " ✎"
                } else {
                    stringResource(R.string.chat_page_conversation_system_prompt)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.chat_page_conversation_system_prompt_hint)) },
                    minLines = 3,
                    maxLines = 8,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (!customSystemPrompt.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                editText = ""
                                onSystemPromptChange(null)
                            },
                        ) {
                            Text(stringResource(R.string.chat_page_conversation_system_prompt_clear))
                        }
                    }
                    TextButton(
                        onClick = {
                            onSystemPromptChange(editText.ifBlank { null })
                            expanded = false
                        },
                    ) {
                        Text(stringResource(R.string.chat_page_conversation_system_prompt_save))
                    }
                }
            }
        }
    }
}
