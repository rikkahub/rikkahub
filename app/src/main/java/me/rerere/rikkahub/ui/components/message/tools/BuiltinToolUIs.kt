package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.HighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject

/**
 * 记忆工具: 按 action 区分标题/图标, 摘要显示记忆内容, 详情附带删除按钮
 */
object MemoryToolUI : ToolUIRenderer {
    private const val ACTION_CREATE = "create"
    private const val ACTION_EDIT = "edit"
    private const val ACTION_DELETE = "delete"

    override val toolName: String = "memory_tool"

    private fun action(context: ToolUIContext): String? =
        context.arguments.getStringContent("action")

    override fun icon(context: ToolUIContext): ImageVector = when (action(context)) {
        ACTION_DELETE -> HugeIcons.Eraser
        else -> HugeIcons.QuillWrite01
    }

    @Composable
    override fun title(context: ToolUIContext): String = when (action(context)) {
        ACTION_CREATE -> stringResource(R.string.chat_message_tool_create_memory)
        ACTION_EDIT -> stringResource(R.string.chat_message_tool_edit_memory)
        ACTION_DELETE -> stringResource(R.string.chat_message_tool_delete_memory)
        else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        action(context) in listOf(ACTION_CREATE, ACTION_EDIT) &&
            context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { memoryContent ->
            Text(
                text = memoryContent,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val memoryRepo: MemoryRepository = koinInject()
        val scope = rememberCoroutineScope()
        val memoryId = (context.content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull
        DefaultToolPreview(
            context = context,
            headerActions = if (action(context) in listOf(ACTION_CREATE, ACTION_EDIT) && memoryId != null) {
                {
                    IconButton(
                        onClick = {
                            scope.launch {
                                memoryRepo.deleteMemory(memoryId)
                                onDismissRequest()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.tool_ui_delete_memory)
                        )
                    }
                }
            } else {
                null
            },
        )
    }
}

/**
 * 网络搜索: 标题带查询词, 摘要显示 answer 与结果数, 详情为结果列表
 */
object SearchWebToolUI : ToolUIRenderer {
    override val toolName: String = "search_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_search_web,
        context.arguments.getStringContent("query") ?: ""
    )

    private fun items(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("items")?.jsonArray ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("answer") != null || items(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("answer")?.let { answer ->
            Text(
                text = answer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val items = items(context)
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

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        SearchWebPreview(arguments = context.arguments, content = content)
    }
}

/**
 * 网页抓取: 摘要显示 URL, 详情为各网页的 Markdown 内容
 */
object ScrapeWebToolUI : ToolUIRenderer {
    override val toolName: String = "scrape_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GlobalSearch

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_scrape_web)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("url") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        Text(
            text = context.arguments.getStringContent("url") ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        ScrapeWebPreview(content = content)
    }
}

/**
 * 获取时间信息
 */
object GetTimeInfoToolUI : ToolUIRenderer {
    override val toolName: String = "get_time_info"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Time02

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_get_time)
}

/**
 * 剪贴板: 按 action 区分读/写标题
 */
object ClipboardToolUI : ToolUIRenderer {
    private const val ACTION_READ = "read"
    private const val ACTION_WRITE = "write"

    override val toolName: String = "clipboard_tool"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clipboard

    @Composable
    override fun title(context: ToolUIContext): String =
        when (context.arguments.getStringContent("action")) {
            ACTION_READ -> stringResource(R.string.chat_message_tool_clipboard_read)
            ACTION_WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
        }
}

/**
 * 文本转语音: 摘要显示朗读文本与重播按钮
 */
object TextToSpeechToolUI : ToolUIRenderer {
    override val toolName: String = "text_to_speech"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.VolumeHigh

    @Composable
    override fun title(context: ToolUIContext): String {
        val preview = context.arguments.getStringContent("text")?.let { text ->
            if (text.length > 24) text.take(24) + "…" else text
        } ?: ""
        return stringResource(R.string.tool_ui_speaking, preview)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("text") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val eventBus: AppEventBus = koinInject()
        val scope = rememberCoroutineScope()
        val text = context.arguments.getStringContent("text") ?: ""
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.tool_ui_replay),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 技能调用: 标题显示技能名与路径
 */
object UseSkillToolUI : ToolUIRenderer {
    override val toolName: String = "use_skill"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val skillName = context.arguments.getStringContent("name") ?: ""
        val path = context.arguments.getStringContent("path")
        return if (path != null) "Skill: $skillName / $path" else "Skill: $skillName"
    }
}

/**
 * 最近聊天: 标题固定, 摘要列出最近对话的标题
 */
object RecentChatsToolUI : ToolUIRenderer {
    override val toolName: String = "recent_chats"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Message02

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_recent_chats)

    private fun chats(context: ToolUIContext): List<JsonElement> =
        (context.content as? JsonArray) ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = chats(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val titles = chats(context).mapNotNull { it.getStringContent("title") }
        if (titles.isEmpty()) return
        Text(
            text = titles.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.shimmer(isLoading = context.loading),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 对话历史搜索: 标题带查询词, 摘要显示命中数
 */
object ConversationSearchToolUI : ToolUIRenderer {
    override val toolName: String = "conversation_search"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_conversation_search,
        context.arguments.getStringContent("query") ?: ""
    )

    private fun results(context: ToolUIContext): List<JsonElement> =
        (context.content as? JsonArray) ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = results(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val results = results(context)
        if (results.isEmpty()) return
        Text(
            text = stringResource(R.string.chat_message_tool_search_results_count, results.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun SearchWebPreview(
    arguments: JsonElement,
    content: JsonElement,
) {
    val context = LocalContext.current
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
                    onClick = { context.openUrl(url) },
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

// --- Fork tools: per-step icon + readable title ---------------------------------
// These tools are fully wired and invocable; without a renderer their chat steps
// fell through to the generic wrench icon + raw tool name. Ported from the fork's
// pre-merge getToolIcon/title branches so the steps read the same as before.

/** JS skill execution (run_js). */
object RunJsToolUI : ToolUIRenderer {
    override val toolName: String = "run_js"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val skillName = context.arguments.getStringContent("skill_name").orEmpty()
        return if (skillName.isNotBlank()) "JS skill: $skillName" else "JS skill"
    }
}

// System-intent tools fire a system intent the user finalises in the destination
// app. The title prefers the executed result's `summary` field (e.g. "Calendar
// event: Lunch") and falls back to an args-derived preview pre-execution.

object CreateCalendarEventToolUI : ToolUIRenderer {
    override val toolName: String = "create_calendar_event"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clock02

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val t = context.arguments.getStringContent("title").orEmpty()
        return if (t.isNotBlank()) "Calendar event: $t" else "Create calendar event"
    }
}

object CreateContactToolUI : ToolUIRenderer {
    override val toolName: String = "create_contact"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val first = context.arguments.getStringContent("first_name").orEmpty()
        val last = context.arguments.getStringContent("last_name").orEmpty()
        val name = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
        return if (name.isNotBlank()) "Contact: $name" else "Create contact"
    }
}

object SendSmsIntentToolUI : ToolUIRenderer {
    override val toolName: String = "send_sms_intent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val ph = context.arguments.getStringContent("phone_number").orEmpty()
        return if (ph.isNotBlank()) "SMS to $ph" else "Compose SMS"
    }
}

object SendEmailIntentToolUI : ToolUIRenderer {
    override val toolName: String = "send_email_intent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Message02

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val to = context.arguments.getStringContent("to").orEmpty()
        return if (to.isNotBlank()) "Email to $to" else "Compose email"
    }
}

object OpenWifiSettingsToolUI : ToolUIRenderer {
    override val toolName: String = "open_wifi_settings"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Settings03

    @Composable
    override fun title(context: ToolUIContext): String =
        context.content.getStringContent("summary") ?: "WiFi Settings"
}

object ShowLocationOnMapToolUI : ToolUIRenderer {
    override val toolName: String = "show_location_on_map"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Pin

    @Composable
    override fun title(context: ToolUIContext): String {
        context.content.getStringContent("summary")?.let { return it }
        val q = context.arguments.getStringContent("query").orEmpty()
        return if (q.isNotBlank()) "Map: $q" else "Open map"
    }
}
