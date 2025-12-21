package me.rerere.rikkahub.ui.components.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FileDown
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.QrCode
import com.composables.icons.lucide.ScanLine
import com.composables.icons.lucide.Share2
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import me.rerere.rikkahub.data.export.ExporterState
import me.rerere.rikkahub.data.export.ImporterState

@Composable
fun <T> ImportActions(
    importer: ImporterState<T>,
    modifier: Modifier = Modifier,
) {
    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> {
                importer.importJson(result.content.rawValue.orEmpty())
            }
            else -> {}
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { importer.importFromFile() }) {
            Icon(
                imageVector = Lucide.FileUp,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "文件",
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        OutlinedButton(onClick = { scanQrCodeLauncher.launch(null) }) {
            Icon(
                imageVector = Lucide.ScanLine,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "扫码",
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
fun <T> ExportActions(
    exporter: ExporterState<T>,
    modifier: Modifier = Modifier,
) {
    var showQRCodeDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { exporter.exportToFile() }) {
            Icon(
                imageVector = Lucide.FileDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "文件",
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        OutlinedButton(onClick = { exporter.exportAndShare() }) {
            Icon(
                imageVector = Lucide.Share2,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "分享",
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        OutlinedButton(onClick = { showQRCodeDialog = true }) {
            Icon(
                imageVector = Lucide.QrCode,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "二维码",
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    if (showQRCodeDialog) {
        AlertDialog(
            onDismissRequest = { showQRCodeDialog = false },
            confirmButton = {
                TextButton(onClick = { showQRCodeDialog = false }) {
                    Text("关闭")
                }
            },
            text = {
                Column {
                    QRCode(
                        value = exporter.value,
                        modifier = Modifier.size(256.dp)
                    )
                }
            }
        )
    }
}
