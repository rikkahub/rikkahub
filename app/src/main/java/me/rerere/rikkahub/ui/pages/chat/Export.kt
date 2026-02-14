package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.composables.icons.lucide.BookDashed
import com.composables.icons.lucide.BookHeart
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Wrench
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.ai.util.encodeBase64
import me.rerere.common.android.appTempFolder
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import com.dokar.sonner.rememberToasterState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.toLocalString
import org.koin.compose.koinInject
import java.io.FileOutputStream
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = LocalSettings.current
    var imageExportOptions by remember { mutableStateOf(ImageExportOptions()) }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(id = R.string.chat_page_export_format))

                val markdownSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Markdown")
                OutlinedCard(
                    onClick = {
                        exportToMarkdown(context, conversation, selectedMessages)
                        toaster.show(
                            markdownSuccessMessage,
                            type = ToastType.Success
                        )
                        onDismissRequest()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown))
                        },
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown_desc))
                        },
                        leadingContent = {
                            Icon(Lucide.FileText, contentDescription = null)
                        }
                    )
                }

                val imageSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Image")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(id = R.string.chat_page_export_image))
                            },
                            supportingContent = {
                                Text(stringResource(id = R.string.chat_page_export_image_desc))
                            },
                            leadingContent = {
                                Icon(Lucide.Image, contentDescription = null)
                            }
                        )

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text(stringResource(R.string.chat_page_export_image_expand_reasoning)) },
                            trailingContent = {
                                Switch(
                                    checked = imageExportOptions.expandReasoning,
                                    onCheckedChange = {
                                        imageExportOptions = imageExportOptions.copy(expandReasoning = it)
                                    }
                                )
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            exportToImage(
                                                context = context,
                                                scope = scope,
                                                density = density,
                                                conversation = conversation,
                                                messages = selectedMessages,
                                                settings = settings,
                                                options = imageExportOptions
                                            )
                                        }.onFailure {
                                            it.printStackTrace()
                                            toaster.show(
                                                message = "Failed to export image: ${it.message}",
                                                type = ToastType.Error
                                            )
                                        }
                                    }
                                    toaster.show(
                                        imageSuccessMessage,
                                        type = ToastType.Success
                                    )
                                    onDismissRequest()
                                }
                            ) {
                                Text(stringResource(R.string.mermaid_export))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun exportToMarkdown(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>
) {
    val filename = "chat-export-${LocalDateTime.now().toLocalString()}.md"

    val sb = buildAnnotatedString {
        append("# ${conversation.title}\n\n")
        append("*Exported on ${LocalDateTime.now().toLocalString()}*\n\n")

        messages.forEach { message ->
            val role = if (message.role == MessageRole.USER) "**User**" else "**Assistant**"
            append("$role:\n\n")
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        append(part.text)
                        appendLine()
                    }

                    is UIMessagePart.Image -> {
                        append("![Image](${part.encodeBase64().getOrNull()?.base64})")
                        appendLine()
                    }

                    is UIMessagePart.Reasoning -> {
                        part.reasoning.lines()
                            .filter { it.isNotBlank() }
                            .map { "> $it" }
                            .forEach {
                                append(it)
                            }
                        appendLine()
                        appendLine()
                    }

                    is UIMessagePart.Tool -> {
                        append("**Tool**: `${part.toolName}`")
                        appendLine()
                        if (part.toolCallId.isNotBlank()) {
                            append("- Call ID: `${part.toolCallId}`")
                            appendLine()
                        }

                        append("Input:")
                        appendLine()
                        append("```json")
                        appendLine()
                        append(JsonInstantPretty.encodeToString(part.inputAsJson()))
                        appendLine()
                        append("```")
                        appendLine()

                        if (part.output.isNotEmpty()) {
                            append("Output:")
                            appendLine()
                            part.output.forEach { outputPart ->
                                when (outputPart) {
                                    is UIMessagePart.Text -> {
                                        append("```text")
                                        appendLine()
                                        append(outputPart.text)
                                        appendLine()
                                        append("```")
                                        appendLine()
                                    }

                                    is UIMessagePart.Reasoning -> {
                                        outputPart.reasoning.lines()
                                            .filter { it.isNotBlank() }
                                            .forEach {
                                                append("> $it")
                                                appendLine()
                                            }
                                    }

                                    is UIMessagePart.Image -> {
                                        append("![Tool Image](${outputPart.encodeBase64().getOrNull()?.base64})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Document -> {
                                        append("[Document: ${outputPart.fileName}](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Video -> {
                                        append("[Video](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Audio -> {
                                        append("[Audio](${outputPart.url})")
                                        appendLine()
                                    }

                                    else -> {}
                                }
                            }
                        }
                        appendLine()
                    }

                    else -> {}
                }
            }
            appendLine()
            append("---")
            appendLine()
        }
    }

    try {
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }
        FileOutputStream(file).use {
            it.write(sb.toString().toByteArray())
        }

        // Share the file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        shareFile(context, uri, "text/markdown")

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private suspend fun exportToImage(
    context: Context,
    scope: CoroutineScope,
    density: Density,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
    options: ImageExportOptions = ImageExportOptions()
) {
    val filename = "chat-export-${LocalDateTime.now().toLocalString()}.png"
    val composer = BitmapComposer(scope)
    val activity = context.getActivity()
    if (activity == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to get activity", Toast.LENGTH_SHORT).show()
        }
        return
    }

    val bitmap = composer.composableToBitmap(
        activity = activity,
        width = 540.dp,
        screenDensity = density,
        content = {
            CompositionLocalProvider(LocalSettings provides settings) {
                ExportedChatImage(
                    conversation = conversation,
                    messages = messages,
                    options = options
                )
            }
        }
    )

    try {
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }

        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
        }

        // Save to gallery
        context.exportImage(activity, bitmap, filename)

        // Share the file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        shareFile(context, uri, "image/png")
    } catch (e: Exception) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to export image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } finally {
        bitmap.recycle()
    }
}

