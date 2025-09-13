package me.rerere.rikkahub.ui.pages.imggen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Images
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Trash2
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.saveMessageImage
import org.koin.androidx.compose.koinViewModel
import java.io.File

@Composable
fun ImageGenPage(
    modifier: Modifier = Modifier,
    vm: ImgGenVM = koinViewModel()
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }
    BackHandler(isGenerating) {
        showCancelDialog = true
    }
    if (showCancelDialog) {
        CancelDialog(
            onDismiss = { showCancelDialog = false },
            onConfirm = {
                showCancelDialog = false
                vm.cancelGeneration()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.imggen_page_title))
                },
                navigationIcon = {
                    BackButton()
                }
            )
        },
        bottomBar = {
            BottomBar(pagerState, scope)
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) { page ->
            when (page) {
                0 -> ImageGenScreen(vm = vm)
                1 -> ImageGalleryScreen(vm = vm)
            }
        }
    }
}

@Composable
private fun CancelDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imggen_page_cancel_generation_title)) },
        text = { Text(stringResource(R.string.imggen_page_cancel_generation_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.imggen_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.imggen_page_cancel))
            }
        }
    )
}

@Composable
private fun BottomBar(
    pagerState: PagerState,
    scope: CoroutineScope
) {
    NavigationBar {
        NavigationBarItem(
            selected = 0 == pagerState.currentPage,
            label = {
                Text(stringResource(R.string.imggen_page_title))
            },
            icon = {
                Icon(Lucide.Palette, null)
            },
            onClick = {
                scope.launch {
                    pagerState.animateScrollToPage(0)
                }
            }
        )

        NavigationBarItem(
            selected = 1 == pagerState.currentPage,
            label = {
                Text(stringResource(R.string.imggen_page_gallery))
            },
            icon = {
                Icon(Lucide.Images, null)
            },
            onClick = {
                scope.launch {
                    pagerState.animateScrollToPage(1)
                }
            }
        )
    }
}

@Composable
private fun ImageGenScreen(
    vm: ImgGenVM,
) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val numberOfImages by vm.numberOfImages.collectAsStateWithLifecycle()
    val aspectRatio by vm.aspectRatio.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val currentGeneratedImages by vm.currentGeneratedImages.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(error) {
        error?.let { errorMessage ->
            toaster.show(message = errorMessage, type = ToastType.Error)
            vm.clearError()
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            (0 until minOf(2, currentGeneratedImages.size)).forEach { index ->
                val image = currentGeneratedImages[index]
                var showPreview by remember { mutableStateOf(false) }
                AsyncImage(
                    model = File(image.filePath),
                    contentDescription = null,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showPreview = true },
                    contentScale = ContentScale.Crop
                )

                if (showPreview) {
                    ImagePreviewDialog(
                        images = listOf(image.filePath),
                        onDismissRequest = { showPreview = false },
                    )
                }
            }
        }
        InputBar(
            prompt = prompt,
            vm = vm,
            isGenerating = isGenerating,
            onShowSettings = { showSettingsSheet = true },
            modifier = Modifier
        )
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            vm = vm,
            settings = settings,
            numberOfImages = numberOfImages,
            aspectRatio = aspectRatio,
            scope = scope,
            sheetState = sheetState,
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun InputBar(
    prompt: String,
    vm: ImgGenVM,
    isGenerating: Boolean,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onShowSettings
        ) {
            Icon(Lucide.Settings2, null)
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = vm::updatePrompt,
            placeholder = { Text(stringResource(R.string.imggen_page_prompt_placeholder)) },
            modifier = Modifier.weight(1f),
            minLines = 1,
            maxLines = 5,
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        FilledTonalIconButton(
            onClick = {
                if (!isGenerating) {
                    vm.generateImage()
                } else {
                    vm.cancelGeneration()
                }
            },
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Lucide.Send,
                    contentDescription = stringResource(R.string.imggen_page_generate_image)
                )
            }
        }
    }
}

@Composable
private fun ImageGalleryScreen(
    vm: ImgGenVM,
) {
    val generatedImages = vm.generatedImages.collectAsLazyPagingItems()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { generatedImages.refresh() },
        state = pullToRefreshState
    ) {
        if (generatedImages.itemCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Lucide.Images,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.imggen_page_no_generated_images),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = generatedImages.itemCount,
                    key = generatedImages.itemKey { it.id },
                    contentType = generatedImages.itemContentType { "GeneratedImage" }
                ) { index ->
                    val image = generatedImages[index]
                    image?.let {
                        var showPreview by remember { mutableStateOf(false) }

                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                AsyncImage(
                                    model = File(it.filePath),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clickable { showPreview = true },
                                    contentScale = ContentScale.Crop
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = it.model,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = it.prompt.take(20) + if (it.prompt.length > 20) "..." else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }

                                    Row {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(it.prompt))
                                                toaster.show(
                                                    message = "Prompt copied to clipboard",
                                                    type = ToastType.Success
                                                )
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Lucide.Copy,
                                                contentDescription = "Copy prompt",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        context.saveMessageImage("file://${it.filePath}")
                                                        toaster.show(
                                                            message = context.getString(R.string.imggen_page_image_saved_success),
                                                            type = ToastType.Success
                                                        )
                                                    } catch (e: Exception) {
                                                        toaster.show(
                                                            message = context.getString(
                                                                R.string.imggen_page_save_failed,
                                                                e.message
                                                            ),
                                                            type = ToastType.Error
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Lucide.Save,
                                                contentDescription = stringResource(R.string.imggen_page_save),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { vm.deleteImage(it) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Lucide.Trash2,
                                                contentDescription = stringResource(R.string.imggen_page_delete),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (showPreview) {
                            ImagePreviewDialog(
                                images = listOf(it.filePath),
                                onDismissRequest = { showPreview = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsBottomSheet(
    vm: ImgGenVM,
    settings: Settings,
    numberOfImages: Int,
    aspectRatio: ImageAspectRatio,
    scope: CoroutineScope,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.imggen_page_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_model_selection)) },
                description = { Text(stringResource(R.string.imggen_page_model_selection_desc)) }
            ) {
                ModelSelector(
                    modelId = settings.imageGenerationModelId,
                    providers = settings.providers,
                    type = ModelType.IMAGE,
                    onlyIcon = false,
                    onSelect = { model ->
                        scope.launch {
                            vm.settingsStore.update { oldSettings ->
                                oldSettings.copy(imageGenerationModelId = model.id)
                            }
                        }
                    }
                )
            }

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_generation_count)) },
                description = { Text(stringResource(R.string.imggen_page_generation_count_desc)) }
            ) {
                OutlinedNumberInput(
                    value = numberOfImages,
                    onValueChange = vm::updateNumberOfImages,
                    modifier = Modifier.width(120.dp)
                )
            }

            FormItem(
                label = { Text(stringResource(R.string.imggen_page_aspect_ratio)) },
                description = { Text(stringResource(R.string.imggen_page_aspect_ratio_desc)) }
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ImageAspectRatio.entries.forEach { ratio ->
                        FilterChip(
                            selected = aspectRatio == ratio,
                            onClick = { vm.updateAspectRatio(ratio) },
                            label = {
                                Text(
                                    stringResource(
                                        when (ratio) {
                                            ImageAspectRatio.SQUARE -> R.string.imggen_page_aspect_ratio_square
                                            ImageAspectRatio.LANDSCAPE -> R.string.imggen_page_aspect_ratio_landscape
                                            ImageAspectRatio.PORTRAIT -> R.string.imggen_page_aspect_ratio_portrait
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
