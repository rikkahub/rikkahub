package me.rerere.rikkahub.ui.pages.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

@Composable
fun SearchPage(vm: SearchVM = koinViewModel()) {
    val navController = LocalNavController.current
    val focusRequester = remember { FocusRequester() }
    var showRebuildDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text("重建消息索引") },
            text = { Text("此操作将清空现有索引并重新扫描全部聊天记录，消息越多耗时越长，确认继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildDialog = false
                        vm.rebuildIndex()
                    }
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = { Text("搜索消息") },
                actions = {
                    IconButton(
                        onClick = { showRebuildDialog = true },
                        enabled = !vm.isRebuilding,
                    ) {
                        Icon(Lucide.RefreshCw, contentDescription = "重建索引")
                    }
                }
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            OutlinedTextField(
                value = vm.searchQuery,
                onValueChange = { vm.onQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("搜索消息内容...") },
                shape = RoundedCornerShape(50),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { vm.search() }
                ),
            )

            Box(modifier = Modifier.weight(1f)) {
                if (vm.isLoading || vm.isRebuilding) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                when {
                    vm.isRebuilding -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val (current, total) = vm.rebuildProgress
                            Text(
                                text = if (total > 0) "正在重建索引 ($current / $total)..." else "正在重建索引...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    vm.searchQuery.isBlank() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "输入关键词，按搜索键查找消息",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    vm.results.isEmpty() && !vm.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No results",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(vm.results) { result ->
                                SearchResultItem(
                                    result = result,
                                    onClick = {
                                        navigateToChatPage(
                                            navController,
                                            chatId = Uuid.parse(result.conversationId),
                                            nodeId = Uuid.parse(result.nodeId),
                                        )
                                    }
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
private fun SearchResultItem(
    result: MessageSearchResult,
    onClick: () -> Unit,
) {
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val snippetText = buildAnnotatedString {
        val snippet = result.snippet
        var index = 0
        while (index < snippet.length) {
            val start = snippet.indexOf('[', index)
            if (start == -1) {
                append(snippet.substring(index))
                break
            }
            if (start > index) {
                append(snippet.substring(index, start))
            }
            val end = snippet.indexOf(']', start + 1)
            if (end == -1) {
                append(snippet.substring(start))
                break
            }
            val matched = snippet.substring(start + 1, end)
            withStyle(SpanStyle(background = highlightColor)) {
                append(matched)
            }
            index = end + 1
        }
    }
    val formattedTime = remember(result.updateAt) {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(result.updateAt)
    }

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = result.title.ifBlank { "无标题" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = snippetText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
