package me.rerere.rikkahub.ui.components.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Fullscreen
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.Lucide
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.modifier.onClick

/**
 * A multi-line text input component with a header and file import functionality.
 *
 * @param state The TextFieldState to manage the text input
 * @param modifier Modifier for the component
 * @param label The header label text
 * @param placeholder Placeholder text for the input field
 * @param minLines Minimum number of lines to display
 * @param maxLines Maximum number of lines to display
 * @param enabled Whether the text field is enabled
 * @param readOnly Whether the text field is read-only
 * @param supportedFileTypes Array of MIME types to filter in file picker (default: text files)
 * @param enableFullscreen Whether to enable fullscreen editing mode
 * @param onImportError Callback when file import fails (optional)
 */
@Composable
fun TextArea(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    minLines: Int = 5,
    maxLines: Int = 10,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    supportedFileTypes: Array<String> = arrayOf("text/*", "application/json"),
    enableFullscreen: Boolean = true,
    onImportError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isFullScreen by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()
                            ?.use { reader -> reader.readText() }
                            ?: error("Failed to read file")
                    }
                    state.setTextAndPlaceCursorAtEnd(content)
                    toaster.show(context.getString(R.string.text_area_import_success), type = ToastType.Success)
                } catch (e: Exception) {
                    e.printStackTrace()
                    val errorMessage = e.message ?: context.getString(R.string.text_area_import_failed)
                    onImportError?.invoke(errorMessage) ?: toaster.show(
                        message = errorMessage,
                        type = ToastType.Error
                    )
                }
            }
        }
    }


    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header with label, fullscreen and import button
        if (label.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (enableFullscreen) {
                        Icon(
                            imageVector = Lucide.Fullscreen,
                            contentDescription = stringResource(R.string.text_area_fullscreen_edit),
                            modifier = Modifier
                                .onClick(onClick = {
                                    isFullScreen = true
                                })
                                .size(24.dp)
                        )
                    }

                    Icon(
                        imageVector = Lucide.Import,
                        contentDescription = stringResource(R.string.text_area_import_from_file),
                        modifier = Modifier
                            .onClick(onClick = {
                                filePickerLauncher.launch(supportedFileTypes)
                            })
                            .size(24.dp)
                    )
                }
            }

            // Multi-line text input
            OutlinedTextField(
                state = state,
                modifier = Modifier
                    .fillMaxWidth(),
                placeholder = if (placeholder.isNotEmpty()) {
                    { Text(placeholder) }
                } else null,
                lineLimits = TextFieldLineLimits.MultiLine(
                    minHeightInLines = minLines,
                    maxHeightInLines = maxLines
                ),
                enabled = enabled,
                readOnly = readOnly
            )
        }
    }

    // Fullscreen editor dialog
    if (isFullScreen) {
        FullScreenTextEditor(
            state = state,
            label = label,
            placeholder = placeholder,
            onDismiss = { isFullScreen = false }
        )
    }
}

@Composable
private fun FullScreenTextEditor(
    state: TextFieldState,
    label: String,
    placeholder: String,
    onDismiss: () -> Unit
) {
    var editingText by remember(state.text.toString()) {
        mutableStateOf(state.text.toString())
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                state.setTextAndPlaceCursorAtEnd(editingText)
                                onDismiss()
                            }
                        ) {
                            Text(stringResource(R.string.text_area_save))
                        }
                    }
                    TextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        modifier = Modifier
                            .imePadding()
                            .fillMaxSize(),
                        shape = RoundedCornerShape(16.dp),
                        placeholder = if (placeholder.isNotEmpty()) {
                            { Text(placeholder) }
                        } else if (label.isNotEmpty()) {
                            { Text(label) }
                        } else null,
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}
