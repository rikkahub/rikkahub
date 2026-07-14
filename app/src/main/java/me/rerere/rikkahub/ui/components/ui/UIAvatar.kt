package me.rerere.rikkahub.ui.components.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import java.security.MessageDigest
import kotlin.math.abs
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.hooks.rememberAvatarShape
import org.koin.compose.koinInject
import java.io.File

@Composable
fun TextAvatar(
    text: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer
) {
    Box(
        modifier = modifier
            .then(Modifier.size(32.dp))
            .clip(shape = rememberAvatarShape(loading))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.take(1).uppercase(),
            color = LocalContentColor.current,
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
    val filesManager: FilesManager = koinInject()
    val context = LocalContext.current
    var showPickOption by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var preCropTempFile by remember { mutableStateOf<File?>(null) }

    fun saveAvatarImage(uri: Uri) {
        val localUris = filesManager.createChatFilesByContents(listOf(uri))
        localUris.firstOrNull()?.let { localUri ->
            onUpdate?.invoke(Avatar.Image(localUri.toString()))
        }
    }

    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            saveAvatarImage(croppedUri)
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        },
        aspectRatio = 1f to 1f,
        freeStyleCropEnabled = false
    )

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val tempFile = File(context.appTempFolder, "avatar_pick_${System.currentTimeMillis()}.jpg")
            runCatching {
                context.contentResolver.openInputStream(selectedUri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Failed to open input stream for $selectedUri")
                preCropTempFile?.delete()
                preCropTempFile = tempFile
                launchImageCrop(tempFile.toUri())
            }.onFailure {
                tempFile.delete()
                launchImageCrop(selectedUri)
            }
        }
    }

    Box(modifier = modifier.then(Modifier.size(32.dp))) {
        Surface(
            shape = rememberAvatarShape(loading),
            modifier = Modifier.fillMaxSize(),
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
                        me.rerere.rikkahub.ui.components.ui.ResolvedAsyncImage(
                            model = value.url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            crossfade = true,
                        )
                    }

                    is Avatar.Emoji -> {
                        Text(
                            text = value.content,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 15.sp,
                                maxFontSize = 30.sp,
                            ),
                            lineHeight = 0.8.em,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(5.dp)
                        )
                    }

                    is Avatar.Dummy -> {
                        ProceduralAvatar(
                            name = name,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Show edit icon when editable
        if (onUpdate != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Edit03,
                    contentDescription = "Edit",
                    modifier = Modifier
                        .size(10.dp)
                        .padding(1.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
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
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
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

@Composable
private fun ProceduralAvatar(name: String, modifier: Modifier = Modifier) {
    val (fromColor, toColor) = remember(name) {
        vercelAvatarColors(name.ifBlank { "?" })
    }
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(fromColor, toColor),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
        )
    }
}

private fun vercelAvatarColors(name: String): Pair<Color, Color> {
    val bytes = MessageDigest.getInstance("SHA-1").digest(name.toByteArray(Charsets.UTF_8))
    val sum = bytes.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
    val hue = (sum % 360).toFloat()
    return Pair(
        hslToColor(hue, 0.65f, 0.55f),
        hslToColor((hue + 120f) % 360f, 0.65f, 0.55f)
    )
}

private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hPrime = h / 60f
    val x = c * (1f - abs(hPrime % 2f - 1f))
    val (r1, g1, b1) = when {
        hPrime < 1f -> Triple(c, x, 0f)
        hPrime < 2f -> Triple(x, c, 0f)
        hPrime < 3f -> Triple(0f, c, x)
        hPrime < 4f -> Triple(0f, x, c)
        hPrime < 5f -> Triple(x, 0f, c)
        else        -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(r1 + m, g1 + m, b1 + m)
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
