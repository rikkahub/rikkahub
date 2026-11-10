package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Attachment01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.SearchRemove
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.utils.plus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 概览里的一张卡片：一轮对话 = 一条用户消息 + 后面跟着的助手回复（工具调用会拆成多个节点，这里合并）。
 * 对话开头没有用户消息时（如开场白），[user] 为空。
 */
private data class PreviewRound(
    // 这一轮第一个节点的 id，用作列表 key
    val id: Uuid,
    // 点击跳转的目标，取这一轮第一个节点
    val firstIndex: Int,
    val lastIndex: Int,
    val user: UIMessage?,
    val reply: UIMessage?,
    // 去掉 Markdown 标记、折叠空白后的纯文本，搜索和摘要都用它
    val userText: String,
    val replyText: String,
    val replyParts: List<UIMessagePart>,
    val favorite: Boolean,
) {
    fun matches(query: String): Boolean =
        userText.contains(query, ignoreCase = true) || replyText.contains(query, ignoreCase = true)
}

private fun List<MessageNode>.toPreviewRounds(): List<PreviewRound> {
    val groups = mutableListOf<MutableList<IndexedValue<MessageNode>>>()
    forEachIndexed { index, node ->
        if (node.currentMessage.role == MessageRole.USER || groups.isEmpty()) groups.add(mutableListOf())
        groups.last().add(IndexedValue(index, node))
    }
    return groups.map { group ->
        val user = group.first().value.currentMessage.takeIf { it.role == MessageRole.USER }
        val replies = group.map { it.value.currentMessage }.filter { it.role != MessageRole.USER }
        PreviewRound(
            id = group.first().value.id,
            firstIndex = group.first().index,
            lastIndex = group.last().index,
            user = user,
            reply = replies.lastOrNull(),
            userText = user?.toPreviewText().orEmpty(),
            replyText = replies.map { it.toPreviewText() }.filter { it.isNotEmpty() }.joinToString(" "),
            replyParts = replies.flatMap { it.parts },
            favorite = group.any { it.value.isFavorite },
        )
    }
}

/**
 * 消息概览：聊天页左滑进入，每轮对话一张卡片，点击跳到对应位置。
 * [chatListState] 用来标出当前阅读位置，并在打开时把它滚到可见范围内。
 */
