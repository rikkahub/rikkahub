package me.rerere.rikkahub.ui.pages.chat

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.TextRequestPreview
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.service.ChatPromptPreviewMessage
import me.rerere.rikkahub.service.ChatRuntimeInspection
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.ui.components.ui.LuneSection
import me.rerere.rikkahub.ui.components.ui.luneGlassBorderColor
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.UiState

internal enum class ChatRuntimeInspectorTab {
    PROMPTS,
    VARIABLES,
    PAYLOAD,
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
        containerColor = Color.Transparent,
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
            Spacer(modifier = Modifier.height(16.dp))
            InspectorTabs(
                activeTab = activeTab,
                onSelect = { activeTab = it },
            )
            Spacer(modifier = Modifier.height(16.dp))
            when (activeTab) {
                ChatRuntimeInspectorTab.PROMPTS -> PromptInspectorContent(
                    state = state,
                    onRefresh = onRefresh,
                )

                ChatRuntimeInspectorTab.VARIABLES -> VariableInspectorContent(
                    state = state,
                    onRefresh = onRefresh,
                )

                ChatRuntimeInspectorTab.PAYLOAD -> PayloadInspectorContent(
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

    LuneSection(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "运行时检查",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = summary?.let {
                            "${it.assistantName} · ${it.characterName}"
                        } ?: "Dry-run 当前会话，不会发出真实请求。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InspectorIconAction(
                    icon = HugeIcons.Refresh01,
                    contentDescription = "刷新运行时预览",
                    onClick = onRefresh,
                )
            }

            summary?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InspectorMetricPill(label = "Model", value = it.modelName)
                    InspectorMetricPill(label = "Preset", value = it.presetName)
                    InspectorMetricPill(label = "Mode", value = it.generationType)
                    InspectorMetricPill(label = "Msgs", value = it.promptMessages.size.toString())
                    InspectorMetricPill(label = "Tokens", value = it.promptTokenEstimate.toString())
                }
            }
        }
    }
}