data class ImageExportOptions(val expandReasoning: Boolean = false)

@Composable
private fun ExportedChatImage(
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ImageExportOptions = ImageExportOptions()
) {
    val navBackStack = rememberNavController()
    val highlighter = koinInject<Highlighter>()
    val toasterState = rememberToasterState()
    RikkahubTheme {
        CompositionLocalProvider(
            LocalNavController provides navBackStack,
            LocalHighlighter provides highlighter,
            LocalToaster provides toasterState
        ) {
            Surface(
                modifier = Modifier.width(540.dp) // like 1080p but with density independence
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = "${LocalDateTime.now().toLocalString()}  rikka-ai.com",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Use painterResource for the logo
                        val painter = painterResource(id = R.mipmap.ic_launcher_foreground)
                        Image(
                            painter = painter,
                            contentDescription = "Logo",
                            modifier = Modifier.size(60.dp)
                        )
                    }

                    // Messages
                    messages.forEach { message ->
                        ExportedChatMessage(
                            message = message,
                            options = options,
                            prevMessage = messages.getOrNull(messages.indexOf(message) - 1)
                        )
                    }

                    // Watermark
                    Column {
                        Text(
                            text = stringResource(R.string.export_image_warning),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ExportedChatMessage(
    message: UIMessage,
    prevMessage: UIMessage? = null,
    options: ImageExportOptions = ImageExportOptions()
) {
    if (message.parts.isEmptyUIMessage()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    val model = message.modelId?.let { settings.findModelById(it) }
    // Always show model icon for assistant messages in exported images
    val showModelIcon = message.role == MessageRole.ASSISTANT && prevMessage?.role == MessageRole.USER
    val iconLabel = when {
        model?.modelId?.isNotBlank() == true -> model.modelId
        model?.displayName?.isNotBlank() == true -> model.displayName
        else -> "AI"
    }
    val groupedParts = remember(message.parts) { message.parts.groupMessageParts() }
    val messageContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .widthIn(max = (540 * 0.9).dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start
        ) {
            groupedParts.forEach { block ->
                when (block) {
                    is MessagePartBlock.ThinkingBlock -> {
                        if (block.steps.isNotEmpty()) {
                            ChainOfThought(
                                steps = block.steps,
                                collapsedVisibleCount = block.steps.size
                            ) { step ->
                                when (step) {
                                    is ThinkingStep.ReasoningStep -> {
                                        ExportedReasoningStep(
                                            reasoning = step.reasoning,
                                            expanded = options.expandReasoning
                                        )
                                    }

                                    is ThinkingStep.ToolStep -> {
                                        ExportedToolStep(
                                            tool = step.tool
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is MessagePartBlock.ContentBlock -> {
                        when (val part = block.part) {
                            is UIMessagePart.Text -> {
                                if (part.text.isNotBlank()) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                        if (message.role == MessageRole.USER) {
                                            Card(
                                                shape = MaterialTheme.shapes.medium,
                                            ) {
                                                MarkdownBlock(
                                                    content = part.text,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        } else {
                                            if (settings.displaySetting.showAssistantBubble) {
                                                Card(
                                                    shape = MaterialTheme.shapes.medium,
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    )
                                                ) {
                                                    MarkdownBlock(
                                                        content = part.text,
                                                        modifier = Modifier.padding(8.dp)
                                                    )
                                                }
                                            } else {
                                                MarkdownBlock(
                                                    content = part.text,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is UIMessagePart.Image -> {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(part.url)
                                        .allowHardware(false)
                                        .crossfade(false)
                                        .build(),
                                    contentDescription = "Image",
                                    modifier = Modifier
                                        .sizeIn(maxHeight = 300.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            }

                            else -> {
                                // Other parts are not rendered in image export for now
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelIcon) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            AutoAIIcon(
                name = iconLabel,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(36.dp)
            )

            Text(
                text = iconLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    messageContent()
}

@Composable
private fun ChainOfThoughtScope.ExportedReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    expanded: Boolean
) {
    val duration = reasoning.finishedAt?.let { endTime ->
        endTime - reasoning.createdAt
    } ?: (kotlin.time.Clock.System.now() - reasoning.createdAt)

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = {},
        icon = {
            Icon(
                painter = painterResource(R.drawable.deepthink),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = stringResource(R.string.deep_thinking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        },
        extra = if (duration > 0.seconds) {
            {
                Text(
                    text = duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            null
        },
        contentVisible = expanded,
        content = {
            MarkdownBlock(
                content = reasoning.reasoning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    )
}

@Composable
private fun ChainOfThoughtScope.ExportedToolStep(
    tool: UIMessagePart.Tool
) {
    val memoryAction = runCatching {
        tool.inputAsJson().jsonObject["action"]?.jsonPrimitiveOrNull?.contentOrNull
    }.getOrNull()
    val title = when (tool.toolName) {
        "memory_tool" -> when (memoryAction) {
            "create" -> stringResource(R.string.chat_message_tool_create_memory)
            "edit" -> stringResource(R.string.chat_message_tool_edit_memory)
            "delete" -> stringResource(R.string.chat_message_tool_delete_memory)
            else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
        }

        "search_web" -> {
            val query = runCatching {
                tool.inputAsJson().jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
            }.getOrDefault("")
            stringResource(R.string.chat_message_tool_search_web, query)
        }

        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
        else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
    }
    ControlledChainOfThoughtStep(
        expanded = true,
        onExpandedChange = {},
        icon = {
            Icon(
                imageVector = when (tool.toolName) {
                    "memory_tool" -> when (memoryAction) {
                        "create", "edit" -> Lucide.BookHeart
                        "delete" -> Lucide.BookDashed
                        else -> Lucide.Wrench
                    }

                    "search_web" -> Lucide.Search
                    "scrape_web" -> Lucide.Earth
                    else -> Lucide.Wrench
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        contentVisible = false,
        content = null,
    )
}

private fun shareFile(context: Context, uri: Uri, mimeType: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.chat_page_export_share_via)
        )
    )
}
