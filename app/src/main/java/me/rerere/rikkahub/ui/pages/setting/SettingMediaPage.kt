package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.RemoteFileStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.components.ui.s3ConnectionItems
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.MediaGenerationProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.label
import me.rerere.rikkahub.ui.pages.setting.components.typeName
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SettingMediaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    var selectedPage by remember { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_media))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (selectedPage == 0) {
                        AddMediaProviderButton {
                            vm.updateSettings(
                                settings.copy(
                                    mediaGenerationProviders = listOf(it) + settings.mediaGenerationProviders
                                )
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = selectedPage == 0,
                    onClick = { selectedPage = 0 },
                    icon = { Icon(HugeIcons.Image02, contentDescription = null) },
                    label = { Text(stringResource(R.string.setting_media_page_providers)) }
                )
                NavigationBarItem(
                    selected = selectedPage == 1,
                    onClick = { selectedPage = 1 },
                    icon = { Icon(HugeIcons.Database02, contentDescription = null) },
                    label = { Text("S3") }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when (selectedPage) {
            0 -> MediaProviderList(
                settings = settings,
                onUpdateSettings = vm::updateSettings,
                onEdit = { editingProvider = it },
                modifier = Modifier.padding(innerPadding)
            )

            1 -> MediaUploadS3Tab(
                settings = settings,
                onUpdateSettings = vm::updateSettings,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    editingProvider?.let { provider ->
        MediaProviderSheet(
            title = stringResource(R.string.setting_media_page_edit_provider),
            initial = provider,
            confirmText = stringResource(R.string.chat_page_save),
            onConfirm = { edited ->
                vm.updateSettings(
                    settings.copy(
                        mediaGenerationProviders = settings.mediaGenerationProviders.map {
                            if (it.id == provider.id) edited else it
                        }
                    )
                )
            },
            onDismiss = { editingProvider = null }
        )
    }
}

@Composable
private fun MediaProviderList(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    onEdit: (MediaGenerationProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.mediaGenerationProviders.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        onUpdateSettings(settings.copy(mediaGenerationProviders = newProviders))
    }
    var deleteTarget by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }

    if (settings.mediaGenerationProviders.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.setting_media_page_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            items(settings.mediaGenerationProviders, key = { it.id }) { provider ->
                ReorderableItem(
                    state = reorderableState,
                    key = provider.id
                ) { isDragging ->
                    MediaProviderItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(longPressReorder(isDragging)),
                        provider = provider,
                        onEdit = { onEdit(provider) },
                        onDelete = { deleteTarget = provider }
                    )
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                onUpdateSettings(
                    settings.copy(
                        mediaGenerationProviders = settings.mediaGenerationProviders.filter { it.id != target.id }
                    )
                )
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, deleteTarget?.name.orEmpty()))
    }
}

@Composable
private fun MediaProviderItem(
    provider: MediaGenerationProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onEdit,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
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
                    name = provider.name.ifEmpty { provider.typeName },
                    modifier = Modifier.size(32.dp)
                )

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = provider.name.ifEmpty { provider.typeName },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = provider.typeName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ItemActionMenu(
                    actions = listOf(
                        ItemAction(
                            text = stringResource(R.string.delete),
                            icon = HugeIcons.Delete01,
                            destructive = true,
                            onClick = onDelete,
                        ),
                    )
                )
            }

            val modelCounts = MediaKind.entries
                .map { kind -> kind to provider.models.count { it.kind == kind } }
                .filter { it.second > 0 }
            if (modelCounts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modelCounts.forEach { (kind, count) ->
                        Tag {
                            Text("${kind.label} $count")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMediaProviderButton(onAdd: (MediaGenerationProviderSetting) -> Unit) {
    var showTypeMenu by remember { mutableStateOf(false) }
    var newProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }

    Box {
        IconButton(
            onClick = { showTypeMenu = true }
        ) {
            Icon(HugeIcons.Add01, stringResource(R.string.setting_media_page_add_provider))
        }
        DropdownMenu(
            expanded = showTypeMenu,
            onDismissRequest = { showTypeMenu = false }
        ) {
            listOf<() -> MediaGenerationProviderSetting>(
                { MediaGenerationProviderSetting.OpenAI() },
                { MediaGenerationProviderSetting.Aliyun() },
                { MediaGenerationProviderSetting.Volcengine() },
                { MediaGenerationProviderSetting.MiniMax() },
                { MediaGenerationProviderSetting.OpenRouter() },
            ).forEach { create ->
                DropdownMenuItem(
                    text = { Text(remember { create() }.typeName) },
                    onClick = {
                        newProvider = create()
                        showTypeMenu = false
                    }
                )
            }
        }
    }

    newProvider?.let { provider ->
        MediaProviderSheet(
            title = stringResource(R.string.setting_media_page_add_provider),
            initial = provider,
            confirmText = stringResource(R.string.setting_tts_page_add),
            onConfirm = onAdd,
            onDismiss = { newProvider = null }
        )
    }
}

@Composable
private fun MediaProviderSheet(
    title: String,
    initial: MediaGenerationProviderSetting,
    confirmText: String,
    onConfirm: (MediaGenerationProviderSetting) -> Unit,
    onDismiss: () -> Unit
) {
    val bottomSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var currentProvider by remember(initial) { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = bottomSheetState,
        dragHandle = {
            BottomSheetDefaults.DragHandle()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .fillMaxHeight(0.8f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )

            MediaGenerationProviderConfigure(
                setting = currentProvider,
                onValueChange = { currentProvider = it },
                modifier = Modifier.weight(1f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }

                TextButton(
                    onClick = {
                        // 没填模型 ID 的行不保存
                        onConfirm(
                            currentProvider.copyProvider(
                                models = currentProvider.models.filter { it.modelId.isNotBlank() }
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(confirmText)
                }
            }
        }
    }
}

@Composable
private fun MediaUploadS3Tab(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    remoteFileStore: RemoteFileStore = koinInject(),
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }

    // 输入框以本地状态为准：设置要经过存储再回流，直接绑定的话打字快了会被旧值覆盖
    var config by remember { mutableStateOf(settings.uploadS3Config) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_media_page_s3_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            CardGroup {
                s3ConnectionItems(
                    config = config,
                    onUpdate = {
                        config = it
                        onUpdateSettings(settings.copy(uploadS3Config = it))
                    }
                )
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    scope.launch {
                        testing = true
                        remoteFileStore.testConnection()
                            .onSuccess {
                                toaster.show(
                                    context.getString(R.string.backup_page_connection_success),
                                    type = ToastType.Success
                                )
                            }
                            .onFailure {
                                toaster.show(
                                    context.getString(R.string.backup_page_connection_failed, it.message ?: ""),
                                    type = ToastType.Error
                                )
                            }
                        testing = false
                    }
                }
            ) {
                Text(stringResource(R.string.backup_page_test_connection))
            }
        }
    }
}
