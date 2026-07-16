package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.workspace.WorkspaceCommandResult
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceTerminalPage(id: String) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val terminal by vm.terminalState.collectAsStateWithLifecycle()

    RikkahubTheme(colorMode = ColorMode.DARK) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = state.workspace?.name?.let {
                                stringResource(R.string.workspace_terminal_title_with_name, it)
                            } ?: stringResource(R.string.workspace_terminal_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { BackButton() },
                    actions = {
                        IconButton(
                            onClick = vm::clearTerminal,
                            enabled = terminal.history.isNotEmpty() && !terminal.running,
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = null)
                        }
                    },
                )
            },
        ) { contentPadding ->
            RemoteTerminalContent(
                state = terminal,
                onInputChange = vm::updateTerminalInput,
                onExecute = vm::executeTerminalCommand,
                modifier = Modifier.padding(contentPadding),
            )
        }
    }
}

@Composable
private fun RemoteTerminalContent(
    state: WorkspaceTerminalState,
    onInputChange: (String) -> Unit,
    onExecute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.history.size) {
        if (state.history.isNotEmpty()) listState.animateScrollToItem(state.history.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.history) { entry ->
                when (entry) {
                    is WorkspaceTerminalEntry.Command -> TerminalText(
                        text = "\$ ${entry.command}",
                        color = Color(0xFF7EE787),
                    )
                    is WorkspaceTerminalEntry.Result -> TerminalResult(entry.result)
                    is WorkspaceTerminalEntry.Error -> TerminalText(
                        text = entry.message,
                        color = Color(0xFFFF7B72),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                enabled = !state.running,
                singleLine = true,
                prefix = { Text("\$ ", fontFamily = FontFamily.Monospace) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            IconButton(
                onClick = { onExecute(state.input) },
                enabled = state.input.isNotBlank() && !state.running,
            ) {
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    Icon(HugeIcons.Play, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun TerminalResult(result: WorkspaceCommandResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (result.stdout.isNotEmpty()) TerminalText(stripAnsi(result.stdout), Color(0xFFE6EDF3))
        if (result.stderr.isNotEmpty()) TerminalText(stripAnsi(result.stderr), Color(0xFFFF7B72))
        if (result.exitCode != 0 || result.timedOut || result.truncated) {
            TerminalText(
                text = buildString {
                    append("exit ").append(result.exitCode)
                    if (result.timedOut) append(" · timeout")
                    if (result.truncated) append(" · truncated")
                },
                color = Color(0xFF8B949E),
            )
        }
    }
}

@Composable
private fun TerminalText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}

private fun stripAnsi(value: String): String = value.replace(ANSI_ESCAPE, "")

private val ANSI_ESCAPE = Regex("\\u001B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])")
