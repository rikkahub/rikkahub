package me.rerere.rikkahub.ui.pages.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private val TerminalBackground = Color(0xFF0D1117)
private val TerminalText = Color(0xFFE6EDF3)
private val TerminalPromptBg = Color(0xFF161B22)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxDetailPage(sandboxId: String) {
    val vm = koinViewModel<SandboxDetailVM> { parametersOf(sandboxId) }
    val output by vm.output.collectAsStateWithLifecycle()
    val terminalState by vm.terminalState.collectAsStateWithLifecycle()
    val info = remember { vm.sandboxInfo }
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when output changes
    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // Kill session when leaving the page
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
        containerColor = TerminalBackground,
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
                    // Terminal output
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(TerminalBackground)
                            .verticalScroll(scrollState)
                            .padding(8.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = output,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = TerminalText,
                                    lineHeight = 18.sp,
                                ),
                            )
                        }
                    }

                    // Input bar
                    if (terminalState == TerminalState.RUNNING) {
                        TerminalInputBar(onSend = { vm.sendLine(it) })
                    } else {
                        // Exited - restart option
                        Surface(color = TerminalPromptBg) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "终端已退出",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TerminalText.copy(alpha = 0.6f),
                                    modifier = Modifier.weight(1f),
                                )
                                Button(onClick = { vm.startSession() }) {
                                    Text("重新启动")
                                }
                            }
                        }
                    }
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
            .background(TerminalBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "rootfs 未安装",
                style = MaterialTheme.typography.titleMedium,
                color = TerminalText,
            )
            Text(
                "请先在沙盒列表页安装 rootfs",
                style = MaterialTheme.typography.bodySmall,
                color = TerminalText.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun IdleState(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground),
        contentAlignment = Alignment.Center,
    ) {
        Button(onClick = onStart) {
            Text("启动终端")
        }
    }
}

@Composable
private fun TerminalInputBar(onSend: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }

    Surface(
        color = TerminalPromptBg,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$ ",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = Color(0xFF7EE787),
                ),
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = TerminalText,
                ),
                cursorBrush = SolidColor(TerminalText),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    onSend(input)
                    input = ""
                }),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = {
                    onSend(input)
                    input = ""
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = "发送",
                    tint = Color(0xFF7EE787),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
