package me.rerere.rikkahub.ui.pages.webview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Bug
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.base64Decode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewPage(url: String, content: String) {
    val state = if (url.isNotEmpty()) {
        rememberWebViewState(
            url = url,
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
            })
    } else {
        rememberWebViewState(
            data = content.base64Decode(),
            baseUrl = "https://rikkahub.local",
            mimeType = "text/html",
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
            }
        )
    }

    var showDropdown by remember { mutableStateOf(false) }
    var showConsoleSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    BackHandler(state.canGoBack) {
        state.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.pageTitle?.takeIf { it.isNotEmpty() } ?: state.currentUrl
                        ?: "",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleSmall
                    )
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = { state.reload() }) {
                        Icon(Lucide.RefreshCw, contentDescription = "Refresh")
                    }

                    IconButton(
                        onClick = { state.goForward() },
                        enabled = state.canGoForward
                    ) {
                        Icon(Lucide.ArrowRight, contentDescription = "Forward")
                    }

                    val urlHandler = LocalUriHandler.current
                    IconButton(
                        onClick = { showDropdown = true }
                    ) {
                        Icon(Lucide.EllipsisVertical, contentDescription = "More options")

                        DropdownMenu(
                            expanded = showDropdown,
                            onDismissRequest = { showDropdown = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open in Browser") },
                                leadingIcon = { Icon(Lucide.Earth, contentDescription = null) },
                                onClick = {
                                    showDropdown = false
                                    state.currentUrl?.let { url ->
                                        if (url.isNotBlank()) {
                                            urlHandler.openUri(url)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Console Logs") },
                                leadingIcon = { Icon(Lucide.Bug, contentDescription = null) },
                                onClick = {
                                    showDropdown = false
                                    showConsoleSheet = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) {
        WebView(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
        )
    }

    if (showConsoleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showConsoleSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Console Logs",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                SelectionContainer {
                    LazyColumn {
                        items(state.consoleMessages) { message ->
                            Text(
                                text = "${message.messageLevel().name}: ${message.message()}\n" +
                                    "Source: ${message.sourceId()}:${message.lineNumber()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetbrainsMono,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                color = when (message.messageLevel().name) {
                                    "ERROR" -> MaterialTheme.colorScheme.error
                                    "WARNING" -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                if (state.consoleMessages.isEmpty()) {
                    Text(
                        text = "No console messages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}
