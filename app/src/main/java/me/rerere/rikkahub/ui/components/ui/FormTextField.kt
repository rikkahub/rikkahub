package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.flow.distinctUntilChanged

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
) {
    key(externalKey) {
        val state = rememberTextFieldState(initialText = value)
        val latestOnValueChange by rememberUpdatedState(onValueChange)
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        // `lastEmitted` is the last text this field is responsible for (the seed, or a value it
        // emitted/adopted). `awaitingAck` means we have emitted a user edit the external source has
        // not echoed back yet — while it is set, EVERY incoming external value is a stale in-flight
        // echo and must not touch the editor (this is what prevents the cursor jump and the
        // blur-before-echo clobber). A value-only "adopt when different" cannot tell a stale echo
        // from a real reset apart; the ack flag can.
        var lastEmitted by remember { mutableStateOf(value) }
        var awaitingAck by remember { mutableStateOf(false) }

        LaunchedEffect(state) {
            snapshotFlow { state.text.toString() }
                .distinctUntilChanged()
                .collect { text ->
                    // Only a genuine USER edit differs from what we last seeded/emitted/adopted, so
                    // the seed emission and the programmatic adopt-write never echo back out (no
                    // write-back loop, no spurious initial write through trim/parse call sites).
                    if (text != lastEmitted) {
                        lastEmitted = text
                        awaitingAck = true
                        latestOnValueChange(text)
                    }
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
            trailingIcon = trailingIcon,
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
