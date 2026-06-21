package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.common.http.jsonPrimitiveOrNull
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

    /**
     * 内联摘要要展示的记忆内容, 不展示时为 null。
     *
     * action 守卫是渲染条件的一部分 (只有 create/edit 才显示内容), 因此 [hasSummary]
     * 与 [Summary] 都必须经由此处, 避免二者各判一次而出现 hasSummary=false 却仍渲染内容的偏差。
     */
    internal fun summaryContent(context: ToolUIContext): String? =
        context.content.getStringContent("content")
            ?.takeIf { action(context) in listOf(ACTION_CREATE, ACTION_EDIT) }

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
        summaryContent(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        summaryContent(context)?.let { memoryContent ->
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
                            contentDescription = "Delete memory"
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
        return "Speaking: $preview"
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
                    contentDescription = "Replay",
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
 * Image generation: the title carries the prompt; the summary renders the generated image(s) inline
 * (the Image parts in the tool output).
 */
object GenerateImageToolUI : ToolUIRenderer {
    override val toolName: String = "generate_image"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val prompt = context.arguments.getStringContent("prompt")?.let {
            if (it.length > 24) it.take(24) + "…" else it
        } ?: ""
        return "Generate image: $prompt"
    }

    private fun images(context: ToolUIContext): List<UIMessagePart.Image> =
        context.tool.output.filterIsInstance<UIMessagePart.Image>()

    override fun hasSummary(context: ToolUIContext): Boolean = images(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            images(context).forEach { image ->
                ZoomableAsyncImage(
                    model = image.url,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                )
            }
        }
    }
}