@Composable
internal fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    chatListState: LazyListState,
    listState: LazyListState,
    settings: Settings,
    hazeState: HazeState,
    onJumpToMessage: (Int) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val query = searchQuery.trim()

    // 长对话里逐条做正则清洗很慢，放到后台算；没算完时为 null。
    // 生成中节点每个 chunk 都在变，conflate 掉中间状态，只算最新的
    val nodes by rememberUpdatedState(conversation.messageNodes)
    val loadedRounds by produceState<List<PreviewRound>?>(initialValue = null) {
        snapshotFlow { nodes }
            .conflate()
            .collect { value = withContext(Dispatchers.Default) { it.toPreviewRounds() } }
    }
    val rounds = loadedRounds.orEmpty()
    val filtered = remember(rounds, query) {
        if (query.isEmpty()) rounds else rounds.filter { it.matches(query) }
    }

    val assistant = remember(settings.assistants, conversation.assistantId) {
        settings.getAssistantById(conversation.assistantId)
    }
    val modelById = remember(settings.providers) {
        settings.providers.flatMap { it.models }.associateBy { it.id }
    }

    // 打开时的阅读位置；概览期间聊天列表不动，记一次就够
    val currentIndex = remember { chatListState.firstVisibleItemIndex }
    // listState 由外部持有（顶栏胶囊要读它），每次打开都重新定位：停在当前轮的前一轮，让上面露出一点上下文。
    // 要等卡片算出来、列表里有内容之后才能滚
    val loaded = loadedRounds != null
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        val currentRound = rounds.indexOfFirst { currentIndex in it.firstIndex..it.lastIndex }
        listState.scrollToItem((currentRound - 1).coerceAtLeast(0))
    }
    // 搜索框固定在列表外，滚到中间时改搜索词，结果要从头看
    LaunchedEffect(listState) {
        snapshotFlow { searchQuery.trim() }.drop(1).collect { listState.scrollToItem(0) }
    }

    // 搜索框浮在列表上方固定不动；列表顶部留白放进 contentPadding，内容能滚到搜索框和透明标题栏下面并渐隐
    val searchBarTop = innerPadding.calculateTopPadding() + 8.dp
    val listTop = searchBarTop + PreviewSearchBarHeight + 12.dp
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp) + PaddingValues(
                top = listTop,
                bottom = 32.dp + innerPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .topFadingEdge(listTop)
                .hazeSource(state = hazeState),
        ) {
            if (loaded && filtered.isEmpty()) {
                item(key = PreviewEmptyKey) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.SearchRemove,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.search_page_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(items = filtered, key = { it.id }) { round ->
                val reply = round.reply
                PreviewRoundCard(
                    round = round,
                    query = query,
                    assistant = assistant,
                    model = reply?.let { it.modelId?.let(modelById::get) ?: it.snapshotModel() },
                    isCurrent = currentIndex in round.firstIndex..round.lastIndex,
                    onClick = { onJumpToMessage(round.firstIndex) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        PreviewSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            resultCount = filtered.size,
            totalCount = rounds.size,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = searchBarTop),
        )
    }
}

private val PreviewSearchBarHeight = 52.dp
private const val PreviewEmptyKey = "PreviewEmpty"

@Composable
private fun PreviewSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(PreviewSearchBarHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Search01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.history_page_search_messages),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = if (query.isBlank()) "$totalCount" else "$resultCount / $totalCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = if (query.isEmpty()) 12.dp else 0.dp),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.chat_page_cancel),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewRoundCard(
    round: PreviewRound,
    query: String,
    assistant: Assistant?,
    model: Model?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
        border = if (isCurrent) BorderStroke(1.5.dp, colors.primary) else null,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 用户消息：一行
            val user = round.user
            if (user != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (round.userText.isNotEmpty()) {
                            HighlightedSnippet(
                                text = round.userText,
                                query = query,
                                maxLines = 1,
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                color = colors.onSurface,
                            )
                        } else {
                            PreviewAttachmentSummary(parts = user.parts)
                        }
                    }
                    if (round.favorite) {
                        Icon(
                            imageVector = HugeIcons.FavouriteCircle,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = colors.primary,
                        )
                    }
                    Text(
                        text = remember(user.createdAt) {
                            user.createdAt.toJavaLocalDateTime().toPreviewTimeString()
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // 助手回复：两行，前面带模型图标区分角色
            if (round.reply != null && (round.replyText.isNotEmpty() || round.replyParts.hasAttachmentSummary())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewReplyAvatar(assistant = assistant, model = model)
                    Box(modifier = Modifier.weight(1f)) {
                        if (round.replyText.isNotEmpty()) {
                            HighlightedSnippet(
                                text = round.replyText,
                                query = query,
                                maxLines = 2,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        } else {
                            PreviewAttachmentSummary(parts = round.replyParts)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightedSnippet(
    text: String,
    query: String,
    maxLines: Int,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
) {
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val highlightTextColor = MaterialTheme.colorScheme.onTertiaryContainer
    val annotated = remember(text, query, highlightColor, highlightTextColor) {
        buildHighlightedText(
            // Text 会把整段文字都排版一遍才截到 maxLines，长回复必须先截短
            text = extractMatchingSnippet(text, query).take(SnippetMaxLength),
            query = query,
            highlightColor = highlightColor,
            highlightTextColor = highlightTextColor,
        )
    }
    Text(
        text = annotated,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PreviewReplyAvatar(
    assistant: Assistant?,
    model: Model?,
) {
    // 和两行正文的首行对齐
    val avatarModifier = Modifier
        .padding(top = 1.dp)
        .size(18.dp)
    when {
        assistant?.useAssistantAvatar == true -> UIAvatar(
            name = assistant.name,
            value = assistant.avatar,
            modifier = avatarModifier,
        )

        model != null -> AutoAIIcon(name = model.modelId, modifier = avatarModifier)
    }
}

/**
 * 图片、文件、工具调用没有文字，用图标 + 数量概括，避免整条只剩空白。
 */
@Composable
private fun PreviewAttachmentSummary(parts: List<UIMessagePart>) {
    val images = parts.count { it is UIMessagePart.Image }
    val files = parts.count { it is UIMessagePart.Document || it is UIMessagePart.Video || it is UIMessagePart.Audio }
    val tools = remember(parts) {
        parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName }.distinct()
    }
    if (images == 0 && files == 0 && tools.isEmpty()) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (images > 0) AttachmentChip(icon = HugeIcons.Image01, label = images.toString())
        if (files > 0) AttachmentChip(icon = HugeIcons.Attachment01, label = files.toString())
        if (tools.isNotEmpty()) {
            AttachmentChip(
                icon = HugeIcons.Wrench01,
                label = tools.joinToString(", "),
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

private fun List<UIMessagePart>.hasAttachmentSummary(): Boolean = any {
    it is UIMessagePart.Image || it is UIMessagePart.Document || it is UIMessagePart.Video ||
        it is UIMessagePart.Audio || it is UIMessagePart.Tool
}

@Composable
private fun AttachmentChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// 概览里空间有限：当天只显示时间，其他日子用短日期 + 时间，不带秒
private fun LocalDateTime.toPreviewTimeString(): String {
    val style = if (toLocalDate() == LocalDate.now()) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    }
    return style.withLocale(Locale.getDefault()).format(this)
}

private val CodeFenceRegex = Regex("```[\\w-]*")
private val HeadingRegex = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
private val QuoteRegex = Regex("(?m)^\\s{0,3}>\\s?")
private val EmphasisRegex = Regex("\\*\\*|__|~~")
private val WhitespaceRegex = Regex("\\s+")

private fun UIMessage.toPreviewText(): String = toText()
    .replace(CodeFenceRegex, " ")
    .replace(HeadingRegex, "")
    .replace(QuoteRegex, "")
    .replace(EmphasisRegex, "")
    .replace(WhitespaceRegex, " ")
    .trim()

/**
 * 截取包含搜索词的片段：匹配词前保留一小段上下文，保证三行内能看到匹配位置。
 */
private fun extractMatchingSnippet(text: String, query: String): String {
    if (query.isBlank()) return text
    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex <= SnippetContextLength) return text
    // 尽量从词边界开始截，免得开头是半个单词
    val start = text.indexOf(' ', matchIndex - SnippetContextLength).let { space ->
        if (space in 0 until matchIndex) space + 1 else matchIndex - SnippetContextLength
    }
    return "…" + text.substring(start)
}

private const val SnippetContextLength = 24

// 卡片最多显示两行，留足宽屏和小字号的余量
private const val SnippetMaxLength = 300

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color,
    highlightTextColor: Color,
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)
        while (index >= 0) {
            append(text.substring(startIndex, index))
            withStyle(
                SpanStyle(
                    background = highlightColor,
                    color = highlightTextColor,
                    fontWeight = FontWeight.SemiBold,
                )
            ) {
                append(text.substring(index, index + query.length))
            }
            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }
        if (startIndex < text.length) append(text.substring(startIndex))
    }
}
