package me.rerere.rikkahub.ui.pages.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.memoryTitleOf
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.SelectableContent
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
 * 记忆文件的详情页：查看时按「更新时间 / 摘要 / 别名 / 正文」分段展示，编辑时也是这几项各填各的，
 * 保存时再拼回带 frontmatter 的原文。
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
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var draftDescription by rememberSaveable { mutableStateOf("") }
    var draftAliases by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // 别名输入框里还没加进列表的那个
    var draftAliasInput by rememberSaveable { mutableStateOf("") }
    var draftBody by rememberSaveable { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val pathConflictMessage = stringResource(R.string.memory_file_page_path_conflict)

    val normalizedDraftPath = MemoryFile.normalizePath(draftPath)
    val draftPathValid = normalizedDraftPath != null
    // 新建时还没有文件，拿路径造一个空的来拼内容
    val base = file ?: remember(normalizedDraftPath) {
        MemoryFile(path = normalizedDraftPath.orEmpty(), content = "", updatedAt = 0)
    }
    // 输入框里打了字没点添加就保存的，也算一个别名，不然就悄悄丢了
    val draftContent = remember(base, draftTitle, draftDescription, draftAliases, draftAliasInput, draftBody) {
        base.contentWith(draftTitle, draftDescription, draftAliases.withAlias(draftAliasInput), draftBody)
    }
    // 和没改过的各项拼出来的内容比，而不是和原文比：原文的 YAML 写法可能和这里拼的不一样，
    // 直接比的话什么都没改也会显示成可以保存
    val unchangedContent = remember(file) {
        file?.run { contentWith(title, description, aliases, editableBody) }
    }
    // 和仓库层一样只拦变大的超限：迁移来的旧记忆可能本来就超限，得允许分几次删小
    val currentSize = remember(file) { file?.content?.toByteArray()?.size ?: 0 }
    val draftSize = draftContent.toByteArray().size
    val draftContentValid = draftSize <= MemoryFile.MAX_BYTES || draftSize <= currentSize
    val canSave = draftContentValid && if (isNew) draftPathValid else draftContent != unchangedContent

    fun onSaved(result: MemoryWriteResult) {
        when (result) {
            is MemoryWriteResult.Success -> if (isNew) navController.popBackStack() else editing = false
            is MemoryWriteResult.Conflict -> toaster.show(
                message = pathConflictMessage,
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
                        text = (file?.title ?: MemoryFile(path = path ?: draftPath, content = "", updatedAt = 0).title)
                            .ifEmpty { stringResource(R.string.memory_file_page_new) },
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
                                draftTitle = file.title
                                draftDescription = file.description
                                draftAliases = file.aliases
                                draftAliasInput = ""
                                draftBody = file.editableBody
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
                title = draftTitle,
                onTitleChange = { draftTitle = it },
                titlePlaceholder = memoryTitleOf(base.name),
                description = draftDescription,
                onDescriptionChange = { draftDescription = it },
                aliases = draftAliases,
                onAliasesChange = { draftAliases = it },
                aliasInput = draftAliasInput,
                onAliasInputChange = { draftAliasInput = it },
                body = draftBody,
                onBodyChange = { draftBody = it },
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
                    text = stringResource(R.string.memory_file_page_missing),
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
                overlineContent = { Text(stringResource(R.string.memory_file_page_last_updated)) },
                headlineContent = { Text(Instant.ofEpochMilli(file.updatedAt).toLocalDateTime()) },
            )
            if (file.description.isNotEmpty()) {
                item(
                    overlineContent = { Text(stringResource(R.string.memory_file_page_summary)) },
                    headlineContent = { Text(file.description) },
                )
            }
            if (file.aliases.isNotEmpty()) {
                item(
                    overlineContent = { Text(stringResource(R.string.memory_file_page_aliases)) },
                    headlineContent = { Text(file.aliases.joinToString(", ")) },
                )
            }
            if (file.body.isNotBlank()) {
                formItem {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 和上面几项的 overline 保持一致
                        Text(
                            text = stringResource(R.string.memory_file_page_details),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectableContent {
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
 * @param titlePlaceholder 标题留空时界面上会显示的名字，从路径推出来
 * @param aliasInput 别名输入框里还没加进 [aliases] 的那个
 * @param contentValid 拼出来的整个文件是否没超过大小上限
 */
@Composable
private fun MemoryFileEditor(
    innerPadding: PaddingValues,
    path: String?,
    onPathChange: (String) -> Unit,
    pathValid: Boolean,
    title: String,
    onTitleChange: (String) -> Unit,
    titlePlaceholder: String,
    description: String,
    onDescriptionChange: (String) -> Unit,
    aliases: List<String>,
    onAliasesChange: (List<String>) -> Unit,
    aliasInput: String,
    onAliasInputChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    contentValid: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            // 导航栏那一段已经在 innerPadding 里了，不标成用掉的话 imePadding 会再留一次
            .consumeWindowInsets(innerPadding)
            // 放在滚动外面：键盘弹出时可滚动的区域跟着变矮，正在输入的那一行才会被滚到键盘上方
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 紧跟在顶栏的标题下面，左边和标题对齐
        Text(
            text = stringResource(R.string.memory_file_page_edit_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardGroup {
            if (path != null) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.memory_file_page_path)) },
                ) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = onPathChange,
                        placeholder = { Text("/topics/food.md") },
                        isError = path.isNotEmpty() && !pathValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_title)) },
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    placeholder = { Text(titlePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_summary)) },
            ) {
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_aliases)) },
            ) {
                val addAlias = {
                    onAliasesChange(aliases.withAlias(aliasInput))
                    onAliasInputChange("")
                }
                if (aliases.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        aliases.forEach { alias ->
                            InputChip(
                                selected = false,
                                onClick = { onAliasesChange(aliases - alias) },
                                label = { Text(text = alias, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.Cancel01,
                                        contentDescription = stringResource(R.string.delete),
                                        modifier = Modifier.size(InputChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = onAliasInputChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addAlias() }),
                    trailingIcon = {
                        IconButton(onClick = addAlias, enabled = aliasInput.isNotBlank()) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.add),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.memory_file_page_details)) },
            ) {
                OutlinedTextField(
                    value = body,
                    onValueChange = onBodyChange,
                    isError = !contentValid,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// 把输入框里的 [input] 加进别名；空的或者已经有了就不加
private fun List<String>.withAlias(input: String): List<String> {
    val alias = input.trim()
    return if (alias.isEmpty() || alias in this) this else this + alias
}
