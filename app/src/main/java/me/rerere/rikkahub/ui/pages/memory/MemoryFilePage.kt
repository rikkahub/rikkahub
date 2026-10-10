package me.rerere.rikkahub.ui.pages.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant

/**
 * 记忆文件的详情页：查看时按「更新时间 / 摘要 / 正文」分段展示，编辑时改的是带 frontmatter 的完整原文。
 *
 * @param memoryId 记忆库的 id（助手 id 或全局记忆库）
 * @param path 文件路径，为 null 时新建
 */
@Composable
fun MemoryFilePage(memoryId: String, path: String?) {
    val vm: MemoryFileVM = koinViewModel(
        parameters = {
            parametersOf(memoryId, path)
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val isNew = path == null
    val file = (state as? MemoryFileState.Loaded)?.file
    // 新文件没有内容可看，直接进编辑
    var editing by rememberSaveable { mutableStateOf(isNew) }
    var draftPath by rememberSaveable { mutableStateOf("") }
    var draftContent by rememberSaveable { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val draftPathValid = MemoryFile.normalizePath(draftPath) != null
    val draftContentValid = draftContent.toByteArray().size <= MemoryFile.MAX_BYTES
    val canSave = draftContentValid && if (isNew) draftPathValid else draftContent != file?.content

    fun onSaved(result: MemoryWriteResult) {
        when (result) {
            is MemoryWriteResult.Success -> if (isNew) navController.popBackStack() else editing = false
            is MemoryWriteResult.Conflict -> toaster.show(
                message = "A memory already exists at this path",
                type = ToastType.Error,
            )

            is MemoryWriteResult.Rejected -> toaster.show(message = result.reason, type = ToastType.Error)
        }
    }

    // 编辑已有文件时，返回是放弃修改回到详情，而不是离开页面
    BackHandler(enabled = editing && !isNew) { editing = false }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = MemoryFile(path = path ?: draftPath, content = "", updatedAt = 0).title
                            .ifEmpty { "New memory" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = {
                    if (path != null) {
                        Text(text = file?.path ?: path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (editing && !isNew) {
                        FilledTonalIconButton(
                            onClick = { editing = false },
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = CustomColors.listItemColors.containerColor
                            ),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.cancel),
                            )
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (editing) {
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                if (isNew) {
                                    vm.create(draftPath, draftContent, ::onSaved)
                                } else {
                                    vm.save(draftContent, ::onSaved)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.assistant_page_save))
                        }
                    } else if (file != null) {
                        IconButton(
                            onClick = {
                                draftContent = file.content
                                editing = true
                            },
                        ) {
                            Icon(
                                imageVector = HugeIcons.PencilEdit01,
                                contentDescription = stringResource(R.string.edit),
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            editing -> MemoryFileEditor(
                innerPadding = innerPadding,
                path = draftPath.takeIf { isNew },
                onPathChange = { draftPath = it },
                pathValid = draftPathValid,
                content = draftContent,
                onContentChange = { draftContent = it },
                contentValid = draftContentValid,
            )

            file != null -> MemoryFileDetail(innerPadding = innerPadding, file = file)

            state is MemoryFileState.Missing -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "This memory no longer exists",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            vm.delete { navController.popBackStack() }
        },
        onDismiss = { showDeleteDialog = false },
        text = {
            Text(text = file?.path.orEmpty())
        }
    )
}

@Composable
private fun MemoryFileDetail(
    innerPadding: PaddingValues,
    file: MemoryFile,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        CardGroup {
            item(
                overlineContent = { Text("Last updated") },
                headlineContent = { Text(Instant.ofEpochMilli(file.updatedAt).toLocalDateTime()) },
            )
            if (file.description.isNotEmpty()) {
                item(
                    overlineContent = { Text("Summary") },
                    headlineContent = { Text(file.description) },
                )
            }
            if (file.aliases.isNotEmpty()) {
                item(
                    overlineContent = { Text("Also known as") },
                    headlineContent = { Text(file.aliases.joinToString(", ")) },
                )
            }
            if (file.body.isNotBlank()) {
                formItem {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 和上面几项的 overline 保持一致
                        Text(
                            text = "Details",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            MarkdownBlock(
                                content = file.displayBody,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * @param path 新建时要填的路径，编辑已有文件时为 null
 */
@Composable
private fun MemoryFileEditor(
    innerPadding: PaddingValues,
    path: String?,
    onPathChange: (String) -> Unit,
    pathValid: Boolean,
    content: String,
    onContentChange: (String) -> Unit,
    contentValid: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (path != null) {
            OutlinedTextField(
                value = path,
                onValueChange = onPathChange,
                label = { Text("Path") },
                placeholder = { Text("/topics/food.md") },
                isError = path.isNotEmpty() && !pathValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = content,
            onValueChange = onContentChange,
            isError = !contentValid,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}
