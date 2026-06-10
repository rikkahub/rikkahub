package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.isShellRunnable
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// Sideload-only PTY terminal page (issue #197 slice 6b). Physically absent from the Play APK
// (I-FLAVOR): the only entry<Screen.WorkspaceTerminal>{ WorkspaceTerminalPage() } binding lives in
// the sideload WorkspaceTerminalDestination seam; the play seam registers nothing. Ported from
// upstream 22b280f3; the upstream RikkahubTheme(colorMode = DARK) wrapper is dropped because the
// fork's RikkahubTheme takes no colorMode param and the content already forces a black Surface.
@Composable
fun WorkspaceTerminalPage(id: String) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name
                            ?.let { stringResource(R.string.workspace_terminal_title_with_name, it) }
                            ?: stringResource(R.string.workspace_terminal_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
            )
        },
    ) { innerPadding ->
        // I-ENABLE (#197 HP-2 / design note §4.3): the interactive terminal is a shell sink just like
        // WorkspaceRepository.executeCommand, so it must honor the SAME gate — open a session only
        // when the workspace's shell is enabled AND its rootfs is READY, never on shellEnabled alone
        // (a BROKEN/INSTALLING workspace with stale linux/ files must not get an interactive shell).
        val shellRunnable = state.workspace?.let {
            isShellRunnable(it.shellEnabled, it.shellStatus)
        } ?: false
        WorkspaceTerminalContent(
            root = state.workspace?.root,
            shellRunnable = shellRunnable,
            contentPadding = innerPadding,
        )
    }
}

@Composable
private fun WorkspaceTerminalContent(
    root: String?,
    shellRunnable: Boolean,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val terminalTextSizePx = with(LocalDensity.current) { 13.sp.roundToPx() }
    val terminalTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
    }
    var finished by remember(root) { mutableStateOf(false) }
    var controlDown by remember(root) { mutableStateOf(false) }
    var altDown by remember(root) { mutableStateOf(false) }
    val sessionClient = remember(root) {
        WorkspaceTerminalSessionClient(context.applicationContext) {
            finished = true
        }
    }
    val viewClient = remember(root) {
        WorkspaceTerminalViewClient(context)
    }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown

    if (root == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.workspace_terminal_loading),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }

    // Gate on the DB invariant (shell enabled + rootfs READY) BEFORE the filesystem check: the
    // terminal must refuse for a disabled/BROKEN/INSTALLING workspace exactly as the executeCommand
    // sink does, even if stale linux/ files happen to satisfy workspaceRootfsReady.
    if (!shellRunnable || !workspaceRootfsReady(context, root)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.workspace_terminal_rootfs_required),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }

    val session = remember(root) {
        createWorkspaceTerminalSession(context, root, sessionClient)
    }

    DisposableEffect(session) {
        onDispose {
            sessionClient.terminalView = null
            viewClient.terminalView = null
            session.finishIfRunning()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding(),
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                            attachSession(session)
                            sessionClient.terminalView = this
                            viewClient.terminalView = this
                            setOnTouchListener { _, event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    viewClient.focusAndShowKeyboard()
                                }
                                false
                            }
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
                        sessionClient.terminalView = terminalView
                        viewClient.terminalView = terminalView
                        terminalView.setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_UP) {
                                viewClient.focusAndShowKeyboard()
                            }
                            false
                        }
                        terminalView.attachSession(session)
                        terminalView.onScreenUpdated()
                    },
                )
                if (finished) {
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
                onSendText = { session.writeText(it) },
            )
        }
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
