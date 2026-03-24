package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
class CommitOnFinishSliderState internal constructor(initialValue: Float) {
    var value by mutableFloatStateOf(initialValue)
        private set

    private var dragging by mutableStateOf(false)

    fun onValueChange(nextValue: Float) {
        dragging = true
        value = nextValue
    }

    fun onValueChangeFinished(
        externalValue: Float,
        onValueCommitted: (Float) -> Unit,
        normalize: (Float) -> Float = { it },
    ) {
        dragging = false
        val committedValue = normalize(value)
        value = committedValue
        if (committedValue != externalValue) {
            onValueCommitted(committedValue)
        }
    }

    internal fun sync(externalValue: Float) {
        if (!dragging && value != externalValue) {
            value = externalValue
        }
    }
}

@Composable
fun rememberCommitOnFinishSliderState(value: Float): CommitOnFinishSliderState {
    val state = remember { CommitOnFinishSliderState(value) }

    LaunchedEffect(value) {
        state.sync(value)
    }

    return state
}
