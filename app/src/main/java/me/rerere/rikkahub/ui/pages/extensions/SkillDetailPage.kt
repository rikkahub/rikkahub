package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.rikkahub.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillDetailPage(skillName: String) {
    val vm = koinViewModel<SkillDetailVM>()
    LaunchedEffect(skillName) { vm.init(skillName) }

    val tree by vm.tree.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current

    var editingFile by remember { mutableStateOf<SkillFile?>(null) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillFile?>(null) }
    val deleteFailedMsg = stringResource(R.string.skill_detail_page_delete_failed)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(skillName) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Lucide.Plus, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding + PaddingValues(8.dp)),
        ) {
            FileTree(
                nodes = tree,
                depth = 0,
                onEdit = { editingFile = it },
                onDelete = { deleteTarget = it },
            )
        }
    }

    editingFile?.let { skillFile ->
        EditFileDialog(
            skillFile = skillFile,
            initialContent = remember(skillFile.relativePath) { vm.readFile(skillFile) },
            onDismiss = { editingFile = null },
            onConfirm = { content ->
                vm.saveFile(skillFile.relativePath, content) { error ->
                    if (error == null) editingFile = null
                    else toaster.show(error)
                }
            },
        )
    }

    if (showAddDialog) {
        AddFileDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { fileName, content ->
                vm.saveFile(fileName, content) { error ->
                    if (error == null) showAddDialog = false
                    else toaster.show(error)
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skill_detail_page_delete_file),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { skillFile ->
                vm.deleteFile(skillFile) { success ->
                    if (!success) toaster.show(deleteFailedMsg)
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skill_detail_page_delete_confirm, deleteTarget?.relativePath ?: ""))
    }
}

@Composable
private fun FileTree(
    nodes: List<SkillFileNode>,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
) {
    nodes.fastForEach { node ->
        when (node) {
            is SkillFileNode.FileNode -> FileItem(
                skillFile = node.skillFile,
                depth = depth,
                onEdit = { onEdit(node.skillFile) },
                onDelete = { onDelete(node.skillFile) },
            )

            is SkillFileNode.DirNode -> DirItem(
                node = node,
                depth = depth,
                onEdit = onEdit,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun FileItem(
    skillFile: SkillFile,
    depth: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (16 + depth * 20).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Lucide.FileText,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = skillFile.file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Text(
                text = "${skillFile.file.length()} B",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Lucide.FilePen,
                    contentDescription = stringResource(R.string.edit),
                    modifier = Modifier.size(16.dp),
                )
            }
            if (skillFile.relativePath != "SKILL.md") {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Lucide.Trash2,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirItem(
    node: SkillFileNode.DirNode,
    depth: Int,
    onEdit: (SkillFile) -> Unit,
    onDelete: (SkillFile) -> Unit,
) {
    var expanded by rememberSaveable(node.relativePath) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = (16 + depth * 20).dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Lucide.FolderOpen else Lucide.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    FileTree(
                        nodes = node.children,
                        depth = depth + 1,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditFileDialog(
    skillFile: SkillFile,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (content: String) -> Unit,
) {
    var content by rememberSaveable(skillFile.relativePath) { mutableStateOf(initialContent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skillFile.relativePath, fontFamily = FontFamily.Monospace) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.skill_detail_page_content)) },
                minLines = 10,
                maxLines = 20,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(content) }) { Text(stringResource(R.string.skill_detail_page_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AddFileDialog(
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, content: String) -> Unit,
) {
    var fileName by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    val fileNameError = fileName.isNotBlank() && (fileName.contains('\\'))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_detail_page_new_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.skill_detail_page_file_name)) },
                    placeholder = { Text("examples/basic.md", fontFamily = FontFamily.Monospace) },
                    supportingText = {
                        if (fileNameError) Text(
                            stringResource(R.string.skill_detail_page_file_name_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    isError = fileNameError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.skill_detail_page_content)) },
                    minLines = 6,
                    maxLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(fileName.trim(), content) },
                enabled = fileName.isNotBlank() && !fileNameError,
            ) {
                Text(stringResource(R.string.skill_detail_page_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
