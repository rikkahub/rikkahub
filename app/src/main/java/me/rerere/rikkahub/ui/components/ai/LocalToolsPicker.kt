package me.rerere.rikkahub.ui.components.ai

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.ToggleSurface

private data class LocalToolMeta(
    val option: LocalToolOption,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
)

private val LocalToolsMeta = listOf(
    LocalToolMeta(
        option = LocalToolOption.JavascriptEngine,
        titleRes = R.string.assistant_page_local_tools_javascript_engine_title,
        descRes = R.string.assistant_page_local_tools_javascript_engine_desc,
    ),
    LocalToolMeta(
        option = LocalToolOption.TimeInfo,
        titleRes = R.string.assistant_page_local_tools_time_info_title,
        descRes = R.string.assistant_page_local_tools_time_info_desc,
    ),
    LocalToolMeta(
        option = LocalToolOption.Clipboard,
        titleRes = R.string.assistant_page_local_tools_clipboard_title,
        descRes = R.string.assistant_page_local_tools_clipboard_desc,
    ),
    LocalToolMeta(
        option = LocalToolOption.TermuxExec,
        titleRes = R.string.assistant_page_local_tools_termux_exec_title,
        descRes = R.string.assistant_page_local_tools_termux_exec_desc,
    ),
    LocalToolMeta(
        option = LocalToolOption.TermuxPython,
        titleRes = R.string.assistant_page_local_tools_termux_python_title,
        descRes = R.string.assistant_page_local_tools_termux_python_desc,
    ),
    LocalToolMeta(
        option = LocalToolOption.Tts,
        titleRes = R.string.assistant_page_local_tools_tts_title,
        descRes = R.string.assistant_page_local_tools_tts_desc,
    ),
)

@Composable
fun LocalToolsPickerButton(
    assistant: Assistant,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val enabledCount = assistant.localTools.size

    ToggleSurface(
        modifier = modifier,
        checked = assistant.localTools.isNotEmpty(),
        onClick = {
            showPicker = true
        },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                BadgedBox(
                    badge = {
                        if (enabledCount > 0) {
                            Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                                Text(text = enabledCount.toString())
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Lucide.Wrench,
                        contentDescription = stringResource(R.string.assistant_page_tab_local_tools),
                    )
                }
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_tab_local_tools),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                LocalToolsPicker(
                    assistant = assistant,
                    onUpdateAssistant = onUpdateAssistant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LocalToolsPicker(
    assistant: Assistant,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(LocalToolsMeta) { item ->
            val enabled = assistant.localTools.contains(item.option)
            Card {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(item.titleRes),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(item.descRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            maxLines = 5,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            val updated = if (checked) {
                                assistant.copy(localTools = (assistant.localTools + item.option).distinct())
                            } else {
                                assistant.copy(localTools = assistant.localTools - item.option)
                            }
                            onUpdateAssistant(updated)
                        },
                    )
                }
            }
        }
    }
}
