package me.rerere.rikkahub.ui.pages.setting.locallm

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R

/**
 * One-time consent dialog that fires the first time the user toggles a Local provider
 * tile ON. Shows the model name, the size on disk, and a Continue / Cancel pair.
 *
 * Title and message strings are passed in so the same composable can serve both
 * runtimes (LiteRT's Gemma, llama.cpp's Qwen).
 */
@Composable
fun LocalLlmDownloadDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.local_llm_download_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.local_llm_download_cancel))
            }
        },
    )
}
