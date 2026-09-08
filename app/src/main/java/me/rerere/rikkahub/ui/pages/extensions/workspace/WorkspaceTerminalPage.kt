package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceTerminalPage(id: String) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val sessionManager: WorkspaceTerminalSessionManager = koinInject()
    val root = state.workspace?.root
    val terminalStateFlow = remember(root, sessionManager) {
        root?.let(sessionManager::observeWorkspace) ?: flowOf(WorkspaceTerminalTabsState())
    }
    val terminalState by terminalStateFlow.collectAsStateWithLifecycle(
        initialValue = WorkspaceTerminalTabsState(),
    )
    var pendingCloseTabId by remember(root) { mutableStateOf<Long?>(null) }

    LaunchedEffect(root) {
        root?.let { sessionManager.ensureSession(it) }
    }

    RikkahubTheme(colorMode = ColorMode.DARK) {
        Scaffold { innerPadding ->
            WorkspaceTerminalContent(
                root = root,
                state = terminalState,
                contentPadding = innerPadding,
                onCreateTab = { root?.let(sessionManager::createTab) },
                onSelectTab = { tabId ->
                    root?.let { sessionManager.selectTab(it, tabId) }
                },
                onCloseTab = { tabId ->
                    pendingCloseTabId = tabId
                },
            )
        }

        val pendingCloseTab = terminalState.tabs.firstOrNull { it.id == pendingCloseTabId }
        if (pendingCloseTab != null) {
            AlertDialog(
                onDismissRequest = { pendingCloseTabId = null },
                title = {
                    Text(
                        stringResource(
                            R.string.workspace_terminal_close_confirm_title,
                            pendingCloseTab.number,
                        ),
                    )
                },
                text = {
                    Text(stringResource(R.string.workspace_terminal_close_confirm_message))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            root?.let { sessionManager.closeTab(it, pendingCloseTab.id) }
                            pendingCloseTabId = null
                        },
                    ) {
                        Text(stringResource(R.string.workspace_terminal_close))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCloseTabId = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun WorkspaceTerminalContent(
    root: String?,
    state: WorkspaceTerminalTabsState,
    contentPadding: PaddingValues,
    onCreateTab: () -> Unit,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
) {
    val selectedIndex = state.tabs.indexOfFirst { it.id == state.selectedTabId }
        .takeIf { it >= 0 }
        ?: 0
    val selectedTab = state.tabs.getOrNull(selectedIndex)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            // Resize to the final IME height at animation start so the toolbar moves immediately
            // and terminal rows stay stable instead of sending a SIGWINCH for every frame.
            .windowInsetsPadding(WindowInsets.imeAnimationTarget),
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton()
                Box(modifier = Modifier.weight(1f)) {
                    if (selectedTab != null) {
                        SecondaryScrollableTabRow(
                            selectedTabIndex = selectedIndex,
                            modifier = Modifier.fillMaxWidth(),
                            edgePadding = 0.dp,
                            minTabWidth = 160.dp,
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            state.tabs.forEach { tab ->
                                val tabDescription = stringResource(
                                    R.string.workspace_terminal_tab,
                                    tab.number,
                                )
                                val tabTitle = tab.title ?: tabDescription
                                val isSelected = selectedTab.id == tab.id
                                Tab(
                                    selected = isSelected,
                                    onClick = { onSelectTab(tab.id) },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .widthIn(min = 160.dp, max = 240.dp)
                                        .semantics {
                                            contentDescription = tabTitle
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = tabTitle,
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(
                                                    if (isSelected) Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                                                    else Modifier,
                                                ),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = if (isSelected) TextOverflow.Clip else TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        val closeDescription = stringResource(
                                            R.string.workspace_terminal_close_tab,
                                            tab.number,
                                        )
                                        IconButton(
                                            onClick = { onCloseTab(tab.id) },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .semantics {
                                                    contentDescription = closeDescription
                                                },
                                        ) {
                                            Text(text = "×", fontSize = 18.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val newTabDescription = stringResource(R.string.workspace_terminal_new_tab)
                IconButton(
                    onClick = onCreateTab,
                    enabled = root != null && !state.isCreating,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = newTabDescription },
                ) {
                    Text(text = "+", fontSize = 24.sp)
                }
            }
            if (selectedTab != null) {
                WorkspaceTerminalTabContent(
                    tab = selectedTab,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            root == null || state.isCreating || state.readiness == WorkspaceTerminalReadiness.Loading -> {
                                stringResource(R.string.workspace_terminal_loading)
                            }
                            state.readiness == WorkspaceTerminalReadiness.NotInstalled -> {
                                stringResource(R.string.workspace_terminal_not_installed)
                            }
                            else -> stringResource(R.string.workspace_terminal_no_tabs)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTerminalTabContent(
    tab: WorkspaceTerminalTab,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val terminalTextSizePx = with(LocalDensity.current) { 12.sp.roundToPx() }
    val terminalTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
    }
    var controlDown by remember(tab.id) { mutableStateOf(false) }
    var altDown by remember(tab.id) { mutableStateOf(false) }
    val viewClient = remember(tab.id) {
        WorkspaceTerminalViewClient(context)
    }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown

    DisposableEffect(tab.id, viewClient) {
        onDispose {
            if (tab.client.terminalView === viewClient.terminalView) {
                tab.client.terminalView = null
            }
            viewClient.terminalView = null
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    TerminalView(viewContext, null).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTextSize(terminalTextSizePx)
                        setTypeface(terminalTypeface)
                        setTerminalViewClient(viewClient)
                        attachSession(tab.session)
                        tab.client.terminalView = this
                        viewClient.terminalView = this
                        post {
                            viewClient.focusAndShowKeyboard()
                        }
                    }
                },
                update = { terminalView ->
                    terminalView.isFocusable = true
                    terminalView.isFocusableInTouchMode = true
                    terminalView.setTextSize(terminalTextSizePx)
                    terminalView.setTypeface(terminalTypeface)
                    terminalView.setTerminalViewClient(viewClient)
                    tab.client.terminalView = terminalView
                    viewClient.terminalView = terminalView
                    terminalView.attachSession(tab.session)
                    terminalView.onScreenUpdated()
                },
            )
            if (tab.finished) {
                Text(
                    text = stringResource(R.string.workspace_terminal_exited),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }
        TerminalExtraKeysBar(
            controlDown = controlDown,
            altDown = altDown,
            onControlToggle = { controlDown = !controlDown },
            onAltToggle = { altDown = !altDown },
            onSendText = { tab.session.writeText(it) },
        )
    }
}

@Composable
private fun TerminalExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", selected = controlDown, onClick = onControlToggle)
        TerminalExtraKey("ALT", selected = altDown, onClick = onAltToggle)
        TerminalExtraKey("-") { onSendText("-") }
        TerminalExtraKey("/") { onSendText("/") }
        TerminalExtraKey("|") { onSendText("|") }
        TerminalExtraKey("←") { onSendText("\u001B[D") }
        TerminalExtraKey("↓") { onSendText("\u001B[B") }
        TerminalExtraKey("↑") { onSendText("\u001B[A") }
        TerminalExtraKey("→") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        },
    )
}

private fun TerminalSession.writeText(text: String) {
    val bytes = text.toByteArray()
    write(bytes, 0, bytes.size)
}
