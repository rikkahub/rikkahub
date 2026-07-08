package me.rerere.rikkahub.ui.pages.setting.components

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import com.dokar.sonner.ToastType
import kotlinx.coroutines.withContext
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.CodeLanguage
import java.io.File

private const val TAG = "FilePreviewSheet"

@Composable
fun FilePreviewSheet(
    file: ManagedFileEntity,
    fileOnDisk: File,
    onDismiss: () -> Unit,
) {
    val mimeType = file.mimeType
    val fileName = file.displayName

    when {
        mimeType.startsWith("image/") -> {
            ImagePreviewDialog(
                images = listOf(fileOnDisk.absolutePath),
                onDismissRequest = onDismiss,
            )
        }

        mimeType.startsWith("video/") ||
                mimeType.startsWith("audio/") ||
                mimeType == "application/pdf" -> {
            LaunchSystemViewer(fileOnDisk, mimeType, onDismiss)
        }

        mimeType == "text/markdown" || fileName.endsWith(".md") -> {
            TextPreviewDialog(
                fileName = fileName,
                fileOnDisk = fileOnDisk,
                onDismiss = onDismiss,
                isMarkdown = true,
            )
        }

        mimeType.startsWith("text/") -> {
            val isCode = CodeLanguage.isCodeFile(fileName)
            if (isCode) {
                CodePreviewDialog(
                    fileName = fileName,
                    fileOnDisk = fileOnDisk,
                    onDismiss = onDismiss,
                )
            } else {
                TextPreviewDialog(
                    fileName = fileName,
                    fileOnDisk = fileOnDisk,
                    onDismiss = onDismiss,
                    isMarkdown = false,
                )
            }
        }

        else -> {
            LaunchSystemViewer(fileOnDisk, mimeType, onDismiss)
        }
    }
}

@Composable
private fun LaunchSystemViewer(fileOnDisk: File, mimeType: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    LaunchedEffect(Unit) {
        // Fire the intent first, then dismiss.  Dismissing first would
        // remove this composable and cancel the LaunchedEffect coroutine
        // before the intent has a chance to launch.
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fileOnDisk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file with system viewer (mime=$mimeType)", e)
            // Fallback: try without specific mime type
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    fileOnDisk,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open file with fallback viewer", e2)
                toaster.show(
                    message = context.getString(R.string.setting_files_page_unable_to_read),
                    type = ToastType.Error,
                )
            }
        }
        onDismiss()
    }
}

@Composable
private fun TextPreviewDialog(
    fileName: String,
    fileOnDisk: File,
    onDismiss: () -> Unit,
    isMarkdown: Boolean,
) {
    var content by remember { mutableStateOf<String?>(null) }
    var showAsMarkdown by remember { mutableStateOf(isMarkdown) }
    val plainTextScrollState = rememberScrollState()
    val markdownScrollState = rememberScrollState()

    LaunchedEffect(fileOnDisk) {
        content = withContext(Dispatchers.IO) {
            runCatching { fileOnDisk.readText() }.onFailure {
                Log.w(TAG, "Failed to read text file: ${fileOnDisk.name}", it)
            }.getOrNull()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (!isMarkdown) {
                    TextButton(onClick = { showAsMarkdown = !showAsMarkdown }) {
                        Text(
                            text = if (showAsMarkdown) stringResource(R.string.setting_files_page_view_plain_text) else stringResource(R.string.setting_files_page_view_markdown),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Content
            val displayContent = content
            if (displayContent != null) {
                if (showAsMarkdown) {
                    MarkdownBlock(
                        content = displayContent,
                        style = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(markdownScrollState),
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = displayContent,
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(plainTextScrollState),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.setting_files_page_unable_to_read),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodePreviewDialog(
    fileName: String,
    fileOnDisk: File,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(fileOnDisk) {
        content = withContext(Dispatchers.IO) {
            runCatching { fileOnDisk.readText() }.onFailure {
                Log.w(TAG, "Failed to read code file: ${fileOnDisk.name}", it)
            }.getOrNull()
        }
    }

    val language = remember(fileName) {
        CodeLanguage.languageForOrText(fileName)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))

            val darkMode = LocalDarkMode.current
            val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette

            val displayContent = content
            if (displayContent != null) {
                HighlightText(
                    code = displayContent,
                    language = language,
                    colors = colorPalette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.setting_files_page_unable_to_read),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
