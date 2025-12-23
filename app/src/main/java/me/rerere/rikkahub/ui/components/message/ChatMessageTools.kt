package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BookDashed
import com.composables.icons.lucide.BookHeart
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wrench
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject

private object ToolNames {
    const val CREATE_MEMORY = "create_memory"
    const val EDIT_MEMORY = "edit_memory"
    const val DELETE_MEMORY = "delete_memory"
    const val SEARCH_WEB = "search_web"
    const val SCRAPE_WEB = "scrape_web"
}

private fun getToolIcon(toolName: String) = when (toolName) {
    ToolNames.CREATE_MEMORY, ToolNames.EDIT_MEMORY -> Lucide.BookHeart
    ToolNames.DELETE_MEMORY -> Lucide.BookDashed
    ToolNames.SEARCH_WEB -> Lucide.Search
    ToolNames.SCRAPE_WEB -> Lucide.Earth
    else -> Lucide.Wrench
}

private fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObject?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

@Composable
fun ToolCallItem(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
    loading: Boolean = false,
) {
    var showResult by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.animateContentSize(),
        onClick = {
            showResult = true
        },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 16.dp)
                .height(IntrinsicSize.Min)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 4.dp,
                )
            } else {
                Icon(
                    imageVector = getToolIcon(toolName),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = when (toolName) {
                        ToolNames.CREATE_MEMORY -> stringResource(R.string.chat_message_tool_create_memory)
                        ToolNames.EDIT_MEMORY -> stringResource(R.string.chat_message_tool_edit_memory)
                        ToolNames.DELETE_MEMORY -> stringResource(R.string.chat_message_tool_delete_memory)
                        ToolNames.SEARCH_WEB -> stringResource(
                            R.string.chat_message_tool_search_web,
                            arguments.getStringContent("query") ?: ""
                        )

                        ToolNames.SCRAPE_WEB -> stringResource(R.string.chat_message_tool_scrape_web)
                        else -> stringResource(
                            R.string.chat_message_tool_call_generic,
                            toolName
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
                if (toolName == ToolNames.CREATE_MEMORY || toolName == ToolNames.EDIT_MEMORY) {
                    content.getStringContent("content")?.let { memoryContent ->
                        Text(
                            text = memoryContent,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.shimmer(isLoading = loading),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (toolName == ToolNames.SEARCH_WEB) {
                    content.getStringContent("answer")?.let { answer ->
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.shimmer(isLoading = loading),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val items = content?.jsonObject?.get("items")?.jsonArray ?: emptyList()
                    if (items.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FaviconRow(
                                urls = items.mapNotNull { it.getStringContent("url") },
                                size = 18.dp,
                            )
                            Text(
                                text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
                if (toolName == ToolNames.SCRAPE_WEB) {
                    val url = arguments.getStringContent("url") ?: ""
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
    if (showResult && content != null) {
        ToolCallPreviewSheet(
            toolName = toolName,
            arguments = arguments,
            content = content,
            onDismissRequest = {
                showResult = false
            }
        )
    }
}

@Composable
private fun ToolCallPreviewSheet(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement,
    onDismissRequest: () -> Unit = {}
) {
    val navController = LocalNavController.current
    val memoryRepo: MemoryRepository = koinInject()
    val scope = rememberCoroutineScope()

    // Check if this is a memory creation/update operation
    val isMemoryOperation = toolName in listOf(ToolNames.CREATE_MEMORY, ToolNames.EDIT_MEMORY)
    val memoryId = (content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismissRequest,
        content = {
            when (toolName) {
                ToolNames.SEARCH_WEB -> SearchWebPreview(
                    arguments = arguments,
                    content = content,
                    navController = navController
                )

                ToolNames.SCRAPE_WEB -> ScrapeWebPreview(content = content)
                else -> GenericToolPreview(
                    toolName = toolName,
                    arguments = arguments,
                    content = content,
                    isMemoryOperation = isMemoryOperation,
                    memoryId = memoryId,
                    memoryRepo = memoryRepo,
                    scope = scope,
                    onDismissRequest = onDismissRequest
                )
            }
        },
    )
}

@Composable
private fun SearchWebPreview(
    arguments: JsonElement,
    content: JsonElement,
    navController: androidx.navigation.NavController
) {
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val answer = content.getStringContent("answer")
    val query = arguments.getStringContent("query") ?: ""

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(stringResource(R.string.chat_message_tool_search_prefix, query))
        }

        if (answer != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    MarkdownBlock(
                        content = answer,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (items.isNotEmpty()) {
            items(items) { item ->
                val url = item.getStringContent("url") ?: return@items
                val title = item.getStringContent("title") ?: return@items
                val text = item.getStringContent("text") ?: return@items

                Card(
                    onClick = { navController.navigate(Screen.WebView(url = url)) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(
                            url = url,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(text = title, maxLines = 1)
                            Text(
                                text = text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = url,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        } else {
            item {
                HighlightText(
                    code = JsonInstantPretty.encodeToString(content),
                    language = "json",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ScrapeWebPreview(content: JsonElement) {
    val urls = content.jsonObject["urls"]?.jsonArray ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(
                    R.string.chat_message_tool_scrape_prefix,
                    urls.joinToString(", ") { it.getStringContent("url") ?: "" }
                )
            )
        }

        items(urls) { url ->
            val urlObject = url.jsonObject
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = urlObject["url"]?.jsonPrimitive?.content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                )
                Card {
                    MarkdownBlock(
                        content = urlObject["content"]?.jsonPrimitive?.content ?: "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GenericToolPreview(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement,
    isMemoryOperation: Boolean,
    memoryId: Int?,
    memoryRepo: MemoryRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismissRequest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            // 如果是memory操作，允许用户快速删除
            if (isMemoryOperation && memoryId != null) {
                IconButton(
                    onClick = {
                        scope.launch {
                            memoryRepo.deleteMemory(memoryId)
                            onDismissRequest()
                        }
                    }
                ) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = "Delete memory"
                    )
                }
            }
        }
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_label, toolName))
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_result))
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(content),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }
    }
}
