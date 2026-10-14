package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.components.ui.s3ConnectionItems
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.MediaGenerationProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.ProviderEditSheet
import me.rerere.rikkahub.ui.pages.setting.components.ProviderTypeSheet
import me.rerere.rikkahub.ui.pages.setting.components.label
import me.rerere.rikkahub.ui.pages.setting.components.typeName
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class MediaTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    PROVIDERS(HugeIcons.Image02, R.string.setting_media_page_providers),
    STORAGE(HugeIcons.Database02, R.string.setting_media_page_asset_storage);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingMediaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    val pagerState = rememberPagerState { MediaTab.entries.size }
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
                    if (MediaTab.entries[pagerState.targetPage] == MediaTab.PROVIDERS) {
                        AddMediaProviderButton {
                            vm.updateSettings { latest ->
                                latest.copy(
                                    mediaGenerationProviders = listOf(it) + latest.mediaGenerationProviders
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = MediaTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (MediaTab.entries[page]) {
                    MediaTab.PROVIDERS -> MediaProviderList(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        onEdit = { editingProvider = it },
                    )

                    MediaTab.STORAGE -> MediaUploadS3Tab(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                    )
                }
            }
        }
    }

    editingProvider?.let { provider ->
        MediaProviderSheet(
            title = stringResource(R.string.setting_media_page_edit_provider),
            initial = provider,
            confirmText = stringResource(R.string.chat_page_save),
            onConfirm = { edited ->
                vm.updateSettings { latest ->
                    latest.copy(
                        mediaGenerationProviders = latest.mediaGenerationProviders.map {
                            if (it.id == provider.id) edited else it
                        }
                    )
                }
            },
            onDismiss = { editingProvider = null }
        )
    }
}

@Composable
private fun MediaProviderList(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onEdit: (MediaGenerationProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.mediaGenerationProviders.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        onUpdateSettings { it.copy(mediaGenerationProviders = newProviders) }
    }
    var deleteTarget by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }

    if (settings.mediaGenerationProviders.isEmpty()) {
        ExtensionEmptyState(
            message = stringResource(R.string.setting_media_page_empty),
            icon = HugeIcons.Image02,
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
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
                onUpdateSettings { latest ->
                    latest.copy(
                        mediaGenerationProviders = latest.mediaGenerationProviders.filter { it.id != target.id }
                    )
                }
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
    val name = provider.name.ifEmpty { provider.typeName }

    Card(
        onClick = onEdit,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = name,
                modifier = Modifier.size(40.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = provider.typeName,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )

                val modelCounts = MediaKind.entries
                    .map { kind -> kind to provider.models.count { it.kind == kind } }
                    .filter { it.second > 0 }
                if (modelCounts.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        modelCounts.forEach { (kind, count) ->
                            Tag(type = TagType.INFO) {
                                Text("${kind.label} $count")
                            }
                        }
                    }
                }
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
    }
}

// 新建时先选类型，再进配置面板
@Composable
private fun AddMediaProviderButton(onAdd: (MediaGenerationProviderSetting) -> Unit) {
    var showTypeSheet by remember { mutableStateOf(false) }
    var newProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    val factories = remember {
        listOf<() -> MediaGenerationProviderSetting>(
            { MediaGenerationProviderSetting.OpenAI() },
            { MediaGenerationProviderSetting.Aliyun() },
            { MediaGenerationProviderSetting.Volcengine() },
            { MediaGenerationProviderSetting.MiniMax() },
            { MediaGenerationProviderSetting.OpenRouter() },
        )
    }

    IconButton(
        onClick = { showTypeSheet = true }
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_media_page_add_provider))
    }

    if (showTypeSheet) {
        ProviderTypeSheet(
            title = stringResource(R.string.setting_media_page_add_provider),
            types = factories,
            typeName = { create -> remember(create) { create() }.typeName },
            onSelect = { create ->
                showTypeSheet = false
                newProvider = create()
            },
            onDismiss = { showTypeSheet = false },
        )
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
    ProviderEditSheet(
        title = title,
        initial = initial,
        confirmText = confirmText,
        onConfirm = { provider ->
            // 没填模型 ID 的行不保存
            onConfirm(provider.copyProvider(models = provider.models.filter { it.modelId.isNotBlank() }))
        },
        onDismiss = onDismiss,
    ) { provider, onValueChange, modifier ->
        MediaGenerationProviderConfigure(
            setting = provider,
            onValueChange = onValueChange,
            modifier = modifier
        )
    }
}

@Composable
private fun MediaUploadS3Tab(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
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
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_media_page_s3_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        CardGroup {
            s3ConnectionItems(
                config = config,
                onUpdate = {
                    config = it
                    onUpdateSettings { latest -> latest.copy(uploadS3Config = it) }
                }
            )
        }

        CardGroup {
            item(
                onClick = if (!testing) {
                    {
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
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_test_connection)) },
                leadingContent = {
                    if (testing) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        CardGroupIcon(HugeIcons.Connect)
                    }
                },
            )
        }
    }
}
