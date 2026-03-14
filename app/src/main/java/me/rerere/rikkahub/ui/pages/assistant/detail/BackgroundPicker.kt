package me.rerere.rikkahub.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.FormItem
import org.koin.compose.koinInject

@Composable
fun BackgroundPicker(
    modifier: Modifier = Modifier,
    background: String?,
    backgroundOpacity: Float = 1.0f,
    onUpdate: (String?) -> Unit
) {
    val filesManager: FilesManager = koinInject()
    var showPickOption by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val localUris = filesManager.createChatFilesByContents(listOf(it))
            localUris.firstOrNull()?.let { localUri ->
                onUpdate(localUri.toString())
            }
        }
    }

    val previewOpacity = backgroundOpacity.coerceIn(0f, 1f)

    FormItem(
        modifier = modifier,
        label = {
            Text(stringResource(R.string.assistant_page_chat_background))
        },
        description = {
            Text(stringResource(R.string.assistant_page_chat_background_desc))
        }
    ) {
        Button(
            onClick = {
                showPickOption = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (background != null) {
                    stringResource(R.string.assistant_page_change_background)
                } else {
                    stringResource(R.string.assistant_page_select_background)
                }
            )
        }

        if (background != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_background_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        onUpdate(null)
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_remove))
                }
            }

            AsyncImage(
                model = background,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(previewOpacity)
            )
        }
    }

    if (showPickOption) {
        AlertDialog(
            onDismissRequest = {
                showPickOption = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_select_background))
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showPickOption = false
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_select_from_gallery))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            urlInput = ""
                            showUrlInput = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_enter_image_url))
                    }
                    if (background != null) {
                        Button(
                            onClick = {
                                showPickOption = false
                                onUpdate(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.assistant_page_remove_background))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPickOption = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    if (showUrlInput) {
        AlertDialog(
            onDismissRequest = {
                showUrlInput = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_enter_image_url))
            },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.assistant_page_image_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/image.jpg") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onUpdate(urlInput.trim())
                            showUrlInput = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUrlInput = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }
}
