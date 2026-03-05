package me.rerere.rikkahub.ui.components.textselection

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.TextSelectionAction

@Composable
fun localizedActionName(action: TextSelectionAction): String {
    if (action.isCustomPrompt) return action.name
    val resId = actionNameResId(action.id) ?: return action.name
    return stringResource(resId)
}

@StringRes
private fun actionNameResId(actionId: String): Int? = when (actionId) {
    "translate" -> R.string.text_selection_translate
    "explain" -> R.string.text_selection_explain
    "summarize" -> R.string.text_selection_summarize
    "ask" -> R.string.text_selection_ask
    "custom" -> R.string.text_selection_ask
    else -> null
}
