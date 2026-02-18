package me.rerere.rikkahub.ui.components.sandbox

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileArchive
import com.composables.icons.lucide.FileCode
import com.composables.icons.lucide.FileImage
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.FileType
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.sandbox.SandboxEngine
import me.rerere.rikkahub.sandbox.SandboxFileInfo

/**
 * 沙箱文件管理器对话框 - 支持目录浏览、新增、编辑、重命名文件
 *
 * 这是一个公共组件，可在任何页面使用。
 *
 * @param sandboxId 沙箱ID（通常是 conversation ID）
 * @param title 对话框标题
 * @param onDismiss 关闭回调
 */
@Composable
fun SandboxFileManagerDialog(
    sandboxId: String,
    title: String = "沙箱文件管理",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 对话框状态
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showEditMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<FileSystemItem?>(null) }

    // 当前浏览路径
    var currentPath by remember { mutableStateOf("") }
    // 路径历史用于面包屑
    var pathHistory by remember { mutableStateOf(listOf("")) }

    // 当前目录内容
    var currentItems by remember { mutableStateOf<List<FileSystemItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // 加载当前目录内容
    fun loadDirectory(path: String) {
        scope.launch {
            isLoading = true
            val allFiles = SandboxEngine.listAllFiles(context, sandboxId)
            currentItems = buildFileTree(allFiles, path)
            isLoading = false
        }
    }

    // 初始加载
    LaunchedEffect(sandboxId) {
        loadDirectory(currentPath)
    }

    // 路径变化时重新加载
    LaunchedEffect(currentPath) {
        loadDirectory(currentPath)
    }

    // 显示编辑菜单
    fun showEditOptionsMenu(file: FileSystemItem) {
        selectedFile = file
        showEditMenu = true
    }

    // 删除文件
    fun deleteFile(item: FileSystemItem) {
        scope.launch {
            try {
                val result = SandboxEngine.execute(
                    context = context,
                    assistantId = sandboxId,
                    operation = "delete",
                    params = mapOf(
                        "file_path" to item.path
                    )
                )
                if (result["success"]?.jsonPrimitive?.boolean == true) {
                    loadDirectory(currentPath)
                } else {
                    // TODO: 显示错误提示
                }
            } catch (e: Exception) {
                // TODO: 显示错误提示
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "沙箱文件管理",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Lucide.X, contentDescription = "关闭")
                    }
                }
                // 面包屑导航
                BreadcrumbNavigation(
                    pathHistory = pathHistory,
                    onPathClick = { index ->
                        pathHistory = pathHistory.take(index + 1)
                        currentPath = pathHistory.last()
                    }
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
            ) {
                // 工具栏
                FileManagerToolbar(
                    currentPath = currentPath,
                    onBackClick = {
                        if (pathHistory.size > 1) {
                            pathHistory = pathHistory.dropLast(1)
                            currentPath = pathHistory.last()
                        }
                    },
                    canGoBack = pathHistory.size > 1,
                    onRefresh = { loadDirectory(currentPath) },
                    onCreateFile = { showCreateDialog = true }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 文件列表
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (currentItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Lucide.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                if (currentPath.isEmpty()) "沙箱为空" else "文件夹为空",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "点击右上角 + 新建文件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // 显示统计信息
                    val folderCount = currentItems.count { it.isDirectory }
                    val fileCount = currentItems.size - folderCount
                    Text(
                        text = buildString {
                            append("共 ")
                            if (folderCount > 0) {
                                append("${folderCount} 个文件夹")
                                if (fileCount > 0) append("，")
                            }
                            if (fileCount > 0) {
                                append("${fileCount} 个文件")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(currentItems, key = { it.name }) { item ->
                            FileSystemItemRow(
                                item = item,
                                onClick = {
                                    if (item.isDirectory) {
                                        pathHistory = pathHistory + item.name
                                        currentPath = if (currentPath.isEmpty()) {
                                            item.name
                                        } else {
                                            "$currentPath/${item.name}"
                                        }
                                    } else {
                                        // 打开文件
                                        val file = java.io.File(context.filesDir, "sandboxes/$sandboxId/${item.path}")
                                        if (file.exists()) {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, getMimeType(item.name))
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "打开文件"))
                                        }
                                    }
                                },
                                onShare = {
                                    if (!item.isDirectory) {
                                        val file = java.io.File(context.filesDir, "sandboxes/$sandboxId/${item.path}")
                                        if (file.exists()) {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = getMimeType(item.name)
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "分享文件"))
                                        }
                                    }
                                },
                                onEdit = {
                                    if (!item.isDirectory) {
                                        showEditOptionsMenu(item)
                                    }
                                },
                                onDelete = {
                                    if (!item.isDirectory) {
                                        selectedFile = item
                                        showDeleteDialog = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

    // 新增文件对话框
    if (showCreateDialog) {
        CreateFileDialog(
            sandboxId = sandboxId,
            currentPath = currentPath,
            onDismiss = { showCreateDialog = false },
            onSuccess = { loadDirectory(currentPath) }
        )
    }

    // 改名文件对话框
    if (showRenameDialog && selectedFile != null) {
        RenameFileDialog(
            sandboxId = sandboxId,
            currentPath = currentPath,
            oldName = selectedFile!!.name,
            onDismiss = {
                showRenameDialog = false
                selectedFile = null
            },
            onSuccess = {
                loadDirectory(currentPath)
                selectedFile = null
            }
        )
    }

    // 编辑文件内容对话框
    if (showEditDialog && selectedFile != null) {
        EditFileDialog(
            sandboxId = sandboxId,
            filePath = selectedFile!!.path,
            fileName = selectedFile!!.name,
            onDismiss = {
                showEditDialog = false
                selectedFile = null
            },
            onSuccess = {
                loadDirectory(currentPath)
                selectedFile = null
            }
        )
    }

    // 编辑选项菜单
    if (showEditMenu && selectedFile != null) {
        EditOptionsMenuDialog(
            fileName = selectedFile!!.name,
            onRename = {
                showEditMenu = false
                showRenameDialog = true
            },
            onEditContent = {
                showEditMenu = false
                showEditDialog = true
            },
            onDismiss = {
                showEditMenu = false
                selectedFile = null
            }
        )
    }

    // 删除确认对话框
    if (showDeleteDialog && selectedFile != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                selectedFile = null
            },
            title = {
                Text("删除文件")
            },
            text = {
                Text("确定要删除文件 \"${selectedFile!!.name}\" 吗？此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteFile(selectedFile!!)
                        showDeleteDialog = false
                        selectedFile = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        selectedFile = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 编辑选项菜单对话框
 */
@Composable
private fun EditOptionsMenuDialog(
    fileName: String,
    onRename: () -> Unit,
    onEditContent: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "编辑: $fileName",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                // 改名选项
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = {
                            onRename()
                        })
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Lucide.File, contentDescription = null)
                        Text("重命名文件")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 编辑内容选项
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = {
                            onEditContent()
                        })
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Lucide.FileText, contentDescription = null)
                        Text("编辑内容")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 构建文件树，返回当前路径下的直接子项
 */
private fun buildFileTree(
    files: List<SandboxFileInfo>,
    currentPath: String
): List<FileSystemItem> {
    val items = mutableSetOf<FileSystemItem>()

    files.forEach { file ->
        val relativePath = file.path.removePrefix("/")

        // 检查文件是否在当前路径下
        if (currentPath.isEmpty()) {
            // 根目录：找第一层
            val firstSlash = relativePath.indexOf('/')
            if (firstSlash == -1) {
                // 根目录下的文件
                items.add(FileSystemItem(
                    name = relativePath,
                    path = relativePath,
                    isDirectory = false,
                        size = file.size,
                        modifiedTime = file.modified
                ))
            } else {
                // 根目录下的文件夹
                val folderName = relativePath.substring(0, firstSlash)
                items.add(FileSystemItem(
                    name = folderName,
                    path = folderName,
                    isDirectory = true,
                    size = 0,
                    modifiedTime = 0
                ))
            }
        } else {
            // 子目录
            val prefix = "$currentPath/"
            if (relativePath.startsWith(prefix)) {
                val remaining = relativePath.removePrefix(prefix)
                val firstSlash = remaining.indexOf('/')

                if (firstSlash == -1) {
                    // 当前目录下的文件
                    items.add(FileSystemItem(
                        name = remaining,
                        path = relativePath,
                        isDirectory = false,
                        size = file.size,
                        modifiedTime = file.modified
                    ))
                } else {
                    // 当前目录下的子文件夹
                    val folderName = remaining.substring(0, firstSlash)
                    items.add(FileSystemItem(
                        name = folderName,
                        path = "$currentPath/$folderName",
                        isDirectory = true,
                        size = 0,
                        modifiedTime = 0
                    ))
                }
            }
        }
    }

    return items.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
}

/**
 * 文件系统项数据类
 */
private data class FileSystemItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTime: Long
)

/**
 * 面包屑导航
 */
@Composable
private fun BreadcrumbNavigation(
    pathHistory: List<String>,
    onPathClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "位置: ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (pathHistory.size == 1 && pathHistory[0].isEmpty()) {
            Text(
                text = "根目录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "根目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onPathClick(0) }
                )

                pathHistory.drop(1).forEachIndexed { index, path ->
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = path,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == pathHistory.size - 2) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.clickable { onPathClick(index + 1) }
                    )
                }
            }
        }
    }
}

