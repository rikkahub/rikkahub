package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.ImageToVideo
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.MediaCreationSession
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toMessageTimeString
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * 媒体创作的入口：列出所有会话，点进去是这个会话的创作页（[MediaCreationPage]）。
 */
@Composable
fun MediaCreationSessionsPage(vm: MediaCreationSessionsVM = koinViewModel()) {
    val navController = LocalNavController.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var renaming by remember { mutableStateOf<MediaCreationSession?>(null) }
    var deleting by remember { mutableStateOf<MediaCreationSession?>(null) }
    val listState = rememberLazyListState()
    // 列表滚动后把按钮收成只剩图标，少挡一点内容
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val open: (Uuid) -> Unit = { id ->
        navController.navigate(Screen.MediaCreation(id.toString())) { launchSingleTop = true }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.media_creation_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.media_creation_page_new_session)) },
                icon = { Icon(HugeIcons.Add01, contentDescription = null) },
                onClick = { vm.newSession(open) },
                expanded = fabExpanded,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            // 底部留出悬浮按钮的位置，最后一项的菜单才点得到
            contentPadding = innerPadding + PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            if (sessions?.isEmpty() == true) {
                item {
                    EmptySessionsState(modifier = Modifier.fillParentMaxHeight(0.7f))
                }
            }

            val items = sessions.orEmpty()
            itemsIndexed(items, key = { _, session -> session.id.toString() }) { index, session ->
                SessionItem(
                    session = session,
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = items.size),
                    resolve = vm::resolve,
                    onOpen = { open(session.id) },
                    onRename = { renaming = session },
                    onDelete = { deleting = session },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    renaming?.let { session ->
        RenameSessionDialog(
            initialTitle = session.title,
            onConfirm = { title ->
                vm.rename(session.id, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    RikkaConfirmDialog(
        show = deleting != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleting?.let { vm.delete(it.id) }
            deleting = null
        },
        onDismiss = { deleting = null },
    ) {
        val session = deleting
        Text(
            stringResource(
                if ((session?.activeCount ?: 0) > 0) {
                    R.string.media_creation_page_delete_session_active_text
                } else {
                    R.string.media_creation_page_delete_session_text
                },
                session?.title?.ifBlank { null } ?: stringResource(R.string.media_creation_page_new_session),
                session?.nodeCount ?: 0,
            )
        )
    }
}

@Composable
private fun EmptySessionsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .size(128.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.ImageToVideo,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.media_creation_page_sessions_empty),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.media_creation_page_sessions_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val SessionLeadingSize = 48.dp

// 每个会话按 id 固定分到一个形状，列表看起来不那么整齐划一
private val sessionShapes = listOf(
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Pentagon,
    MaterialShapes.Flower,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Gem,
    MaterialShapes.Sunny,
)

@Composable
private fun SessionItem(
    session: MediaCreationSession,
    shapes: ListItemShapes,
    resolve: (String) -> File,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        onClick = onOpen,
        shapes = shapes,
        modifier = modifier,
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        leadingContent = {
            val shape = sessionShapes[session.id.hashCode().mod(sessionShapes.size)].toShape()
            val cover = session.cover
            when {
                // 有任务在生成时换成变形的加载指示器
                session.activeCount > 0 -> ContainedLoadingIndicator(modifier = Modifier.size(SessionLeadingSize))

                // 最近的产出裁成这个会话的形状当封面
                cover != null -> MediaThumbnail(
                    file = remember(cover.path) { resolve(cover.path) },
                    isVideo = cover.isVideo,
                    poster = cover.posterPath?.let(resolve),
                    playIconSize = 8.dp,
                    modifier = Modifier
                        .size(SessionLeadingSize)
                        .clip(shape),
                )

                else -> Box(
                    modifier = Modifier
                        .size(SessionLeadingSize)
                        .background(color = MaterialTheme.colorScheme.secondaryContainer, shape = shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.ImageToVideo,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
        supportingContent = {
            val time = remember(session.updateAt) {
                session.updateAt.atZone(ZoneId.systemDefault()).toLocalDateTime().toMessageTimeString()
            }
            Text(
                text = listOfNotNull(
                    stringResource(R.string.media_creation_page_record_count, session.nodeCount),
                    stringResource(R.string.media_creation_page_active_count, session.activeCount)
                        .takeIf { session.activeCount > 0 },
                    time,
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.common_rename),
                        icon = HugeIcons.PencilEdit01,
                        onClick = onRename,
                    ),
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
        },
    ) {
        Text(
            text = session.title.ifBlank { stringResource(R.string.media_creation_page_new_session) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RenameSessionDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_creation_page_rename_session)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
