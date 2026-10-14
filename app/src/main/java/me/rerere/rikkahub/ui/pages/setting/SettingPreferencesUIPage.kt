package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesUIPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val chatFontFamily = rememberChatFontFamily(displaySetting)

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings { it.copy(displaySetting = setting) }
    }

    val importSuccessMsg = stringResource(R.string.setting_display_page_custom_font_import_success)
    val importFailedMsg = stringResource(R.string.setting_display_page_custom_font_import_failed)
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importCustomChatFontInternal(context, uri)
                }
            }.onSuccess { importedFont ->
                updateDisplaySetting(
                    displaySetting.copy(
                        chatFontFamily = ChatFontFamily.CUSTOM,
                        chatCustomFontPath = importedFont.relativePath,
                        chatCustomFontName = importedFont.displayName,
                    )
                )
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_ui))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_message_display_settings)) },
                ) {
                    switchItem(
                        checked = displaySetting.showUserAvatar,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showUserAvatar = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
                    )
                    switchItem(
                        checked = displaySetting.showAssistantBubble,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showAssistantBubble = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_title)) },
                    )
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_bubble_opacity_title)) },
                        trailingContent = { CardGroupValue("${(displaySetting.bubbleOpacity * 100).roundToInt()}%") },
                    ) {
                        Slider(
                            value = displaySetting.bubbleOpacity,
                            onValueChange = {
                                updateDisplaySetting(displaySetting.copy(bubbleOpacity = it))
                            },
                            valueRange = 0.1f..1.0f,
                            steps = 8,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.showModelIcon,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showModelIcon = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title)) },
                    )
                    switchItem(
                        checked = displaySetting.showModelName,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showModelName = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
                    )
                    switchItem(
                        checked = displaySetting.showDateTimeInMessage,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showDateTimeInMessage = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_title)) },
                    )
                    switchItem(
                        checked = displaySetting.showTokenUsage,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showTokenUsage = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_title)) },
                    )
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = displaySetting.showThinkingContent,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showThinkingContent = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
                    )
                    switchItem(
                        checked = displaySetting.autoCloseThinking,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(autoCloseThinking = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title)) },
                    )
                    switchItem(
                        checked = displaySetting.enableLatexRendering,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableLatexRendering = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_title)) },
                    )
                    switchItem(
                        checked = displaySetting.enableQuoteHighlight,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(enableQuoteHighlight = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_quote_highlight_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_quote_highlight_title)) },
                    )
                }
            }

            item {
                CardGroup {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                    ) {
                        ConnectedChoiceButtons(
                            options = ChatFontFamily.entries,
                            selected = displaySetting.chatFontFamily,
                            onSelect = { family ->
                                if (family == ChatFontFamily.CUSTOM && displaySetting.chatCustomFontPath.isBlank()) {
                                    fontPickerLauncher.launch(CustomFontMimeTypesUI)
                                } else {
                                    updateDisplaySetting(displaySetting.copy(chatFontFamily = family))
                                }
                            },
                            minHeight = 64.dp,
                        ) { family, _ ->
                            // 每个按钮用对应的字体写一个样字
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Aa",
                                    fontFamily = family.toFontFamilyUI(chatFontFamily),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = family.labelUI(),
                                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 11.sp),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    item(
                        onClick = { fontPickerLauncher.launch(CustomFontMimeTypesUI) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_custom_font_title)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.chatCustomFontName.isNotBlank()) {
                                    displaySetting.chatCustomFontName
                                } else {
                                    stringResource(R.string.setting_display_page_custom_font_not_imported)
                                }
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { fontPickerLauncher.launch(CustomFontMimeTypesUI) }
                                ) {
                                    Icon(
                                        HugeIcons.FileImport,
                                        contentDescription = stringResource(
                                            R.string.setting_display_page_custom_font_import
                                        )
                                    )
                                }
                                if (displaySetting.chatCustomFontPath.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            deleteCustomChatFontInternal(context, displaySetting.chatCustomFontPath)
                                            updateDisplaySetting(
                                                displaySetting.copy(
                                                    chatFontFamily = ChatFontFamily.DEFAULT,
                                                    chatCustomFontPath = "",
                                                    chatCustomFontName = "",
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            HugeIcons.Delete02,
                                            contentDescription = stringResource(
                                                R.string.setting_display_page_custom_font_remove
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
                        trailingContent = { CardGroupValue("${(displaySetting.fontSizeRatio * 100).toInt()}%") },
                    ) {
                        Slider(
                            value = displaySetting.fontSizeRatio,
                            onValueChange = {
                                updateDisplaySetting(displaySetting.copy(fontSizeRatio = it))
                            },
                            valueRange = 0.5f..2f,
                            steps = 11,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // 预览放在凹下去的底色里，和设置项本身区分开
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            MarkdownBlock(
                                content = stringResource(R.string.setting_display_page_font_size_preview),
                                modifier = Modifier.padding(12.dp),
                                style = LocalTextStyle.current.copy(
                                    fontSize = LocalTextStyle.current.fontSize * displaySetting.fontSizeRatio,
                                    lineHeight = LocalTextStyle.current.lineHeight * displaySetting.fontSizeRatio,
                                    fontFamily = chatFontFamily
                                )
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_code_display_settings)) },
                ) {
                    switchItem(
                        checked = displaySetting.codeBlockAutoWrap,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(codeBlockAutoWrap = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_title)) },
                    )
                    switchItem(
                        checked = displaySetting.codeBlockAutoCollapse,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(codeBlockAutoCollapse = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_title)) },
                    )
                    switchItem(
                        checked = displaySetting.showLineNumbers,
                        onCheckedChange = { updateDisplaySetting(displaySetting.copy(showLineNumbers = it)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_title)) },
                    )
                }
            }
        }
    }
}

