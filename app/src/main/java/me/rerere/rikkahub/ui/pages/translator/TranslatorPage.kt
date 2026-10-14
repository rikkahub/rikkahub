package me.rerere.rikkahub.ui.pages.translator

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.ui.toast.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDataTransferVertical
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Translate
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.TranslationLanguages
import me.rerere.rikkahub.ui.components.ai.translationLanguageName
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.getText
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Composable
fun TranslatorPage(vm: TranslatorVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val inputText by vm.inputText.collectAsStateWithLifecycle()
    val translatedText by vm.translatedText.collectAsStateWithLifecycle()
    val targetLanguage by vm.targetLanguage.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()
    val history = vm.history.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.copied)
    val errorText = stringResource(R.string.translator_page_error)

    // settings.init 为 true 时设置还没加载完，这时不提示没选模型
    val modelMissing = !settings.init && settings.providers.findModelById(settings.translateModeId) == null

    LaunchedEffect(Unit) {
        vm.errorFlow.collect { error ->
            toaster.show(error.message ?: errorText, type = ToastType.Error)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.translator_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    ModelSelector(
                        modelId = settings.translateModeId,
                        onSelect = {
                            vm.updateSettings { latest -> latest.copy(translateModeId = it.id) }
                        },
                        providers = settings.providers,
                        type = ModelType.CHAT,
                        onlyIcon = true,
                    )
                }
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (modelMissing) {
                    item(key = "model_missing") {
                        ModelMissingTip(modifier = Modifier.animateItem())
                    }
                }

                item(key = "input") {
                    InputCard(
                        text = inputText,
                        onTextChange = vm::updateInputText,
                        onPaste = {
                            scope.launch {
                                clipboard.getClipEntry()?.clipData?.getText()?.let(vm::updateInputText)
                            }
                        },
                    )
                }

                item(key = "result") {
                    ResultCard(
                        text = translatedText,
                        targetLanguage = targetLanguage,
                        translating = translating,
                        onCopy = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, translatedText)))
                                toaster.show(copiedText, type = ToastType.Success)
                            }
                        },
                        onShare = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, translatedText)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        },
                        onUseAsInput = vm::useResultAsInput,
                    )
                }

                if (history.itemCount > 0) {
                    item(key = "history_header") {
                        TranslationHistoryHeader(
                            onClear = vm::clearHistory,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                items(
                    count = history.itemCount,
                    key = history.itemKey { it.id },
                ) { index ->
                    val entry = history[index] ?: return@items
                    TranslationHistoryItem(
                        entry = entry,
                        onClick = {
                            vm.restoreHistory(entry)
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        onDelete = { vm.deleteHistory(entry) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            TranslateToolbar(
                targetLanguage = targetLanguage,
                onLanguageSelected = vm::updateTargetLanguage,
                translating = translating,
                canTranslate = inputText.isNotBlank() && !modelMissing,
                onTranslate = vm::translate,
                onCancel = vm::cancelTranslation,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .offset(y = -ScreenOffset),
            )
        }
    }
}

// 短文本用大字号，长文本降下来，避免一屏只放得下几行
@Composable
private fun adaptiveTextStyle(text: String): TextStyle =
    if (text.length <= 80) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge

@Composable
private fun ModelMissingTip(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.translator_page_model_missing),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun InputCard(
    text: String,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.translator_page_input_placeholder)) },
                textStyle = adaptiveTextStyle(text),
                minLines = 3,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.translator_page_char_count, text.length),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = text.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    IconButton(onClick = { onTextChange("") }) {
                        Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.translator_page_clear_input))
                    }
                }
                FilledTonalIconButton(onClick = onPaste) {
                    Icon(HugeIcons.ClipboardPaste, contentDescription = stringResource(R.string.translator_page_paste))
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    text: String,
    targetLanguage: Locale,
    translating: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onUseAsInput: () -> Unit,
) {
    val hasResult = text.isNotBlank()
    val containerColor by animateColorAsState(
        if (hasResult) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    )

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = translationLanguageName(targetLanguage),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )

            AnimatedVisibility(translating) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (hasResult) {
                SelectionContainer {
                    MarkdownBlock(
                        content = text,
                        style = adaptiveTextStyle(text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (!translating) {
                Text(
                    text = stringResource(R.string.translator_page_result_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = hasResult && !translating,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
                    IconButton(onClick = onUseAsInput) {
                        Icon(HugeIcons.ArrowDataTransferVertical, contentDescription = stringResource(R.string.translator_page_use_as_input))
                    }
                    IconButton(onClick = onShare) {
                        Icon(HugeIcons.Share08, contentDescription = stringResource(R.string.share))
                    }
                    FilledTonalIconButton(onClick = onCopy) {
                        Icon(HugeIcons.Copy01, contentDescription = stringResource(R.string.copy))
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslateToolbar(
    targetLanguage: Locale,
    onLanguageSelected: (Locale) -> Unit,
    translating: Boolean,
    canTranslate: Boolean,
    onTranslate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = CustomColors.floatingToolbarColors,
        floatingActionButton = {
            FloatingToolbarDefaults.VibrantFloatingActionButton(
                onClick = {
                    when {
                        translating -> onCancel()
                        canTranslate -> onTranslate()
                    }
                },
            ) {
                AnimatedContent(translating) { isTranslating ->
                    if (isTranslating) {
                        Icon(HugeIcons.Stop, contentDescription = stringResource(R.string.translator_page_cancel))
                    } else {
                        Icon(HugeIcons.Translate, contentDescription = stringResource(R.string.translator_page_translate))
                    }
                }
            }
        },
    ) {
        Box {
            TextButton(
                onClick = { menuExpanded = true },
                // 默认是 primary，在 vibrant 工具栏上看不清
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
            ) {
                Icon(
                    imageVector = HugeIcons.LanguageCircle,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(translationLanguageName(targetLanguage))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                TranslationLanguages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(translationLanguageName(language)) },
                        onClick = {
                            onLanguageSelected(language)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
    }
}
