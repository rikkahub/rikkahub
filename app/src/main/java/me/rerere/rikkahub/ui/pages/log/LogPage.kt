package me.rerere.rikkahub.ui.pages.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.launch
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogPage() {
    var logs by remember { mutableStateOf(Logging.getRecentLogs()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            Logging.clear()
                            logs = Logging.getRecentLogs()
                        }
                    ) {
                        Icon(Lucide.Trash2, null)
                    }
                }
            )
        }
    ) { contentPadding ->
        UnifiedLogList(
            logs = logs,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        )
    }
}

@Composable
private fun UnifiedLogList(logs: List<LogEntry>, modifier: Modifier = Modifier) {
    var selectedLog by remember { mutableStateOf<LogEntry.RequestLog?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val sortedLogs = remember(logs) { logs.sortedByDescending { it.timestamp } }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        items(sortedLogs, key = { it.id }, contentType = { it.javaClass.simpleName }) { log ->
            when (log) {
                is LogEntry.RequestLog -> RequestLogCard(
                    log = log,
                    onClick = {
                        selectedLog = log
                        scope.launch { sheetState.show() }
                    }
                )

                is LogEntry.TextLog -> TextLogCard(log = log)
            }
        }
    }

    selectedLog?.let { log ->
        ModalBottomSheet(
            onDismissRequest = { selectedLog = null },
            sheetState = sheetState
        ) {
            RequestLogDetail(log)
        }
    }
}

@Composable
private fun RequestLogCard(log: LogEntry.RequestLog, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = log.method,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = log.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                maxLines = 2
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                log.responseCode?.let { code ->
                    Text(
                        text = "Status: $code",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (code in 200..299) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                log.durationMs?.let { duration ->
                    Text(
                        text = "${duration}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            log.error?.let { error ->
                Text(
                    text = "Error: $error",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RequestLogDetail(log: LogEntry.RequestLog) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Request Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            DetailSection("Time", dateFormat.format(Date(log.timestamp)))
        }

        item {
            DetailSection("URL", log.url)
        }

        item {
            DetailSection("Method", log.method)
        }

        log.responseCode?.let { code ->
            item {
                DetailSection("Status Code", code.toString())
            }
        }

        log.durationMs?.let { duration ->
            item {
                DetailSection("Duration", "${duration}ms")
            }
        }

        log.error?.let { error ->
            item {
                DetailSection("Error", error)
            }
        }

        if (log.requestHeaders.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    text = "Request Headers",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            log.requestHeaders.forEach { (key, value) ->
                item {
                    HeaderItem(key, value)
                }
            }
        }

        log.requestBody?.let { body ->
            item {
                HorizontalDivider()
                Text(
                    text = "Request Body",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    HighlightText(
                        code = JsonInstantPretty.encodeToString(
                            JsonInstantPretty.parseToJsonElement(body)
                        ),
                        language = "json",
                        fontFamily = JetbrainsMono,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (log.responseHeaders.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    text = "Response Headers",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            log.responseHeaders.forEach { (key, value) ->
                item {
                    HeaderItem(key, value)
                }
            }
        }
    }
}

@Composable
private fun DetailSection(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = JetbrainsMono
        )
    }
}

@Composable
private fun HeaderItem(key: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetbrainsMono
        )
    }
}

@Composable
private fun TextLogCard(log: LogEntry.TextLog) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = log.tag,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono
            )
        }
    }
}
