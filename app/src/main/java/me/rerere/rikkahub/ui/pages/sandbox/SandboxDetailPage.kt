package me.rerere.rikkahub.ui.pages.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.terminal.TerminalColors
import me.rerere.rikkahub.ui.components.terminal.TerminalView
import me.rerere.rikkahub.ui.components.terminal.TerminalViewState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxDetailPage(sandboxId: String) {
    val vm = koinViewModel<SandboxDetailVM> { parametersOf(sandboxId) }
    val output by vm.output.collectAsStateWithLifecycle()
    val terminalState by vm.terminalState.collectAsStateWithLifecycle()
    val info = remember { vm.sandboxInfo }

    DisposableEffect(Unit) {
        onDispose { vm.killSession() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.name ?: "沙盒终端") },
                navigationIcon = { BackButton() },
                actions = {
                    if (terminalState == TerminalState.RUNNING) {
                        IconButton(onClick = { vm.killSession() }) {
                            Icon(
                                HugeIcons.ArrowRight01,
                                contentDescription = "停止",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = TerminalColors.Background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            when {
                !vm.isRootfsInstalled -> {
                    NotInstalledState()
                }

                terminalState == TerminalState.IDLE -> {
                    IdleState(onStart = { vm.startSession() })
                }

                else -> {
                    TerminalView(
                        output = output,
                        state = when (terminalState) {
                            TerminalState.RUNNING -> TerminalViewState.RUNNING
                            else -> TerminalViewState.EXITED
                        },
                        onSendLine = { vm.sendLine(it) },
                        onRestart = { vm.startSession() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotInstalledState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "rootfs 未安装",
                style = MaterialTheme.typography.titleMedium,
                color = TerminalColors.Text,
            )
            Text(
                "请先在沙盒列表页安装 rootfs",
                style = MaterialTheme.typography.bodySmall,
                color = TerminalColors.Text.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun IdleState(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Button(onClick = onStart) {
            Text("启动终端")
        }
    }
}