private val CustomFontMimeTypesUI = arrayOf(
    "font/*",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

private val CustomFontExtensionsUI = setOf("ttf", "otf", "ttc")

private data class ImportedChatFontUI(
    val relativePath: String,
    val displayName: String,
)

@Composable
private fun ChatFontFamily.labelUI(): String = when (this) {
    ChatFontFamily.DEFAULT -> stringResource(R.string.setting_display_page_chat_font_family_default)
    ChatFontFamily.SERIF -> stringResource(R.string.setting_display_page_chat_font_family_serif)
    ChatFontFamily.MONOSPACE -> stringResource(R.string.setting_display_page_chat_font_family_monospace)
    ChatFontFamily.CUSTOM -> stringResource(R.string.setting_display_page_chat_font_family_custom)
}

private fun ChatFontFamily.toFontFamilyUI(customFontFamily: FontFamily): FontFamily = when (this) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> customFontFamily
}

private fun importCustomChatFontInternal(context: Context, uri: Uri): ImportedChatFontUI {
    val displayName = FileUtils.getFileNameFromUri(context, uri)?.takeIf { it.isNotBlank() } ?: "custom_font"
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in CustomFontExtensionsUI }
        ?: "ttf"
    val fontDir = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
    val targetFile = File(fontDir, "chat_font.${System.currentTimeMillis()}.$extension")
    val tempFile = File(fontDir, "chat_font_import.tmp")

    try {
        tempFile.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected font")

        runCatching {
            Typeface.createFromFile(tempFile)
        }.onFailure { error ->
            throw IllegalArgumentException(error.message ?: "Invalid font file", error)
        }

        replaceCustomChatFontInternal(fontDir, tempFile, targetFile)
    } catch (error: Throwable) {
        tempFile.delete()
        throw error
    }

    val relativePath = FileUtils.getRelativePathInFilesDir(context.filesDir, targetFile)
        ?: "${FileFolders.FONTS}/${targetFile.name}"
    return ImportedChatFontUI(relativePath = relativePath, displayName = displayName)
}

private fun replaceCustomChatFontInternal(fontDir: File, tempFile: File, targetFile: File) {
    val existingFiles = fontDir.listFiles { file ->
        file.isFile && file.name.startsWith("chat_font.") && file != tempFile
    }?.toList().orEmpty()
    val backups = existingFiles.map { file ->
        file to File(fontDir, "previous_${file.name}").also { it.delete() }
    }

    try {
        backups.forEach { (file, backup) ->
            check(file.renameTo(backup)) { "Unable to prepare existing font for replacement" }
        }
        check(tempFile.renameTo(targetFile)) { "Unable to save selected font" }
        backups.forEach { (_, backup) -> backup.delete() }
    } catch (error: Throwable) {
        tempFile.delete()
        backups.forEach { (file, backup) ->
            if (!file.exists() && backup.exists()) {
                backup.renameTo(file)
            }
        }
        throw error
    }
}

private fun deleteCustomChatFontInternal(context: Context, relativePath: String) {
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return
    val fontFile = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return
    if (fontFile.path.startsWith("${filesDir.path}${File.separator}")) {
        fontFile.delete()
    }
}
