package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FileDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.ExporterState

@Composable
fun <T> ExportDialog(
    exporter: ExporterState<T>,
    title: String? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(
                    onClick = {
                        exporter.exportToFile()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(Lucide.FileDown, null)
                        Column {
                            Text(
                                text = stringResource(R.string.export_to_file),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.export_to_file_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                OutlinedCard(
                    onClick = {
                        exporter.exportAndShare()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(Lucide.Share2, null)
                        Column {
                            Text(
                                text = stringResource(R.string.export_share),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.export_share_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.export_cancel))
            }
        }
    )
}
