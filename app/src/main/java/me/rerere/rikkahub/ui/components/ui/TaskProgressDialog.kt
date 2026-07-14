package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/**
 * Shared blocking progress dialog for long tasks (compress history, file upload, import, etc.).
 *
 * @param progress null = indeterminate; 0f..1f = determinate bar
 */
@Composable
fun TaskProgressDialog(
    visible: Boolean,
    title: String,
    message: String? = null,
    progress: Float? = null,
    detail: String? = null,
    cancellable: Boolean = false,
    onCancel: (() -> Unit)? = null,
    onDismissRequest: (() -> Unit)? = null,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = {
            if (cancellable) {
                onCancel?.invoke()
            } else {
                onDismissRequest?.invoke()
            }
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!message.isNullOrBlank()) {
                    Text(message)
                }
                val fraction = progress?.coerceIn(0f, 1f)
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (cancellable && onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
