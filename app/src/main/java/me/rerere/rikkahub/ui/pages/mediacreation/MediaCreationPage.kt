package me.rerere.rikkahub.ui.pages.mediacreation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ui.toast.ToastType
import me.rerere.ui.toast.ToasterState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.ImageToVideo
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.MediaCreationNode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.heroContainer
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.components.Tooltip
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * 一个媒体创作会话：一条时间线，最新的记录在最下面，紧挨着输入区，上一轮的产出可以直接放回输入区继续用。
 * 再来一次、修改后重新生成的结果留在原来那条记录的位置上，作为它的另一个版本。
 */
@Composable
fun MediaCreationPage(id: String) {
    val vm: MediaCreationVM = koinViewModel(parameters = { parametersOf(id) })
    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val uploadConfigured by vm.uploadConfigured.collectAsStateWithLifecycle()
    val recentOutputs by vm.recentOutputs.collectAsStateWithLifecycle()
    val selectedNodes by vm.selectedNodes.collectAsStateWithLifecycle()
    val nodes = vm.nodes.collectAsLazyPagingItems()

    var showUploadDialog by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedNodes != null) {
        vm.endSelection()
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is MediaCreationEvent.Error -> toaster.show(event.message, type = ToastType.Error)
                MediaCreationEvent.UploadNotConfigured -> showUploadDialog = true
                MediaCreationEvent.SessionGone -> navController.popBackStack()
            }
        }
    }

    // 从会话列表点进来时由列表项展开；切换途中带着圆角，到位后变成直角
    val heroCorner by LocalNavAnimatedContentScope.current.transition.animateDp(label = "heroCorner") { state ->
        if (state == EnterExitState.Visible) 0.dp else 24.dp
    }

    Scaffold(
        modifier = Modifier.heroContainer(mediaCreationHeroKey(id), RoundedCornerShape(heroCorner)),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedNodes?.let { stringResource(R.string.media_creation_page_selected_count, it.size) }
                            ?: session?.title?.ifBlank { null }
                            ?: stringResource(R.string.media_creation_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (selectedNodes == null && nodes.itemCount > 0) {
                        val label = stringResource(R.string.media_creation_page_select)
                        Tooltip(tooltip = { Text(label) }) {
                            IconButton(onClick = { vm.startSelection() }) {
                                Icon(HugeIcons.CheckList, contentDescription = label)
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // 底部的安全区和键盘留给输入区自己处理
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                MediaCreationTimeline(
                    vm = vm,
                    nodes = nodes,
                    editingNodeId = draft.editingNodeId,
                    selectedNodes = selectedNodes,
                    hasModels = settings.mediaGenerationProviders.any { it.models.isNotEmpty() },
                    onOpenMediaSettings = { navController.navigate(Screen.SettingMedia) },
                )
            }
            val selected = selectedNodes
            if (selected != null) {
                MediaCreationSelectionBar(
                    vm = vm,
                    selectedCount = selected.size,
                    allSelected = nodes.itemCount > 0 && selected.size >= nodes.itemCount,
                    onDelete = { confirmDeleteSelected = true },
                )
            } else {
                MediaCreationComposer(
                    vm = vm,
                    draft = draft,
                    selection = selection,
                    providers = settings.mediaGenerationProviders,
                    recentOutputs = recentOutputs,
                    uploadConfigured = uploadConfigured,
                    onOpenMediaSettings = { navController.navigate(Screen.SettingMedia) },
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = confirmDeleteSelected,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            confirmDeleteSelected = false
            vm.deleteSelected()
        },
        onDismiss = { confirmDeleteSelected = false },
    ) {
        Text(stringResource(R.string.media_creation_page_delete_selected_text, selectedNodes?.size ?: 0))
    }

    RikkaConfirmDialog(
        show = showUploadDialog,
        title = stringResource(R.string.media_creation_page_upload_dialog_title),
        confirmText = stringResource(R.string.media_creation_page_go_configure),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showUploadDialog = false
            navController.navigate(Screen.SettingMedia)
        },
        onDismiss = { showUploadDialog = false },
    ) {
        Text(stringResource(R.string.media_creation_page_upload_dialog_text))
    }
}

@Composable
private fun MediaCreationTimeline(
    vm: MediaCreationVM,
    nodes: LazyPagingItems<MediaCreationNode>,
    editingNodeId: Uuid?,
    selectedNodes: Set<Uuid>?,
    hasModels: Boolean,
    onOpenMediaSettings: () -> Unit,
) {
    val listState = rememberLazyListState()

    // 列表是倒序排布的，第 0 项就是最下面那条最新的记录。停在底部时，新记录出现后跟着露出来；
    // 往上翻看旧记录时不打扰。
    val newestId = nodes.newestId()
    LaunchedEffect(newestId) {
        if (newestId != null && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    // 自己提交的记录无论当前翻到哪里都滚过去；新版本出现在原来那一项上，它还在屏幕里时不动
    LaunchedEffect(vm) {
        vm.submitted.collect { submitted ->
            val key = submitted.nodeId.toString()
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
                listState.scrollToItem(submitted.position)
            }
        }
    }

    if (nodes.loadState.refresh is LoadState.NotLoading && nodes.itemCount == 0) {
        TimelineEmpty(hasModels = hasModels, onOpenMediaSettings = onOpenMediaSettings)
        return
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            count = nodes.itemCount,
            // 以项为单位：切换版本时原地换内容，不会被当成另一项
            key = nodes.itemKey { it.id.toString() },
        ) { index ->
            val node = nodes[index]
            if (node != null) {
                MediaCreationRecordItem(
                    node = node,
                    editing = node.id == editingNodeId,
                    selected = selectedNodes?.let { node.id in it },
                    vm = vm,
                )
            } else {
                // 还没加载到的记录先占位，保证列表总长度和滚动位置稳定
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {}
            }
        }
    }
}

/** 会话列表项和创作页共用的容器变换 key。 */
internal fun mediaCreationHeroKey(sessionId: String) = "media_creation_session_$sessionId"

private fun LazyPagingItems<MediaCreationNode>.newestId(): Uuid? =
    if (itemCount > 0) peek(0)?.id else null

@Composable
private fun TimelineEmpty(
    hasModels: Boolean,
    onOpenMediaSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 键盘弹出后高度不够时可以滚动，不至于被裁掉
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 12.dp)
                .size(112.dp)
                .background(
                    color = if (hasModels) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = (if (hasModels) MaterialShapes.Flower else MaterialShapes.Cookie4Sided).toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (hasModels) HugeIcons.ImageToVideo else HugeIcons.Settings03,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (hasModels) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (hasModels) {
            Text(
                text = stringResource(R.string.media_creation_page_empty_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = stringResource(R.string.media_creation_page_no_providers),
                style = MaterialTheme.typography.titleMediumEmphasized,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onOpenMediaSettings,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.media_creation_page_go_configure))
            }
        }
    }
}

