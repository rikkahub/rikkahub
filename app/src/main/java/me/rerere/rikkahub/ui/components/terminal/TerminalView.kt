package me.rerere.rikkahub.ui.components.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01

object TerminalColors {
    val Background = Color(0xFF0D1117)
    val Text = Color(0xFFE6EDF3)
    val InputBackground = Color(0xFF161B22)
    val Accent = Color(0xFF7EE787)
}

enum class TerminalViewState {
    RUNNING, EXITED
}

/**
 * A reusable terminal view component that displays terminal output and an input bar.
 *
 * @param output The terminal output text to display.
 * @param state The current state of the terminal (RUNNING or EXITED).
 * @param onSendLine Called when the user submits a line of input.
 * @param onRestart Called when the user requests to restart after exit.
 * @param modifier Modifier for the root container.
 */
@Composable
fun TerminalView(
    output: String,
    state: TerminalViewState,
    onSendLine: (String) -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(modifier = modifier.background(TerminalColors.Background)) {
        // Output area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 44.dp) // Reserve space for input/exit bar
                .verticalScroll(scrollState)
                .padding(8.dp),
        ) {
            SelectionContainer {
                Text(
                    text = output,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = TerminalColors.Text,
                        lineHeight = 18.sp,
                    ),
                )
            }
        }

        // Bottom bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
        ) {
            if (state == TerminalViewState.RUNNING) {
                TerminalInputBar(onSend = onSendLine)
            } else {
                TerminalExitedBar(onRestart = onRestart)
            }
        }
    }
}

@Composable
private fun TerminalInputBar(onSend: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }

    Surface(
        color = TerminalColors.InputBackground,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = TerminalColors.Text,
                ),
                cursorBrush = SolidColor(TerminalColors.Text),
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
                    tint = TerminalColors.Accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun TerminalExitedBar(onRestart: () -> Unit) {
    Surface(color = TerminalColors.InputBackground) {
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
                color = TerminalColors.Text.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onRestart) {
                Text("重新启动")
            }
        }
    }
}
