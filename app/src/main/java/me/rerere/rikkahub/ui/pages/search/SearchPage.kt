package me.rerere.rikkahub.ui.pages.search

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.User
import me.rerere.rikkahub.data.db.MessageSearchResult
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPage(vm: SearchVM = koinViewModel()) {
    val navController = LocalNavController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    OutlinedTextField(
                        value = vm.searchQuery,
                        onValueChange = { vm.onQueryChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("搜索消息内容...") },
                        shape = RoundedCornerShape(50),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { vm.search() }
                        ),
                    )
                },
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (vm.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = contentPadding.calculateTopPadding())
                )
            }

            when {
                vm.searchQuery.isBlank() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
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
                        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
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

    Surface(onClick = onClick) {
        ListItem(
            leadingContent = {
                if (result.role.equals("USER", ignoreCase = true)) {
                    Icon(Lucide.User, contentDescription = null)
                } else {
                    Icon(Lucide.Bot, contentDescription = null)
                }
            },
            headlineContent = {
                Text(snippetText, maxLines = 3)
            },
            supportingContent = {
                Text(
                    text = result.conversationId.take(8),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
