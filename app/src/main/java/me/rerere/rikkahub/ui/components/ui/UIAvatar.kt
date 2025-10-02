package me.rerere.rikkahub.ui.components.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.hooks.rememberAvatarShape
import me.rerere.rikkahub.utils.createChatFilesByContents

@Composable
fun TextAvatar(
    text: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer
) {
    Box(
        modifier = modifier
            .clip(shape = rememberAvatarShape(loading))
            .background(color)
            .size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.take(1).uppercase(),
            color = MaterialTheme.colorScheme.onSecondary,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 8.sp,
                maxFontSize = 32.sp,
                stepSize = 1.sp
            ),
            lineHeight = 0.8.em
        )
    }
}

@Composable
fun UIAvatar(
    name: String,
    value: Avatar,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onUpdate: ((Avatar) -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showPickOption by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val localUris = context.createChatFilesByContents(listOf(it))
            localUris.firstOrNull()?.let { localUri ->
                onUpdate?.invoke(Avatar.Image(localUri.toString()))
            }
        }
    }

    Surface(
        shape = rememberAvatarShape(loading),
        modifier = modifier.size(32.dp),
        onClick = {
            onClick?.invoke()
            if (onUpdate != null) showPickOption = true
        },
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            when (value) {
                is Avatar.Image -> {
                    AsyncImage(
                        model = value.url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }

                is Avatar.Emoji -> {
                    Text(
                        text = value.content,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 15.sp,
                            maxFontSize = 30.sp,
                        ),
                        lineHeight = 1.em,
                        modifier = Modifier.padding(2.dp)
                    )
                }

                is Avatar.Dummy -> {
                    Text(
                        text = name
                            .ifBlank { stringResource(R.string.user_default_name) }
                            .takeIf { it.isNotEmpty() }
                            ?.firstOrNull()?.toString()?.uppercase() ?: "A",
                        fontSize = 20.sp,
                        lineHeight = 1.em
                    )
                }
            }
        }
    }

    if (showPickOption) {
        AlertDialog(
            onDismissRequest = {
                showPickOption = false
            },
            title = {
                Text(text = stringResource(id = R.string.avatar_change_avatar))
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
                        Text(text = stringResource(id = R.string.avatar_pick_image))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            showEmojiPicker = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.avatar_pick_emoji))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            urlInput = ""
                            showUrlInput = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.avatar_input_url))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            onUpdate?.invoke(Avatar.Dummy)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.avatar_reset))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPickOption = false
                    }
                ) {
                    Text(stringResource(id = R.string.avatar_cancel))
                }
            }
        )
    }

    if (showEmojiPicker) {
        ModalBottomSheet(
            onDismissRequest = {
                showEmojiPicker = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            EmojiPicker(
                onEmojiSelected = { emoji ->
                    onUpdate?.invoke(Avatar.Emoji(content = emoji.emoji))
                    showEmojiPicker = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(16.dp)
            )
        }
    }

    if (showUrlInput) {
        AlertDialog(
            onDismissRequest = {
                showUrlInput = false
            },
            title = {
                Text(text = stringResource(id = R.string.avatar_url_dialog_title))
            },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(id = R.string.avatar_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onUpdate?.invoke(Avatar.Image(urlInput.trim()))
                            showUrlInput = false
                        }
                    }
                ) {
                    Text(stringResource(id = R.string.avatar_url_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUrlInput = false
                    }
                ) {
                    Text(stringResource(id = R.string.avatar_cancel))
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewUIAvatar() {
    var loading by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        UIAvatar(
            name = "John Doe",
            value = Avatar.Dummy,
            loading = false
        )

        UIAvatar(
            name = "John Doe",
            value = Avatar.Dummy,
            loading = loading,
        )

        Button(
            onClick = {
                loading = !loading
            }
        ) {
            Text("Toggle Loading")
        }
    }
}
