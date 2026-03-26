package me.rerere.rikkahub.ui.components.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * A reusable terminal view with inline keyboard input (no separate input bar).
 * Tap the terminal to show the soft keyboard; keystrokes are forwarded to [onInput] immediately.
 *
 * @param output The terminal output text to display.
 * @param state The current state of the terminal.
 * @param onInput Called with raw characters as the user types. Enter is sent as `\r`, backspace as `\u007F`.
 * @param onRestart Called when the user requests restart after the process exits.
 * @param modifier Modifier for the root container.
 */
@Composable
fun TerminalView(
    output: String,
    state: TerminalViewState,
    onInput: (String) -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    LaunchedEffect(state) {
        if (state == TerminalViewState.RUNNING) {
            focusRequester.requestFocus()
        }
    }

    Box(modifier = modifier.background(TerminalColors.Background)) {
        // Terminal output
        Text(
            text = output,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = TerminalColors.Text,
                lineHeight = 18.sp,
            ),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    focusRequester.requestFocus()
                }
                .padding(8.dp),
        )

        // Hidden input capture — invisible BasicTextField that receives keyboard events
        if (state == TerminalViewState.RUNNING) {
            TerminalInputCapture(
                onInput = onInput,
                focusRequester = focusRequester,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .alpha(0f),
            )
        }

        if (state == TerminalViewState.EXITED) {
            TerminalExitedBar(
                onRestart = onRestart,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Invisible text field that captures keyboard input and forwards it to the PTY.
 *
 * Keeps a single dummy space so that backspace events are detectable.
 * During IME composition, text accumulates until committed.
 */
@Composable
private fun TerminalInputCapture(
    onInput: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(DUMMY, TextRange(DUMMY.length))) }

    BasicTextField(
        value = tfv,
        onValueChange = { newValue ->
            val isComposing = newValue.composition != null

            if (isComposing) {
                // IME is composing — let it accumulate
                tfv = newValue
                return@BasicTextField
            }

            // Composition committed or regular typing
            val newText = newValue.text

            when {
                newText.length > DUMMY.length -> {
                    // New characters after the dummy space
                    val added = newText.substring(DUMMY.length)
                    onInput(added.replace('\n', '\r'))
                }

                newText.isEmpty() -> {
                    // Backspace deleted the dummy space
                    onInput("\u007F")
                }
            }

            // Reset to dummy
            tfv = TextFieldValue(DUMMY, TextRange(DUMMY.length))
        },
        modifier = modifier
            .focusRequester(focusRequester),
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            imeAction = ImeAction.None,
        ),
    )
}

@Composable
private fun TerminalExitedBar(
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = TerminalColors.InputBackground,
        modifier = modifier,
    ) {
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

private const val DUMMY = " "