/**
 * 工具栏
 */
@Composable
private fun FileManagerToolbar(
    currentPath: String,
    onBackClick: () -> Unit,
    canGoBack: Boolean,
    onRefresh: () -> Unit,
    onCreateFile: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                enabled = canGoBack
            ) {
                Icon(
                    Lucide.ChevronLeft,
                    contentDescription = "返回上级",
                    tint = if (canGoBack) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    }
                )
            }

            Text(
                text = if (currentPath.isEmpty()) "根目录" else currentPath.substringAfterLast('/'),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCreateFile) {
                Icon(Lucide.Plus, contentDescription = "新增文件")
            }
            IconButton(onClick = onRefresh) {
                Icon(Lucide.RefreshCw, contentDescription = "刷新")
            }
        }
    }
}

/**
 * 文件系统项行
 */
@Composable
private fun FileSystemItemRow(
    item: FileSystemItem,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 图标
            Icon(
                imageVector = if (item.isDirectory) {
                    Lucide.Folder
                } else {
                    getFileIcon(item.name)
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (item.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            // 文件名
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.isDirectory) {
                    Text(
                        text = formatFileSize(item.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 操作按钮（仅文件）
            if (!item.isDirectory) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Lucide.Wrench,
                        contentDescription = "编辑",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Lucide.Share2,
                        contentDescription = "分享",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 获取文件图标
 */
private fun getFileIcon(fileName: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when {
        fileName.endsWith(".jpg", true) ||
        fileName.endsWith(".jpeg", true) ||
        fileName.endsWith(".png", true) ||
        fileName.endsWith(".gif", true) ||
        fileName.endsWith(".webp", true) -> Lucide.FileImage

        fileName.endsWith(".json", true) -> Lucide.FileJson

        fileName.endsWith(".txt", true) ||
        fileName.endsWith(".md", true) ||
        fileName.endsWith(".log", true) -> Lucide.FileText

        fileName.endsWith(".zip", true) ||
        fileName.endsWith(".tar", true) ||
        fileName.endsWith(".gz", true) ||
        fileName.endsWith(".rar", true) ||
        fileName.endsWith(".7z", true) -> Lucide.FileArchive

        fileName.endsWith(".kt", true) ||
        fileName.endsWith(".java", true) ||
        fileName.endsWith(".py", true) ||
        fileName.endsWith(".js", true) ||
        fileName.endsWith(".ts", true) ||
        fileName.endsWith(".html", true) ||
        fileName.endsWith(".css", true) ||
        fileName.endsWith(".xml", true) -> Lucide.FileCode

        else -> Lucide.FileType
    }
}

/**
 * 获取 MIME 类型
 */
private fun getMimeType(fileName: String): String {
    return when {
        fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
        fileName.endsWith(".png", true) -> "image/png"
        fileName.endsWith(".gif", true) -> "image/gif"
        fileName.endsWith(".webp", true) -> "image/webp"
        fileName.endsWith(".txt", true) || fileName.endsWith(".md", true) -> "text/plain"
        fileName.endsWith(".json", true) -> "application/json"
        fileName.endsWith(".html", true) -> "text/html"
        fileName.endsWith(".pdf", true) -> "application/pdf"
        fileName.endsWith(".zip", true) -> "application/zip"
        else -> "*/*"
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}