@Composable
private fun InspectorTabs(
    activeTab: ChatRuntimeInspectorTab,
    onSelect: (ChatRuntimeInspectorTab) -> Unit,
) {
    SecondaryScrollableTabRow(
        selectedTabIndex = activeTab.ordinal,
        containerColor = Color.Transparent,
        edgePadding = 0.dp,
    ) {
        ChatRuntimeInspectorTab.entries.forEach { tab ->
            Tab(
                selected = activeTab == tab,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = when (tab) {
                            ChatRuntimeInspectorTab.PROMPTS -> "Prompts"
                            ChatRuntimeInspectorTab.VARIABLES -> "Variables"
                            ChatRuntimeInspectorTab.PAYLOAD -> "Payload"
                        }
                    )
                },
            )
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
        LuneSection(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Prompt Stack",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "可读提示词视图，不含思维链字段；精确请求看 Payload · 约 ${inspection.promptTokenEstimate} tokens · ${filteredMessages.size}/${inspection.promptMessages.size} 条消息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InspectorIconAction(
                        icon = HugeIcons.Copy01,
                        contentDescription = "复制提示词",
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(
                                            "prompt_preview",
                                            promptMessagesToClipboardText(filteredMessages),
                                        )
                                    )
                                )
                                toaster.show("已复制提示词", type = ToastType.Success)
                            }
                        },
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, luneGlassBorderColor().copy(alpha = 0.7f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("搜索角色或正文")
                        },
                        singleLine = true,
                        shape = CircleShape,
                        leadingIcon = {
                            Icon(HugeIcons.Search01, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                InspectorInlineIcon(
                                    icon = HugeIcons.Cancel01,
                                    contentDescription = "清空搜索",
                                    onClick = { searchQuery = "" },
                                )
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredMessages.isEmpty()) {
            InspectorEmptyState(
                modifier = Modifier.fillMaxSize(),
                title = "没有匹配到提示词",
                subtitle = "换个关键词，或者直接复制全部结果再看。",
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            item {
                Spacer(modifier = Modifier.navigationBarsPadding())
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

    LuneSection(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起提示词" else "展开提示词",
                    ) {
                        expanded = !expanded
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PromptRoleBadge(role = item.role)
                    Text(
                        text = "${item.tokenEstimate} tokens",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            } else {
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
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
        LuneSection(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Variable Snapshot",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Local ${inspection.localVariables.size} · Global ${inspection.globalVariables.size} · Context dry-run",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InspectorIconAction(
                        icon = HugeIcons.Copy01,
                        contentDescription = "复制变量 JSON",
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
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChatVariableInspectorTab.entries.forEach { tab ->
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            label = {
                                Text(
                                    when (tab) {
                                        ChatVariableInspectorTab.LOCAL -> "Local"
                                        ChatVariableInspectorTab.GLOBAL -> "Global"
                                        ChatVariableInspectorTab.CONTEXT -> "Context"
                                    }
                                )
                            },
                            shape = CircleShape,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (currentJson.isEmpty()) {
            InspectorEmptyState(
                modifier = Modifier.fillMaxSize(),
                title = "当前没有可显示的数据",
                subtitle = "这个 scope 还没有变量，切到其他 scope 看看。",
            )
            return
        }

        LuneSection(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                JsonTree(
                    json = currentJson,
                    modifier = Modifier.fillMaxWidth(),
                    initialExpandLevel = if (activeTab == ChatVariableInspectorTab.CONTEXT) 2 else 1,
                )
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun PayloadInspectorContent(
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

        is UiState.Success -> PayloadInspectorLoaded(
            inspection = state.data,
        )
    }
}

@Composable
private fun PayloadInspectorLoaded(
    inspection: ChatRuntimeInspection,
) {
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val payload = inspection.payloadPreview

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        LuneSection(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Provider Payload",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${payload.providerName} · ${payload.apiName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InspectorIconAction(
                        icon = HugeIcons.Copy01,
                        contentDescription = "复制完整请求",
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(
                                            "provider_payload",
                                            payloadToClipboardText(payload),
                                        )
                                    )
                                )
                                toaster.show("已复制请求预览", type = ToastType.Success)
                            }
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InspectorMetricPill(label = "Method", value = payload.method)
                    InspectorMetricPill(label = "Stream", value = if (payload.stream) "on" else "off")
                    InspectorMetricPill(label = "Headers", value = payload.headers.size.toString())
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PayloadEndpointSection(payload = payload)
            PayloadHeadersSection(payload = payload)
            PayloadBodySection(
                payload = payload,
                onCopyBody = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText(
                                    "provider_payload_body",
                                    JsonInstantPretty.encodeToString(payload.body),
                                )
                            )
                        )
                        toaster.show("已复制请求体", type = ToastType.Success)
                    }
                },
            )
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun PayloadEndpointSection(
    payload: TextRequestPreview,
) {
    LuneSection(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InspectorSectionHeader(
                title = "Endpoint",
                subtitle = "离线生成的真实请求入口，不会触发网络调用。",
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PayloadPill(text = payload.method)
                if (payload.stream) {
                    PayloadPill(text = "SSE")
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, luneGlassBorderColor().copy(alpha = 0.7f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                SelectionContainer {
                    Text(
                        text = payload.url,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetbrainsMono,
                    )
                }
            }
        }
    }
}

@Composable
private fun PayloadHeadersSection(
    payload: TextRequestPreview,
) {
    LuneSection(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InspectorSectionHeader(
                title = "Headers",
                subtitle = "${payload.headers.size} 条请求头，保留追加顺序。",
            )

            if (payload.headers.isEmpty()) {
                InspectorEmptyState(
                    modifier = Modifier.fillMaxWidth(),
                    title = "没有请求头",
                    subtitle = "当前 provider 没有额外头部信息。",
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    payload.headers.forEachIndexed { index, header ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = header.name,
                                modifier = Modifier.weight(0.34f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = header.value,
                                    modifier = Modifier.weight(0.66f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = JetbrainsMono,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PayloadBodySection(
    payload: TextRequestPreview,
    onCopyBody: () -> Unit,
) {
    LuneSection(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                InspectorSectionHeader(
                    title = "JSON Body",
                    subtitle = "请求体已经合并 assistant / model 的自定义 body。",
                )
                InspectorInlineIcon(
                    icon = HugeIcons.Copy01,
                    contentDescription = "复制请求体",
                    onClick = onCopyBody,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, luneGlassBorderColor().copy(alpha = 0.7f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    JsonTree(
                        json = payload.body,
                        modifier = Modifier.fillMaxWidth(),
                        initialExpandLevel = 2,
                    )
                }
            }
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
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state is UiState.Loading) {
                CircularProgressIndicator()
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, luneGlassBorderColor().copy(alpha = 0.7f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Icon(
                        imageVector = if (state is UiState.Error) HugeIcons.Alert01 else HugeIcons.Search01,
                        contentDescription = null,
                        modifier = Modifier.padding(14.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (state !is UiState.Loading) {
                FilledTonalButton(onClick = onRefresh) {
                    Icon(
                        imageVector = HugeIcons.Refresh01,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun InspectorMetricPill(
    label: String,
    value: String,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, luneGlassBorderColor().copy(alpha = 0.7f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            text = "$label  $value",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = JetbrainsMono,
        )
    }
}

@Composable
private fun InspectorIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, luneGlassBorderColor().copy(alpha = 0.7f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .clickable(
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onClick = onClick,
                )
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun InspectorInlineIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InspectorSectionHeader(
    title: String,
    subtitle: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InspectorEmptyState(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, luneGlassBorderColor().copy(alpha = 0.7f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PromptRoleBadge(
    role: MessageRole,
) {
    val tint = when (role) {
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.secondary
        MessageRole.USER -> MaterialTheme.colorScheme.primary
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.tertiary
        MessageRole.TOOL -> MaterialTheme.colorScheme.error
    }

    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.28f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            text = role.toPromptRoleLabel(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = JetbrainsMono,
            color = tint,
        )
    }
}

@Composable
private fun PayloadPill(
    text: String,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.primary,
        )
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

private fun promptMessagesToClipboardText(messages: List<ChatPromptPreviewMessage>): String {
    return messages.joinToString("\n\n==========\n\n") { item ->
        "[${item.role.name.lowercase()}]\n${item.content}"
    }
}

private fun payloadToClipboardText(payload: TextRequestPreview): String {
    val headers = payload.headers.joinToString("\n") { header ->
        "${header.name}: ${header.value}"
    }
    val body = JsonInstantPretty.encodeToString(payload.body)
    return buildString {
        append(payload.method)
        append(' ')
        append(payload.url)
        append("\n\n")
        if (headers.isNotBlank()) {
            append(headers)
            append("\n\n")
        }
        append(body)
    }
}
