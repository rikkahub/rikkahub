package me.rerere.rikkahub.ui.pages.log

import android.content.ClipData
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dokar.sonner.ToastType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogPage() {
    var logs by remember { mutableStateOf(Logging.getRecentLogs()) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Logs") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            Logging.clear()
                            logs = Logging.getRecentLogs()
                        }
                    ) {
                        Icon(HugeIcons.Delete01, null)
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
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
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
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
                LogBodySection(title = "Request Body", body = body)
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

        log.responseBody?.let { body ->
            item {
                LogBodySection(title = "Response Body", body = body)
            }
        }
    }
}

@Composable
private fun LogBodySection(title: String, body: String) {
    val parsedJson = remember(body) {
        runCatching { JsonInstantPretty.parseToJsonElement(body) }.getOrNull()
    }
    val displayText = remember(body, parsedJson) {
        parsedJson?.let { JsonInstantPretty.encodeToString(it) } ?: body
    }
    val lineCount = remember(displayText) { displayText.lineSequence().count().coerceAtLeast(1) }
    val colorScheme = MaterialTheme.colorScheme
    val highlightedText = remember(
        parsedJson,
        colorScheme.primary,
        colorScheme.onSurface,
        colorScheme.onSurfaceVariant,
        colorScheme.outline
    ) {
        parsedJson?.let {
            buildJsonAnnotatedString(
                element = it,
                colors = JsonHighlightColors(
                    key = colorScheme.primary,
                    string = Color(0xFF6A8759),
                    number = Color(0xFF6897BB),
                    boolean = Color(0xFFCC7832),
                    punctuation = colorScheme.onSurfaceVariant,
                    nullValue = colorScheme.outline
                )
            )
        }
    }
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.copied)

    HorizontalDivider()
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (parsedJson != null) "Formatted JSON" else "Raw Body",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (parsedJson != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = "$lineCount lines",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = {
                        scope.launch {
                            clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("request-log", displayText)))
                            toaster.show(copiedText, type = ToastType.Success)
                        }
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = stringResource(R.string.text_selection_copy)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.text_selection_copy))
                }
            }

            HorizontalDivider()

            SelectionContainer {
                Text(
                    text = highlightedText ?: AnnotatedString(displayText),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetbrainsMono,
                        lineHeight = 20.sp
                    ),
                    softWrap = true
                )
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

private data class JsonHighlightColors(
    val key: Color,
    val string: Color,
    val number: Color,
    val boolean: Color,
    val punctuation: Color,
    val nullValue: Color
)

private fun buildJsonAnnotatedString(
    element: JsonElement,
    colors: JsonHighlightColors
): AnnotatedString = buildAnnotatedString {
    appendJsonElement(element = element, depth = 0, colors = colors)
}

private fun AnnotatedString.Builder.appendJsonElement(
    element: JsonElement,
    depth: Int,
    colors: JsonHighlightColors
) {
    when (element) {
        is JsonObject -> {
            if (element.isEmpty()) {
                appendJsonPunctuation("{}", colors.punctuation)
                return
            }

            appendJsonPunctuation("{", colors.punctuation)
            val entries = element.entries.toList()
            entries.forEachIndexed { index, (key, value) ->
                appendLineBreakWithIndent(depth + 1)
                withStyle(SpanStyle(color = colors.key)) {
                    append("\"")
                    append(escapeJsonString(key))
                    append("\"")
                }
                appendJsonPunctuation(": ", colors.punctuation)
                appendJsonElement(value, depth + 1, colors)
                if (index != entries.lastIndex) {
                    appendJsonPunctuation(",", colors.punctuation)
                }
            }
            appendLineBreakWithIndent(depth)
            appendJsonPunctuation("}", colors.punctuation)
        }

        is JsonArray -> {
            if (element.isEmpty()) {
                appendJsonPunctuation("[]", colors.punctuation)
                return
            }

            appendJsonPunctuation("[", colors.punctuation)
            element.forEachIndexed { index, value ->
                appendLineBreakWithIndent(depth + 1)
                appendJsonElement(value, depth + 1, colors)
                if (index != element.lastIndex) {
                    appendJsonPunctuation(",", colors.punctuation)
                }
            }
            appendLineBreakWithIndent(depth)
            appendJsonPunctuation("]", colors.punctuation)
        }

        JsonNull -> {
            withStyle(SpanStyle(color = colors.nullValue)) {
                append("null")
            }
        }

        is JsonPrimitive -> {
            when {
                element.isString -> {
                    withStyle(SpanStyle(color = colors.string)) {
                        append("\"")
                        append(escapeJsonString(element.content))
                        append("\"")
                    }
                }

                element.booleanOrNull != null -> {
                    withStyle(SpanStyle(color = colors.boolean)) {
                        append(element.content)
                    }
                }

                element.longOrNull != null || element.doubleOrNull != null -> {
                    withStyle(SpanStyle(color = colors.number)) {
                        append(element.content)
                    }
                }

                else -> {
                    withStyle(SpanStyle(color = colors.punctuation)) {
                        append(element.content)
                    }
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendJsonPunctuation(text: String, color: Color) {
    withStyle(SpanStyle(color = color)) {
        append(text)
    }
}

private fun AnnotatedString.Builder.appendLineBreakWithIndent(depth: Int) {
    append("\n")
    repeat(depth) {
        append("  ")
    }
}

private fun escapeJsonString(text: String): String = buildString(text.length + 8) {
    text.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> append(char)
        }
    }
}

@Composable
private fun TextLogCard(log: LogEntry.TextLog) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
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
