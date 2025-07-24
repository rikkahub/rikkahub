package me.rerere.rikkahub.ui.pages.setting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.*
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyStaggeredGridState

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.setting_provider_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    ImportProviderButton {
                        vm.updateSettings(
                            settings.copy(
                                providers = listOf(it) + settings.providers
                            )
                        )
                    }
                    AddButton(vm)
                }
            )
        },
    ) { innerPadding ->
        val lazyListState = rememberLazyStaggeredGridState()
        val reorderableState = rememberReorderableLazyStaggeredGridState(lazyListState) { from, to ->
            val newProviders = settings.providers.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            vm.updateSettings(settings.copy(providers = newProviders))
        }
        LazyVerticalStaggeredGrid(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState,
            columns = StaggeredGridCells.Fixed(2)
        ) {
            items(settings.providers, key = { it.id }) { provider ->
                ReorderableItem(
                    state = reorderableState,
                    key = provider.id
                ) { isDragging ->
                    ProviderItem(
                        modifier = Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .fillMaxWidth(),
                        provider = provider,
                        dragHandle = {
                            val haptic = LocalHapticFeedback.current
                            IconButton(
                                onClick = {},
                                modifier = Modifier
                                    .longPressDraggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        }
                                    )
                            ) {
                                Icon(
                                    imageVector = Lucide.GripHorizontal,
                                    contentDescription = null
                                )
                            }
                        },
                        onClick = {
                            navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportProviderButton(
    onAdd: (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }

    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        handleQRResult(result, onAdd, toaster, context)
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            handleImageQRCode(it, onAdd, toaster, context)
        }
    }

    IconButton(
        onClick = {
            showImportDialog = true
        }
    ) {
        Icon(Lucide.Import, null)
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_import_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 主要操作：扫描二维码
                        Button(
                            onClick = {
                                showImportDialog = false
                                scanQrCodeLauncher.launch(null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Lucide.Camera,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_scan_qr_code),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        // 次要操作：从相册选择
                        OutlinedButton(
                            onClick = {
                                showImportDialog = false
                                pickImageLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Lucide.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_select_from_gallery),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false },
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }
}

private fun handleQRResult(
    result: QRResult,
    onAdd: (ProviderSetting) -> Unit,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context
) {
    runCatching {
        when (result) {
            is QRResult.QRError -> {
                toaster.show(
                    context.getString(
                        R.string.setting_provider_page_scan_error,
                        result
                    ), type = ToastType.Error
                )
            }

            QRResult.QRMissingPermission -> {
                toaster.show(
                    context.getString(R.string.setting_provider_page_no_permission),
                    type = ToastType.Error
                )
            }

            is QRResult.QRSuccess -> {
                val setting = decodeProviderSetting(result.content.rawValue ?: "")
                onAdd(setting)
                toaster.show(
                    context.getString(R.string.setting_provider_page_import_success),
                    type = ToastType.Success
                )
            }

            QRResult.QRUserCanceled -> {}
        }
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}

private fun handleImageQRCode(
    uri: Uri,
    onAdd: (ProviderSetting) -> Unit,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context
) {
    runCatching {
        // 使用ImageUtils解析二维码
        val qrContent = ImageUtils.decodeQRCodeFromUri(context, uri)

        if (qrContent.isNullOrEmpty()) {
            toaster.show(
                context.getString(R.string.setting_provider_page_no_qr_found),
                type = ToastType.Error
            )
            return
        }

        val setting = decodeProviderSetting(qrContent)
        onAdd(setting)
        toaster.show(
            context.getString(R.string.setting_provider_page_import_success),
            type = ToastType.Success
        )
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_image_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}


@Composable
private fun AddButton(vm: SettingVM) {
    var showDialog by remember { mutableStateOf(false) }
    val tempConfigs by vm.tempConfigs.collectAsStateWithLifecycle()
    var currentProviderType by remember { mutableStateOf(ProviderType.OpenAI) }

    IconButton(
        onClick = {
            vm.initTempConfigs(listOf(ProviderType.OpenAI, ProviderType.Google, ProviderType.Claude))
            showDialog = true
        }
    ) {
        Icon(Lucide.Plus, "Add")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.setting_provider_page_add_provider)) },
            text = {
                Column {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ProviderSetting.Types.forEachIndexed { index, type ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ProviderSetting.Types.size
                                ),
                                label = { Text(type.name) },
                                selected = currentProviderType == type,
                                onClick = { currentProviderType = type }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    tempConfigs[currentProviderType]?.let {
                        ProviderConfigure(
                            providerType = currentProviderType,
                            tempApiConfig = it,
                            onEdit = { newConfig ->
                                vm.updateTempConfig(currentProviderType, newConfig)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newProviders = tempConfigs.mapNotNull { (type, config) ->
                            when (type) {
                                ProviderType.OpenAI -> ProviderSetting.OpenAI(
                                    name = config.name,
                                    apiKey = config.apiKey,
                                    baseUrl = config.baseUrl,
                                    enabled = config.enabled,
                                    useResponseApi = config.useResponseApi
                                )
                                ProviderType.Google -> ProviderSetting.Google(
                                    name = config.name,
                                    apiKey = config.apiKey,
                                    baseUrl = config.baseUrl,
                                    enabled = config.enabled,
                                    vertexAI = config.vertexAI,
                                    location = config.location,
                                    projectId = config.projectId
                                )
                                ProviderType.Claude -> ProviderSetting.Claude(
                                    name = config.name,
                                    apiKey = config.apiKey,
                                    baseUrl = config.baseUrl,
                                    enabled = config.enabled
                                )
                            }
                        }
                        val currentSettings = vm.settings.value
                        vm.updateSettings(currentSettings.copy(providers = newProviders + currentSettings.providers))
                        showDialog = false
                    }
                ) {
                    Text(stringResource(R.string.setting_provider_page_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ProviderItem(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (provider.enabled) {
                MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
            } else MaterialTheme.colorScheme.errorContainer,
        ),
        onClick = {
            onClick()
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoAIIcon(
                    name = provider.name,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                dragHandle()
            }
            Column(
                modifier = Modifier,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleLarge
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = if (provider.enabled) TagType.SUCCESS else TagType.WARNING) {
                        Text(stringResource(if (provider.enabled) R.string.setting_provider_page_enabled else R.string.setting_provider_page_disabled))
                    }
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.setting_provider_page_model_count,
                                provider.models.size
                            )
                        )
                    }
                }
            }
        }
    }
}
