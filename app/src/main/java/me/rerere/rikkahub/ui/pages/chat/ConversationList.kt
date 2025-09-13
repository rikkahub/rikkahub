package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: List<Conversation>,
    conversationJobs: Collection<Uuid>,
    modifier: Modifier = Modifier,
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {}
) {
    var searchInput by remember {
        mutableStateOf("")
    }
    val navController = LocalNavController.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = searchInput,
            onValueChange = {
                searchInput = it
            },
            modifier = Modifier
                .weight(1f),
            shape = RoundedCornerShape(50),
            trailingIcon = {
                AnimatedVisibility(searchInput.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            searchInput = ""
                        }
                    ) {
                        Icon(Lucide.X, null)
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            placeholder = {
                Text(stringResource(id = R.string.chat_page_search_placeholder))
            }
        )

        Tooltip(
            tooltip = { Text(stringResource(id = R.string.chat_page_search_placeholder)) },
        ) {
            IconButton(
                onClick = { navController.navigate(Screen.History) }
            ) {
                Icon(
                    imageVector = Lucide.History,
                    contentDescription = stringResource(R.string.chat_page_history),
                )
            }
        }
    }

    // 分离置顶和普通对话
    val calculateConversations by remember(conversations) {
        derivedStateOf {
            val filtered = conversations
                .filter { conversation ->
                    conversation.title.contains(searchInput, true)
                }

            // 分离置顶和非置顶对话
            val (pinned, unpinned) = filtered.partition { it.isPinned }

            // 置顶对话按更新时间排序
            val pinnedSorted = pinned.sortedByDescending { it.updateAt }

            // 非置顶对话按日期分组
            val unpinnedGrouped = unpinned
                .groupBy { conversation ->
                    val instant = conversation.updateAt
                    instant.atZone(ZoneId.systemDefault()).toLocalDate()
                }
                .toSortedMap(compareByDescending { it })
                .mapValues { (_, conversations) ->
                    conversations.sortedByDescending { it.updateAt }
                }

            pinnedSorted to unpinnedGrouped
        }
    }

    val (pinnedConversations, groupedConversations) = calculateConversations

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (conversations.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = stringResource(id = R.string.chat_page_no_conversations),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 置顶对话区域
        if (pinnedConversations.isNotEmpty()) {
            stickyHeader {
                PinnedHeader()
            }

            items(pinnedConversations, key = { it.id }) { conversation ->
                ConversationItem(
                    conversation = conversation,
                    selected = conversation.id == current.id,
                    loading = conversation.id in conversationJobs,
                    onClick = onClick,
                    onDelete = onDelete,
                    onRegenerateTitle = onRegenerateTitle,
                    onPin = onPin,
                    modifier = Modifier.animateItem()
                )
            }
        }

        // 普通对话按日期分组
        groupedConversations.forEach { (date, conversationsInGroup) ->
            // 添加日期标题
            stickyHeader {
                DateHeader(date = date)
            }

            // 每组内的对话列表
            items(conversationsInGroup, key = { it.id }) { conversation ->
                ConversationItem(
                    conversation = conversation,
                    selected = conversation.id == current.id,
                    loading = conversation.id in conversationJobs,
                    onClick = onClick,
                    onDelete = onDelete,
                    onRegenerateTitle = onRegenerateTitle,
                    onPin = onPin,
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
private fun PinnedHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Lucide.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun DateHeader(date: LocalDate) {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)

    val displayText = when {
        date.isEqual(today) -> stringResource(id = R.string.chat_page_today)
        date.isEqual(yesterday) -> stringResource(id = R.string.chat_page_yesterday)
        else -> {
            // 使用Android本地化日期格式
            val formatStyle = if (date.year == today.year) {
                // 同一年仅显示月日
                date.toLocalString(false)
            } else {
                // 不同年显示完整日期
                date.toLocalString(true)
            }
            formatStyle
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onClick: (Conversation) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
    } else {
        Color.Transparent
    }
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50f))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { onClick(conversation) },
                onLongClick = {
                    showDropdownMenu = true
                }
            )
            .background(backgroundColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))

            // 置顶图标
            AnimatedVisibility(conversation.isPinned) {
                Icon(
                    imageVector = Lucide.Pin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(loading) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.extendColors.green6)
                        .size(4.dp)
                        .semantics {
                            contentDescription = "Loading"
                        }
                )
            }
            DropdownMenu(
                expanded = showDropdownMenu,
                onDismissRequest = { showDropdownMenu = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (conversation.isPinned) stringResource(R.string.unpin_chat) else stringResource(R.string.pin_chat)
                        )
                    },
                    onClick = {
                        onPin(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            if (conversation.isPinned) Lucide.PinOff else Lucide.Pin,
                            null
                        )
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_regenerate_title))
                    },
                    onClick = {
                        onRegenerateTitle(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(Lucide.RefreshCw, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_delete))
                    },
                    onClick = {
                        onDelete(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(Lucide.Trash2, null)
                    }
                )
            }
        }
    }
}
