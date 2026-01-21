package me.rerere.rikkahub.ui.pages.history;

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val allConversations by vm.conversations.collectAsStateWithLifecycle()
    val searchConversations by produceState(emptyList(), searchText) {
        runCatching {
            vm.searchConversations(searchText).collect {
                value = it
            }
        }.onFailure {
            it.printStackTrace()
        }
    }
    val showConversations = if (searchText.isEmpty()) {
        allConversations
    } else {
        searchConversations
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.history_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            isSearchVisible = !isSearchVisible
                            if (!isSearchVisible) {
                                searchText = ""
                            }
                        }
                    ) {
                        Icon(Lucide.Search, contentDescription = stringResource(R.string.history_page_search))
                    }
                    IconButton(
                        onClick = {
                            showDeleteAllDialog = true
                        }
                    ) {
                        Icon(Lucide.Trash2, contentDescription = stringResource(R.string.history_page_delete_all))
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isSearchVisible,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                SearchInput(
                    value = searchText,
                    onValueChange = { searchText = it }
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
        val snackMessageUndo = stringResource(R.string.history_page_undo)
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(showConversations, key = { it.id }) { conversation ->
                SwipeableConversationItem(
                    conversation = conversation,
                    onClick = {
                        navigateToChatPage(navController, conversation.id)
                    },
                    onDelete = {
                        scope.launch {
                            // 先获取完整的对话数据（包含 messageNodes），用于撤销恢复
                            val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                            vm.deleteConversation(conversation)
                            val result = snackbarHostState.showSnackbar(
                                message = snackMessageDeleted,
                                actionLabel = snackMessageUndo,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                vm.restoreConversation(fullConversation)
                            }
                        }
                    },
                    onTogglePin = { vm.togglePinStatus(conversation.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(stringResource(R.string.history_page_search_placeholder))
                },
                shape = RoundedCornerShape(50),
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = { onValueChange("") },
                        modifier = Modifier
                    ) {
                        Icon(Lucide.X, stringResource(R.string.history_page_clear))
                    }
                }
            )
        }
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
            }

            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(25)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Lucide.Trash2,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        ConversationItem(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onClick = onClick
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25),
        modifier = modifier
    ) {
        ListItem(
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = Lucide.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
            trailingContent = {
                IconButton(
                    onClick = onTogglePin
                ) {
                    Icon(
                        if (conversation.isPinned) Lucide.PinOff else Lucide.Pin,
                        contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                            R.string.history_page_pin
                        )
                    )
                }
            }
        )
    }
}
