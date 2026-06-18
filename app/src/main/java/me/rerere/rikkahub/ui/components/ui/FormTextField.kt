package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.rikkahub.R

@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    externalKey: Any,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    outputTransformation: OutputTransformation? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = LocalTextStyle.current,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    enabled: Boolean = true,
    readOnly: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    enableFullscreen: Boolean = false,
    // When true, the field also commits on every edit (not just on focus-loss/dispose). Use for fields
    // whose value is read synchronously by an action button (e.g. an image-gen / translator prompt with
    // a Send/Translate FAB) so the action never reads stale pre-blur text. Leave false for heavy
    // settings/assistant pages where per-keystroke commits would recompose the whole page.
    liveUpdate: Boolean = false,
) {
    key(externalKey) {
        val state = rememberTextFieldState(initialText = value)
        val latestOnValueChange by rememberUpdatedState(onValueChange)
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        var fullscreenOpen by remember { mutableStateOf(false) }
        // `lastEmitted` is the last text this field is responsible for (the seed, or a value it
        // emitted/adopted). `awaitingAck` means we have emitted a user edit the external source has
        // not echoed back yet — while it is set, EVERY incoming external value is a stale in-flight
        // echo and must not touch the editor (this is what prevents the cursor jump and the
        // blur-before-echo clobber). A value-only "adopt when different" cannot tell a stale echo
        // from a real reset apart; the ack flag can.
        var lastEmitted by remember { mutableStateOf(value) }
        var awaitingAck by remember { mutableStateOf(false) }
        var wasFocused by remember { mutableStateOf(false) }

        fun commitCurrentText() {
            val text = state.text.toString()
            if (shouldCommitFormTextField(text, lastEmitted)) {
                lastEmitted = text
                awaitingAck = true
                latestOnValueChange(text)
            }
        }

        LaunchedEffect(focused) {
            if (wasFocused && !focused) {
                commitCurrentText()
            }
            wasFocused = focused
        }

        if (liveUpdate) {
            LaunchedEffect(state) {
                snapshotFlow { state.text.toString() }
                    .distinctUntilChanged()
                    .collect { commitCurrentText() }
            }
        }

        DisposableEffect(state) {
            onDispose {
                commitCurrentText()
            }
        }

        LaunchedEffect(value, focused, state) {
            when (
                reconcileFormTextField(
                    localText = state.text.toString(),
                    incomingExternalValue = value,
                    awaitingAck = awaitingAck,
                    focused = focused,
                )
            ) {
                FormTextFieldReconciliation.Ack -> awaitingAck = false
                FormTextFieldReconciliation.KeepLocal -> Unit
                is FormTextFieldReconciliation.AdoptExternal -> {
                    state.setTextAndPlaceCursorAtEnd(value)
                    lastEmitted = value
                    awaitingAck = false
                }
            }
        }

        val fullscreenIcon: (@Composable () -> Unit)? = if (enableFullscreen) {
            {
                IconButton(
                    onClick = { fullscreenOpen = true },
                    enabled = enabled && !readOnly,
                ) {
                    Icon(
                        imageVector = HugeIcons.FullScreen,
                        contentDescription = stringResource(R.string.text_area_fullscreen_edit),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        } else {
            null
        }
        val effectiveTrailingIcon: (@Composable () -> Unit)? = when {
            trailingIcon != null && fullscreenIcon != null -> {
                {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        trailingIcon()
                        fullscreenIcon()
                    }
                }
            }
            fullscreenIcon != null -> fullscreenIcon
            else -> trailingIcon
        }

        OutlinedTextField(
            state = state,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            label = label?.let { content ->
                { content() }
            },
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = effectiveTrailingIcon,
            supportingText = supportingText,
            isError = isError,
            outputTransformation = outputTransformation,
            keyboardOptions = keyboardOptions,
            lineLimits = if (singleLine) {
                TextFieldLineLimits.SingleLine
            } else {
                TextFieldLineLimits.MultiLine(
                    minHeightInLines = minLines,
                    maxHeightInLines = maxLines,
                )
            },
            scrollState = scrollState,
            shape = shape,
            colors = colors,
            interactionSource = interactionSource,
        )

        if (fullscreenOpen) {
            FullScreenTextEditor(
                state = state,
                label = "",
                placeholder = "",
                onDismiss = { fullscreenOpen = false },
                onSave = { commitCurrentText() },
            )
        }
    }
}

internal sealed interface FormTextFieldReconciliation {
    /** External caught up to the local buffer: clear the pending-ack flag, do NOT rewrite the editor. */
    data object Ack : FormTextFieldReconciliation
    /** Stale in-flight echo, or an external change while the user is editing: leave the buffer alone. */
    data object KeepLocal : FormTextFieldReconciliation
    /** A genuine external change (e.g. a reset button) on an idle, in-sync field: adopt it. */
    data object AdoptExternal : FormTextFieldReconciliation
}

/**
 * Pure decision for how an incoming external [incomingExternalValue] should affect the editor.
 *
 * - If it equals the local text, the external source has acknowledged our edit -> [Ack].
 * - While [awaitingAck] (we have an un-echoed local edit), ANY non-matching external value is a stale
 *   in-flight echo and must be ignored -> [KeepLocal]. This is the property that kills the cursor
 *   jump: a value-only "adopt when different" cannot distinguish a stale echo from a real reset, so it
 *   would clobber freshly typed text on blur before the echo lands; the ack flag can.
 * - Once in sync and the field is idle (not [focused]), a differing external value is a real change
 *   (reset / another control) -> [AdoptExternal].
 * - In sync but focused: a non-matching external change mid-focus is left alone -> [KeepLocal].
 */
internal fun reconcileFormTextField(
    localText: String,
    incomingExternalValue: String,
    awaitingAck: Boolean,
    focused: Boolean,
): FormTextFieldReconciliation = when {
    incomingExternalValue == localText -> FormTextFieldReconciliation.Ack
    awaitingAck -> FormTextFieldReconciliation.KeepLocal
    !focused -> FormTextFieldReconciliation.AdoptExternal
    else -> FormTextFieldReconciliation.KeepLocal
}

internal fun shouldCommitFormTextField(
    localText: String,
    lastEmitted: String,
): Boolean = localText != lastEmitted

internal data class FormTextFieldBufferSnapshot(
    val externalKey: Any,
    val localText: String,
    val syncedExternalValue: String,
    val didReset: Boolean = false,
)

internal fun resetFormTextFieldBufferOnKeyChange(
    previous: FormTextFieldBufferSnapshot,
    externalKey: Any,
    value: String,
): FormTextFieldBufferSnapshot {
    return if (previous.externalKey == externalKey) {
        previous.copy(didReset = false)
    } else {
        FormTextFieldBufferSnapshot(
            externalKey = externalKey,
            localText = value,
            syncedExternalValue = value,
            didReset = true,
        )
    }
}