/** 多选时代替输入区：退出多选、全选，以及对选中各项的批量操作。 */
@Composable
private fun MediaCreationSelectionBar(
    vm: MediaCreationVM,
    selectedCount: Int,
    allSelected: Boolean,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    // 和输入区占同一个位置、同一种外观
    Surface(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.largeIncreased,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val exitLabel = stringResource(R.string.media_creation_page_exit_selection)
            Tooltip(tooltip = { Text(exitLabel) }) {
                IconButton(onClick = { vm.endSelection() }) {
                    Icon(HugeIcons.Cancel01, contentDescription = exitLabel)
                }
            }
            TextButton(
                onClick = { if (allSelected) vm.clearSelection() else vm.selectAll() },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    stringResource(
                        if (allSelected) R.string.media_creation_page_deselect_all else R.string.media_creation_page_select_all
                    )
                )
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        saving = true
                        try {
                            saveSelected(context, toaster, vm)
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = selectedCount > 0 && !saving,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(HugeIcons.Download01, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(
                    stringResource(if (saving) R.string.media_creation_page_saving else R.string.media_creation_page_save)
                )
            }
            Button(
                onClick = onDelete,
                enabled = selectedCount > 0 && !saving,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(HugeIcons.Delete01, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.delete))
            }
        }
    }
}

/** 把选中各项当前显示的版本的产出逐个存进相册，一个失败不影响其余的。 */
private suspend fun saveSelected(context: Context, toaster: ToasterState, vm: MediaCreationVM) {
    val outputs = vm.getSelectedOutputs()
    if (outputs.isEmpty()) {
        toaster.show(context.getString(R.string.media_creation_page_nothing_to_save), type = ToastType.Info)
        return
    }
    val failures = outputs.mapNotNull { output ->
        try {
            saveMediaToGallery(context, vm.resolve(output.path), output.mimeType)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
    }
    when {
        failures.isEmpty() -> toaster.show(
            context.getString(R.string.media_creation_page_saved_files, outputs.size),
            type = ToastType.Success,
        )
        else -> {
            val reason = failures.first().message ?: context.getString(R.string.media_creation_page_save_failed)
            toaster.show(
                if (failures.size == outputs.size) {
                    reason
                } else {
                    context.getString(
                        R.string.media_creation_page_saved_files_partial,
                        outputs.size - failures.size,
                        failures.size,
                        reason,
                    )
                },
                type = ToastType.Error,
            )
        }
    }
}
