package me.rerere.rikkahub.ui.pages.chat

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.service.ChatPromptPreviewMessage
import me.rerere.rikkahub.service.ChatRuntimeInspection
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlinx.coroutines.launch

internal enum class ChatRuntimeInspectorTab {
    PROMPTS,
    VARIABLES,
}

private enum class ChatVariableInspectorTab {
    LOCAL,
    GLOBAL,
    CONTEXT,
}

@Composable
internal fun ChatRuntimeInspectorSheet(
    state: UiState<ChatRuntimeInspection>,
    initialTab: ChatRuntimeInspectorTab,
    onDismissRequest: () -> Unit,
    onRefresh: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by rememberSaveable { mutableStateOf(initialTab) }

    LaunchedEffect(initialTab) {
        activeTab = initialTab
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp),
        ) {
            InspectorHeader(
                state = state,
                onRefresh = onRefresh,
            )
            Spacer(modifier = Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ChatRuntimeInspectorTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ChatRuntimeInspectorTab.entries.size,
                        ),
                    ) {
                        Text(
                            when (tab) {
                                ChatRuntimeInspectorTab.PROMPTS -> "提示词查看器"
                                ChatRuntimeInspectorTab.VARIABLES -> "变量查看器"
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            when (activeTab) {
                ChatRuntimeInspectorTab.PROMPTS -> PromptInspectorContent(
                    state = state,
                    onRefresh = onRefresh,
                )

                ChatRuntimeInspectorTab.VARIABLES -> VariableInspectorContent(
                    state = state,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}

@Composable
private fun InspectorHeader(
    state: UiState<ChatRuntimeInspection>,
    onRefresh: () -> Unit,
) {
    val summary = (state as? UiState.Success)?.data
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "运行时检查",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            summary?.let {
                Text(
                    text = "模型 ${it.modelName} · 预设 ${it.presetName} · ${it.generationType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onRefresh) {
            Text("刷新")
        }
    }
}

@Composable
private fun PromptInspectorContent(
    state: UiState<ChatRuntimeInspection>,
    onRefresh: () -> Unit,
) {
    when (state) {
        UiState.Idle,
        UiState.Loading,
        is UiState.Error -> InspectorStatePanel(
            state = state,
            onRefresh = onRefresh,
        )

        is UiState.Success -> PromptInspectorLoaded(
            inspection = state.data,
        )
    }
}

@Composable
private fun PromptInspectorLoaded(
    inspection: ChatRuntimeInspection,
) {
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var searchQuery by rememberSaveable(inspection.promptMessages, inspection.generationType) {
        mutableStateOf("")
    }
    val filteredMessages = remember(inspection.promptMessages, searchQuery) {
        inspection.promptMessages.filter { message ->
            searchQuery.isBlank() ||
                message.content.contains(searchQuery, ignoreCase = true) ||
                message.role.name.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "约 ${inspection.promptTokenEstimate} tokens · ${filteredMessages.size}/${inspection.promptMessages.size} 条消息",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索提示词") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val payload = filteredMessages.joinToString("\n\n==========\n\n") { item ->
                                "[${item.role.name.lowercase()}]\n${item.content}"
                            }
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("prompt_preview", payload))
                                )
                                toaster.show("已复制提示词", type = ToastType.Success)
                            }
                        }
                    ) {
                        Text("复制全部")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Text(
                    text = "没有匹配到提示词内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> "${item.role.name}-$index-${item.content.hashCode()}" },
            ) { index, item ->
                PromptPreviewCard(
                    index = index,
                    item = item,
                )
            }
        }
    }
}

@Composable
private fun PromptPreviewCard(
    index: Int,
    item: ChatPromptPreviewMessage,
) {
    var expanded by rememberSaveable(index, item.content.hashCode()) {
        mutableStateOf(index < 2)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${item.role.toPromptRoleLabel()} · ${item.tokenEstimate} tokens",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = item.content,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetbrainsMono,
                    )
                }
            }
        }
    }
}

@Composable
private fun VariableInspectorContent(
    state: UiState<ChatRuntimeInspection>,
    onRefresh: () -> Unit,
) {
    when (state) {
        UiState.Idle,
        UiState.Loading,
        is UiState.Error -> InspectorStatePanel(
            state = state,
            onRefresh = onRefresh,
        )

        is UiState.Success -> VariableInspectorLoaded(
            inspection = state.data,
        )
    }
}

@Composable
private fun VariableInspectorLoaded(
    inspection: ChatRuntimeInspection,
) {
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var activeTab by rememberSaveable { mutableStateOf(ChatVariableInspectorTab.LOCAL) }
    val currentJson = remember(activeTab, inspection) {
        when (activeTab) {
            ChatVariableInspectorTab.LOCAL -> inspection.localVariables.toJsonObject()
            ChatVariableInspectorTab.GLOBAL -> inspection.globalVariables.toJsonObject()
            ChatVariableInspectorTab.CONTEXT -> inspection.contextVariables
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "当前 Local ${inspection.localVariables.size} 项 · Global ${inspection.globalVariables.size} 项",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Context 里包含 dry-run 后的宏环境、outlet 和变量快照。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ChatVariableInspectorTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ChatVariableInspectorTab.entries.size,
                            ),
                        ) {
                            Text(
                                when (tab) {
                                    ChatVariableInspectorTab.LOCAL -> "Local"
                                    ChatVariableInspectorTab.GLOBAL -> "Global"
                                    ChatVariableInspectorTab.CONTEXT -> "Context"
                                }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(
                                            "variables",
                                            JsonInstantPretty.encodeToString(currentJson),
                                        )
                                    )
                                )
                                toaster.show("已复制 JSON", type = ToastType.Success)
                            }
                        }
                    ) {
                        Text("复制 JSON")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (currentJson.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Text(
                    text = "当前没有可显示的数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            JsonTree(
                json = currentJson,
                modifier = Modifier.fillMaxWidth(),
                initialExpandLevel = if (activeTab == ChatVariableInspectorTab.CONTEXT) 2 else 1,
            )
        }
    }
}

@Composable
private fun InspectorStatePanel(
    state: UiState<ChatRuntimeInspection>,
    onRefresh: () -> Unit,
) {
    val message = when (state) {
        UiState.Idle -> "还没有加载运行时数据。"
        UiState.Loading -> "正在生成当前会话的 dry-run 预览..."
        is UiState.Error -> state.error.message ?: state.error.javaClass.simpleName
        is UiState.Success -> ""
    }
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state !is UiState.Loading) {
                TextButton(onClick = onRefresh) {
                    Text("重试")
                }
            }
        }
    }
}

private fun MessageRole.toPromptRoleLabel(): String {
    return when (this) {
        MessageRole.SYSTEM -> "system"
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL -> "tool"
    }
}

private fun Map<String, String>.toJsonObject(): JsonObject {
    return buildJsonObject {
        this@toJsonObject.forEach { (key, value) ->
            put(key, JsonPrimitive(value))
        }
    }
}
