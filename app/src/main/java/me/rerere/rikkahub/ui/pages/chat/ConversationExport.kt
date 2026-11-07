package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Share04
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import java.io.File
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Wrench01
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.ui.context.Navigator
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import me.rerere.ui.toast.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.ai.util.encodeBase64
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.ChatMessageServerToolStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.message.tools.memoryToolTitle
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.ui.toast.rememberToasterState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.ui.charts.ChartCard
import me.rerere.ui.charts.ChartSpec
import me.rerere.ui.components.BitmapComposer
import java.io.FileOutputStream
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private enum class ChatExportFormat(
    val extension: String,
    val mimeType: String,
) {
    Markdown("md", "text/markdown"),
    Image("png", "image/png"),
}

private enum class ChatExportAction { Save, Share }

/**
 * 先选格式和选项，再决定「保存」（系统另存为，用户挑位置）还是「分享」（系统分享面板）。
 * 两种都是先在临时目录生成文件；保存时等用户选完位置再把文件拷过去。
 */
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
    var exportOptions by remember { mutableStateOf(ExportOptions()) }
    var format by rememberSaveable { mutableStateOf(ChatExportFormat.Markdown) }
    var exporting by remember { mutableStateOf<ChatExportAction?>(null) }
    // 另存为面板打开期间界面可能重建（比如旋转屏幕），待写入的临时文件路径要存下来
    var pendingSavePath by rememberSaveable { mutableStateOf<String?>(null) }

    fun onSaveDocumentResult(uri: Uri?) {
        val source = pendingSavePath?.let(::File) ?: return
        pendingSavePath = null
        if (uri == null) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("Cannot open $uri")
                    output.use { out -> source.inputStream().use { it.copyTo(out) } }
                }
            }.onSuccess {
                toaster.show(
                    context.getString(R.string.chat_page_export_success, uri.displayName(context) ?: source.name),
                    type = ToastType.Success,
                )
            }.onFailure {
                it.printStackTrace()
                toaster.show(context.getString(R.string.chat_page_export_failed, it.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    // CreateDocument 的 MIME 类型在构造时就定死了，每种格式一个
    val saveMarkdownLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ChatExportFormat.Markdown.mimeType)
    ) { onSaveDocumentResult(it) }
    val saveImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ChatExportFormat.Image.mimeType)
    ) { onSaveDocumentResult(it) }

    fun export(action: ChatExportAction) {
        if (exporting != null) return
        // 面板关掉后多选会被清空，这里先把要导出的内容取出来
        val messages = selectedMessages
        val options = exportOptions
        val exportFormat = format
        exporting = action
        scope.launch {
            runCatching {
                when (exportFormat) {
                    ChatExportFormat.Markdown -> writeMarkdownExport(context, conversation, messages, options)
                    ChatExportFormat.Image -> writeImageExport(
                        context = context,
                        scope = scope,
                        density = density,
                        conversation = conversation,
                        messages = messages,
                        settings = settings,
                        options = options,
                    )
                }
            }.onSuccess { file ->
                onDismissRequest()
                when (action) {
                    ChatExportAction.Save -> {
                        pendingSavePath = file.path
                        when (exportFormat) {
                            ChatExportFormat.Markdown -> saveMarkdownLauncher.launch(file.name)
                            ChatExportFormat.Image -> saveImageLauncher.launch(file.name)
                        }
                    }

                    ChatExportAction.Share -> {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        shareFile(context, uri, exportFormat.mimeType)
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                it.printStackTrace()
                toaster.show(context.getString(R.string.chat_page_export_failed, it.message.orEmpty()), type = ToastType.Error)
            }
            exporting = null
        }
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.chat_page_export_format),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConnectedChoiceButtons(
                        options = ChatExportFormat.entries,
                        selected = format,
                        onSelect = { format = it },
                    ) { option, selected ->
                        ConnectedChoiceLabel(
                            text = when (option) {
                                ChatExportFormat.Markdown -> stringResource(R.string.chat_page_export_markdown)
                                ChatExportFormat.Image -> stringResource(R.string.media_kind_image)
                            },
                            selected = selected,
                            icon = when (option) {
                                ChatExportFormat.Markdown -> HugeIcons.File02
                                ChatExportFormat.Image -> HugeIcons.Image02
                            },
                        )
                    }
                    Text(
                        text = when (format) {
                            ChatExportFormat.Markdown -> stringResource(R.string.chat_page_export_markdown_desc)
                            ChatExportFormat.Image -> stringResource(R.string.chat_page_export_image_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                CardGroup {
                    switchItem(
                        checked = exportOptions.includeReasoning,
                        onCheckedChange = { exportOptions = exportOptions.copy(includeReasoning = it) },
                    ) {
                        Text(stringResource(R.string.chat_page_export_include_reasoning))
                    }
                    if (format == ChatExportFormat.Image) {
                        switchItem(
                            checked = exportOptions.expandReasoning,
                            enabled = exportOptions.includeReasoning,
                            onCheckedChange = { exportOptions = exportOptions.copy(expandReasoning = it) },
                        ) {
                            Text(stringResource(R.string.chat_page_export_image_expand_reasoning))
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatExportButton(
                        text = stringResource(R.string.common_save),
                        icon = HugeIcons.Download04,
                        loading = exporting == ChatExportAction.Save,
                        enabled = exporting == null,
                        tonal = true,
                        onClick = { export(ChatExportAction.Save) },
                        modifier = Modifier.weight(1f),
                    )
                    ChatExportButton(
                        text = stringResource(R.string.export_share),
                        icon = HugeIcons.Share04,
                        loading = exporting == ChatExportAction.Share,
                        enabled = exporting == null,
                        tonal = false,
                        onClick = { export(ChatExportAction.Share) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatExportButton(
    text: String,
    icon: ImageVector,
    loading: Boolean,
    enabled: Boolean,
    tonal: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            LoadingIndicator(modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
        }
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text, style = ButtonDefaults.textStyleFor(height))
    }
    val buttonModifier = modifier.heightIn(min = height)
    val contentPadding = ButtonDefaults.contentPaddingFor(height)
    if (tonal) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            contentPadding = contentPadding,
            modifier = buttonModifier,
            content = content,
        )
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            contentPadding = contentPadding,
            modifier = buttonModifier,
            content = content,
        )
    }
}

private fun exportFileName(format: ChatExportFormat): String {
    val time = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    return "chat-export-$time.${format.extension}"
}

private fun Uri.displayName(context: Context): String? = runCatching {
    context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

private suspend fun writeMarkdownExport(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ExportOptions,
): File = withContext(Dispatchers.IO) {
    val markdown = buildMarkdownExport(conversation, messages, options)
    val file = context.appTempFolder.resolve(exportFileName(ChatExportFormat.Markdown))
    file.writeText(markdown)
    file
}

private fun buildMarkdownExport(
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ExportOptions,
): String = buildString {
    append("# ${conversation.title}\n\n")
    append("*Exported on ${LocalDateTime.now().toLocalString()}*\n\n")

    messages.filterNot { it.isNoticeOnly }.forEach { message ->
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
                    if (!options.includeReasoning) return@forEach
                    part.reasoning.lines()
                        .filter { it.isNotBlank() }
                        .forEach {
                            append("> $it")
                            appendLine()
                        }
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

private suspend fun writeImageExport(
    context: Context,
    scope: CoroutineScope,
    density: Density,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
    options: ExportOptions = ExportOptions()
): File {
    val composer = BitmapComposer(scope)
    val activity = context.getActivity() ?: error("Failed to get activity")

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
        return withContext(Dispatchers.IO) {
            val file = context.appTempFolder.resolve(exportFileName(ChatExportFormat.Image))
            FileOutputStream(file).use { fos ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)) { "Failed to encode image" }
            }
            file
        }
    } finally {
        bitmap.recycle()
    }
}

data class ExportOptions(
    val includeReasoning: Boolean = true,
    val expandReasoning: Boolean = false,
)

@Composable
private fun ExportedChatImage(
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ExportOptions = ExportOptions()
) {
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = Navigator(navBackStack)
    val toasterState = rememberToasterState()
    RikkahubTheme {
        CompositionLocalProvider(
            LocalNavController provides navigator,
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
                    messages.forEachIndexed { index, message ->
                        ExportedChatMessage(
                            message = message,
                            options = options,
                            prevMessage = messages.getOrNull(index - 1)
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
    options: ExportOptions = ExportOptions()
) {
    // 通知是发给模型的运行时事件，不导出
    val parts = remember(message.parts, options.includeReasoning) {
        message.parts.filterNot {
            it is UIMessagePart.Notice || (!options.includeReasoning && it is UIMessagePart.Reasoning)
        }
    }
    if (parts.isEmptyUIMessage()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    val model = message.modelId?.let { settings.findModelById(it) } ?: message.snapshotModel()
    // Always show model icon for assistant messages in exported images
    val showModelIcon = message.role == MessageRole.ASSISTANT && prevMessage?.role == MessageRole.USER
    val iconLabel = when {
        model?.modelId?.isNotBlank() == true -> model.modelId
        model?.displayName?.isNotBlank() == true -> model.displayName
        else -> "AI"
    }
    val groupedParts = remember(parts) { parts.groupMessageParts() }
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

                                    is ThinkingStep.ServerToolStep -> {
                                        ChatMessageServerToolStep(tool = step.tool)
                                    }

                                    // 导出前已经去掉了通知
                                    is ThinkingStep.NoticeStep -> {}
                                }
                            }
                        }
                    }

                    is MessagePartBlock.ChartBlock -> {
                        val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                        spec?.let { ChartCard(spec = it) }
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
    val title = when (tool.toolName) {
        "search_web" -> {
            val query = runCatching {
                tool.inputAsJson().jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
            }.getOrDefault("")
            stringResource(R.string.chat_message_tool_search_web, query)
        }

        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
        else -> memoryToolTitle(tool.toolName, runCatching { tool.inputAsJson() }.getOrNull())
            ?: stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
    }
    ControlledChainOfThoughtStep(
        expanded = true,
        onExpandedChange = {},
        icon = {
            Icon(
                imageVector = when (tool.toolName) {
                    "search_web" -> HugeIcons.Search01
                    "scrape_web" -> HugeIcons.Earth
                    MemoryToolNames.DELETE -> HugeIcons.Book02
                    in MemoryToolNames.WRITES -> HugeIcons.Book04
                    else -> HugeIcons.Wrench01
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
