package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import coil3.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
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

import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Clipboard
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import me.rerere.rikkahub.data.datastore.Settings
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.sandbox.SandboxEngine
import me.rerere.rikkahub.sandbox.SandboxFileInfo
import me.rerere.rikkahub.sandbox.SandboxUsage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf


@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { innerPadding ->
        AssistantLocalToolContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

/**
 * 文件管理卡片 - 始终可见，无开关
 * 仅供用户管理沙箱文件，不控制工具是否暴露给AI模型
 */
@Composable
private fun FileManagerCard(
    assistantId: kotlin.uuid.Uuid
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题和图标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Lucide.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "文件管理",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            // 描述
            Text(
                text = "管理该助手的所有对话沙箱中的文件。此功能仅供用户管理文件，不影响AI模型对文件工具的访问权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 文件管理界面
            ConversationSandboxList(assistantId = assistantId)
        }
    }
}

@Composable
private fun AssistantLocalToolContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // JavaScript 引擎工具卡片
        LocalToolCard(
            title = stringResource(R.string.assistant_page_local_tools_javascript_engine_title),
            description = stringResource(R.string.assistant_page_local_tools_javascript_engine_desc),
            icon = Lucide.Terminal,
            isEnabled = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
            onToggle = { enabled ->
                val newLocalTools = if (enabled) {
                    assistant.localTools + LocalToolOption.JavascriptEngine
                } else {
                    assistant.localTools - LocalToolOption.JavascriptEngine
                }
                onUpdate(assistant.copy(localTools = newLocalTools))
            }
        )

        // 时间信息工具卡片
        LocalToolCard(
            title = "时间信息",
            description = "允许 AI 获取当前时间信息",
            icon = Lucide.Clock,
            isEnabled = assistant.localTools.contains(LocalToolOption.TimeInfo),
            onToggle = { enabled ->
                val newLocalTools = if (enabled) {
                    assistant.localTools + LocalToolOption.TimeInfo
                } else {
                    assistant.localTools - LocalToolOption.TimeInfo
                }
                onUpdate(assistant.copy(localTools = newLocalTools))
            }
        )

        // 剪贴板工具卡片
        LocalToolCard(
            title = "剪贴板",
            description = "允许 AI 读取和写入剪贴板内容",
            icon = Lucide.Clipboard,
            isEnabled = assistant.localTools.contains(LocalToolOption.Clipboard),
            onToggle = { enabled ->
                val newLocalTools = if (enabled) {
                    assistant.localTools + LocalToolOption.Clipboard
                } else {
                    assistant.localTools - LocalToolOption.Clipboard
                }
                onUpdate(assistant.copy(localTools = newLocalTools))
            }
        )

        // ✅ 文件管理卡片 - 始终可见，无开关，仅供用户管理沙箱文件
        FileManagerCard(
            assistantId = assistant.id
        )

        // ✅ 文件管理工具开关（控制是否暴露给AI模型）
        LocalToolCard(
            title = "文件管理工具",
            description = "允许 AI 访问沙箱文件系统（仅暴露给模型，非文件管理器UI）",
            icon = Lucide.Folder,
            isEnabled = assistant.localTools.contains(LocalToolOption.SandboxFile),
            onToggle = { enabled ->
                val newLocalTools = if (enabled) {
                    assistant.localTools + LocalToolOption.SandboxFile
                } else {
                    assistant.localTools - LocalToolOption.SandboxFile
                }
                onUpdate(assistant.copy(localTools = newLocalTools))
            }
        )

        // ✅ ChaquoPy工具卡片
        val isChaquoPyEnabled = assistant.localTools.contains(LocalToolOption.ChaquoPy)
        LocalToolCard(
            title = "ChaquoPy工具",
            description = "沙箱Python执行环境，支持数据分析、图表绘制等高级功能",
            icon = Lucide.Terminal,
            isEnabled = isChaquoPyEnabled,
            onToggle = { enabled ->
                val newLocalTools = if (enabled) {
                    assistant.localTools + LocalToolOption.ChaquoPy
                } else {
                    assistant.localTools - LocalToolOption.ChaquoPy
                }
                onUpdate(assistant.copy(localTools = newLocalTools))
            }
        )

        // ✅ 容器工具卡片
        val isContainerEnabled = assistant.localTools.contains(LocalToolOption.Container)
        LocalToolCard(
            title = "容器工具 (PRoot)",
            description = "Linux 容器环境，支持 pip install 任意 Python 包",
            icon = Lucide.Terminal,
            isEnabled = isContainerEnabled,
            onToggle = { enabled ->
                val newLocalTools = if (enabled) {
                    assistant.localTools + LocalToolOption.Container
                } else {
                    assistant.localTools - LocalToolOption.Container
                }
                onUpdate(assistant.copy(localTools = newLocalTools))
            },
            content = if (isContainerEnabled) {
                {
                    ContainerStatusCard()
                }
            } else null
        )

        // ✅ Workflow TODO 工具卡片
        val isWorkflowTodoEnabled = assistant.localTools.contains(LocalToolOption.WorkflowTodo)
        LocalToolCard(
            title = "Workflow TODO",
            description = "任务列表管理工具，支持创建、更新、读取待办事项",
            icon = Lucide.ListTodo,
            isEnabled = isWorkflowTodoEnabled,
            onToggle = { enabled ->
                val newLocalTools = if (enabled) {
                    assistant.localTools + LocalToolOption.WorkflowTodo
                } else {
                    assistant.localTools - LocalToolOption.WorkflowTodo
                }
                onUpdate(assistant.copy(localTools = newLocalTools))
            }
        )

        // ✅ SubAgent 工具卡片
        val isSubAgentEnabled = assistant.localTools.contains(LocalToolOption.SubAgent)
        LocalToolCard(
            title = "子代理工具",
            description = "生成子代理并行处理特定任务（需先在子代理页面配置）",
            icon = Lucide.Bot,
            isEnabled = isSubAgentEnabled,
            onToggle = { enabled ->
                val newLocalTools = if (enabled) {
                    assistant.localTools + LocalToolOption.SubAgent
                } else {
                    assistant.localTools - LocalToolOption.SubAgent
                }
                onUpdate(assistant.copy(localTools = newLocalTools))
            },
            content = if (isSubAgentEnabled) {
                {
                    Text(
                        text = "子代理工具已启用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else null
        )
    }
}

/**
 * 对话沙箱列表 - 显示所有使用此助手的对话及其沙箱
 */
@Composable
private fun ConversationSandboxList(assistantId: kotlin.uuid.Uuid) {
    val conversationRepo: ConversationRepository = koinInject()
    val conversations by conversationRepo.getConversationsOfAssistant(assistantId)
        .collectAsStateWithLifecycle(initialValue = emptyList<Conversation>())
    val context = LocalContext.current
    
    // 只显示有沙箱文件的对话
    val conversationsWithSandbox = remember(conversations) {
        conversations.filter { conv: Conversation ->
            val sandboxDir = java.io.File(context.filesDir, "sandboxes/${conv.id}")
            sandboxDir.exists() && sandboxDir.listFiles()?.isNotEmpty() == true
        }
    }
    
    // 统计所有沙箱
    val totalSandboxes = conversationsWithSandbox.size
    
    var selectedConversation by remember { mutableStateOf<Conversation?>(null) }
    
    if (selectedConversation != null) {
        // 显示特定对话的沙箱管理界面
        SandboxManagerForConversation(
            conversation = selectedConversation!!,
            onBack = { selectedConversation = null }
        )
    } else {
        // 显示对话列表
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "选择对话查看沙箱",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (conversationsWithSandbox.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Lucide.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            "暂无对话沙箱数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "在对话中上传文件后会自动创建沙箱",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                Text(
                    text = "共 $totalSandboxes 个对话沙箱",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                conversationsWithSandbox.take(10).forEach { conversation: Conversation ->
                    ConversationSandboxItem(
                        conversation = conversation,
                        onClick = { selectedConversation = conversation }
                    )
                }
                
                if (conversationsWithSandbox.size > 10) {
                    Text(
                        text = "还有 ${conversationsWithSandbox.size - 10} 个对话...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 对话沙箱列表项
 */
@Composable
private fun ConversationSandboxItem(
    conversation: Conversation,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    
    // 获取该对话沙箱的使用情况
    var usage by remember { mutableStateOf<SandboxUsage?>(null) }
    
    LaunchedEffect(conversation.id) {
        usage = SandboxEngine.getSandboxUsage(context, conversation.id.toString())
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Lucide.Folder,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = conversation.title.ifEmpty { "未命名对话" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("${conversation.updateAt.toEpochMilli()}")
                        append(" • ")
                        append(usage?.fileCount ?: 0)
                        append(" 个文件")
                        usage?.let { 
                            append(" • ")
                            append(formatFileSize(it.usedBytes))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * 特定对话的沙箱管理器
 */
@Composable
private fun SandboxManagerForConversation(
    conversation: Conversation,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf<SandboxUsage?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showFileManager by remember { mutableStateOf(false) }
    
    // 加载沙箱使用情况
    LaunchedEffect(conversation.id) {
        isLoading = true
        usage = SandboxEngine.getSandboxUsage(context, conversation.id.toString())
        isLoading = false
    }
    
    // 文件管理器对话框
    if (showFileManager) {
        SandboxFileManagerDialog(
            sandboxId = conversation.id.toString(),
            title = conversation.title.ifEmpty { "未命名对话" },
            onDismiss = { showFileManager = false }
        )
    }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 返回按钮和标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Lucide.ChevronLeft, contentDescription = "返回", modifier = Modifier.size(20.dp))
            }
            Text(
                text = conversation.title.ifEmpty { "未命名对话" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        
        if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "正在加载沙箱信息...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            usage?.let { sandboxUsage ->
                // 存储使用情况
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "存储使用",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "${formatFileSize(sandboxUsage.usedBytes)} / ${formatFileSize(sandboxUsage.maxBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    LinearProgressIndicator(
                        progress = { sandboxUsage.usagePercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = when {
                            sandboxUsage.usagePercent > 90 -> MaterialTheme.colorScheme.error
                            sandboxUsage.usagePercent > 70 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${sandboxUsage.fileCount} 个文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${sandboxUsage.usagePercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // 操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showFileManager = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Lucide.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("浏览文件")
                        }
                        
                        if (sandboxUsage.fileCount > 0) {
                            TextButton(
                                onClick = {
                                    SandboxEngine.clearSandbox(context, conversation.id.toString())
                                    usage = SandboxEngine.getSandboxUsage(context, conversation.id.toString())
                                }
                            ) {
                                Text("清空", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 沙箱管理器组件（旧版，保留用于兼容）
 */
@Composable
private fun SandboxManager(assistantId: String) {
    // 已废弃，使用 ConversationSandboxList 替代
}

/**
 * 获取文件图标根据扩展名
 */
@Composable
private fun getFileIcon(fileName: String, isDirectory: Boolean): androidx.compose.ui.graphics.vector.ImageVector {
    if (isDirectory) return Lucide.Folder
    
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "zip", "tar", "gz", "tgz", "bz2", "7z", "rar", "xz" -> Lucide.FileArchive
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> Lucide.FileImage
        "json", "xml", "yaml", "yml" -> Lucide.FileJson
        "txt", "md", "csv", "log" -> Lucide.FileText
        "py", "java", "kt", "js", "ts", "c", "cpp", "h", "go", "rs", "rb", "php", "swift" -> Lucide.FileCode
        else -> Lucide.File
    }
}

/**
 * 沙箱文件管理器对话框 - 支持目录浏览
 *
 * 注意：此组件为 private，仅在 AssistantLocalToolPage 内部使用。
 * 如需在其他页面使用，请复制此组件或将其提取到公共组件库。
 */
@Composable
private fun SandboxFileManagerDialog(
    sandboxId: String,
    title: String = "沙箱文件管理",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
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
                    onRefresh = { loadDirectory(currentPath) }
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
                        }
                    }
                } else {
                    // 显示统计信息
                    val folderCount = currentItems.count { it.isDirectory }
                    val fileCount = currentItems.size - folderCount
                    Text(
                        text = buildString {
                            append("${currentItems.size} 个项目")
                            if (folderCount > 0) append(" • $folderCount 个文件夹")
                            if (fileCount > 0) append(" • $fileCount 个文件")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // 表头
                    FileListHeader()
                    
                    // 预览状态
                    var previewImage by remember { mutableStateOf<FileSystemItem?>(null) }
                    var previewDb by remember { mutableStateOf<FileSystemItem?>(null) }
                    
                    // 图片预览对话框
                    previewImage?.let { imageItem ->
                        ImagePreviewDialog(
                            sandboxId = sandboxId,
                            imagePath = imageItem.path,
                            imageName = imageItem.name,
                            onDismiss = { previewImage = null }
                        )
                    }
                    
                    // SQLite 浏览器对话框
                    previewDb?.let { dbItem ->
                        SQLiteBrowserDialog(
                            sandboxId = sandboxId,
                            dbPath = dbItem.path,
                            dbName = dbItem.name,
                            onDismiss = { previewDb = null }
                        )
                    }
                    
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        // 先显示文件夹
                        items(currentItems.filter { it.isDirectory }.sortedBy { it.name }) { item ->
                            FileManagerItem(
                                item = item,
                                onClick = {
                                    if (item.isDirectory) {
                                        val newPath = if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}"
                                        currentPath = newPath
                                        pathHistory = pathHistory + newPath
                                    }
                                },
                                onShare = {
                                    if (!item.isDirectory) {
                                        val uri = SandboxEngine.getFileShareUri(context, sandboxId, item.path)
                                        uri?.let { shareUri ->
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = SandboxEngine.getFileMimeType(item.name)
                                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(intent, "分享 ${item.name}")
                                            )
                                        }
                                    }
                                },
                                onDelete = {
                                    SandboxEngine.deleteSandboxFile(context, sandboxId, item.path)
                                    loadDirectory(currentPath)
                                }
                            )
                        }
                        // 再显示文件
                        items(currentItems.filter { !it.isDirectory }.sortedBy { it.name }) { item ->
                            FileManagerItem(
                                item = item,
                                onClick = {
                                    when {
                                        item.name.isImageFile() -> previewImage = item
                                        item.name.isSQLiteFile() -> previewDb = item
                                    }
                                },
                                onShare = {
                                    val uri = SandboxEngine.getFileShareUri(context, sandboxId, item.path)
                                    uri?.let { shareUri ->
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = SandboxEngine.getFileMimeType(item.name)
                                            putExtra(Intent.EXTRA_STREAM, shareUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(intent, "分享 ${item.name}")
                                        )
                                    }
                                },
                                onDelete = {
                                    SandboxEngine.deleteSandboxFile(context, sandboxId, item.path)
                                    loadDirectory(currentPath)
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
}

/**
 * 文件系统项数据类
 */
private data class FileSystemItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: Long = 0
)

/**
 * 根据文件列表构建当前目录的内容
 */
private fun buildFileTree(allFiles: List<SandboxFileInfo>, currentPath: String): List<FileSystemItem> {
    val items = mutableSetOf<FileSystemItem>()
    val prefix = if (currentPath.isEmpty()) "" else "$currentPath/"
    
    allFiles.forEach { file ->
        val relativePath = file.path
        // 只处理在当前目录下的文件
        if (currentPath.isEmpty()) {
            // 根目录：显示直接子项
            if (!relativePath.contains("/")) {
                items.add(FileSystemItem(
                    name = file.name,
                    path = file.path,
                    isDirectory = false,
                    size = file.size,
                    modified = file.modified
                ))
            } else {
                // 添加一级文件夹
                val firstDir = relativePath.substringBefore("/")
                items.add(FileSystemItem(
                    name = firstDir,
                    path = firstDir,
                    isDirectory = true
                ))
            }
        } else {
            // 子目录
            if (relativePath.startsWith(prefix)) {
                val remaining = relativePath.removePrefix(prefix)
                if (!remaining.contains("/")) {
                    // 直接子文件
                    items.add(FileSystemItem(
                        name = file.name,
                        path = file.path,
                        isDirectory = false,
                        size = file.size,
                        modified = file.modified
                    ))
                } else {
                    // 子文件夹
                    val subDir = remaining.substringBefore("/")
                    items.add(FileSystemItem(
                        name = subDir,
                        path = "$currentPath/$subDir",
                        isDirectory = true
                    ))
                }
            }
        }
    }
    
    return items.toList()
}

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
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 根目录
        TextButton(
            onClick = { onPathClick(0) },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        ) {
            Icon(
                Lucide.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("根目录", style = MaterialTheme.typography.labelSmall)
        }
        
        // 路径层级
        pathHistory.drop(1).forEachIndexed { index, path ->
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            val folderName = path.substringAfterLast("/")
            TextButton(
                onClick = { onPathClick(index + 1) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(
                    folderName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 100.dp)
                )
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
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 返回上级按钮
            IconButton(
                onClick = onBackClick,
                enabled = canGoBack,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Lucide.ChevronLeft,
                    contentDescription = "返回上级",
                    modifier = Modifier.size(20.dp)
                )
            }
            
            // 当前路径显示
            Text(
                text = if (currentPath.isEmpty()) "根目录" else currentPath.substringAfterLast("/"),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp)
            )
        }
        
        // 刷新按钮
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Lucide.Settings, // 使用其他图标代替刷新
                contentDescription = "刷新",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 文件列表表头
 */
@Composable
private fun FileListHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "名称",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            "大小",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(70.dp)
        )
        Text(
            "操作",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
    }
    HorizontalDivider(thickness = 0.5.dp)
}

/**
 * 文件管理器项目
 */
@Composable
private fun FileManagerItem(
    item: FileSystemItem,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标和名称
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = getFileIcon(item.name, item.isDirectory),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (item.isDirectory)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.isDirectory) {
                    Icon(
                        Lucide.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            
            // 大小
            Text(
                text = if (item.isDirectory) "-" else formatFileSize(item.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(70.dp)
            )
            
            // 操作按钮
            Row(
                modifier = Modifier.width(80.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (!item.isDirectory) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Lucide.Share2,
                            contentDescription = "分享",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "${size}B"
        size < 1024 * 1024 -> String.format("%.1fK", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.1fM", size / (1024.0 * 1024.0))
        else -> String.format("%.1fG", size / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
private fun LocalToolCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    ) {
        FormItem(
            modifier = Modifier.padding(8.dp),
            label = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(title)
                }
            },
            description = {
                Text(description)
            },
            tail = {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle
                )
            },
            content = {
                if (isEnabled && content != null) {
                    content()
                } else {
                    null
                }
            }
        )
    }
}

/**
 * 图片预览对话框
 */
@Composable
private fun ImagePreviewDialog(
    sandboxId: String,
    imagePath: String,
    imageName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uri = remember(imagePath) {
        SandboxEngine.getFileShareUri(context, sandboxId, imagePath)
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(imageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onDismiss) {
                    Icon(Lucide.X, contentDescription = "关闭")
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                contentAlignment = Alignment.Center
            ) {
                uri?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = imageName,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                } ?: Text("无法加载图片")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * SQLite 数据库浏览器对话框
 */
@Composable
private fun SQLiteBrowserDialog(
    sandboxId: String,
    dbPath: String,
    dbName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var tables by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var queryResult by remember { mutableStateOf<Map<String, Any>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(0) } // 0=表结构, 1=查询
    
    // 加载表结构
    fun loadTables() {
        scope.launch {
            isLoading = true
            val result = SandboxEngine.execute(
                context, sandboxId, "sqlite_tables",
                mapOf("db_path" to dbPath, "detail" to true)
            )
            @Suppress("UNCHECKED_CAST")
            val tablesList = result["tables"] as? List<Map<String, Any>> ?: emptyList()
            tables = tablesList
            isLoading = false
        }
    }
    
    // 执行查询
    fun executeQuery() {
        if (query.isBlank()) return
        scope.launch {
            isLoading = true
            val result = SandboxEngine.execute(
                context, sandboxId, "sqlite_query",
                mapOf("db_path" to dbPath, "query" to query, "max_rows" to 100)
            )
            queryResult = result
            isLoading = false
        }
    }
    
    LaunchedEffect(sandboxId, dbPath) {
        loadTables()
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(dbName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "SQLite 浏览器",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Lucide.X, contentDescription = "关闭")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                // Tab 切换
                SecondaryTabRow(selectedTabIndex = currentTab) {
                    Tab(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        text = { Text("表结构") }
                    )
                    Tab(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        text = { Text("SQL 查询") }
                    )
                }
                
                when (currentTab) {
                    0 -> {
                        // 表结构视图
                        if (isLoading && tables.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (tables.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("数据库为空或无法读取")
                            }
                        } else {
                            LazyColumn {
                                 items(tables) { table ->
                                    @Suppress("UNCHECKED_CAST")
                                    val tableName = table["name"] as? String ?: ""
                                    val rowCount = table["row_count"] as? Int ?: 0
                                    @Suppress("UNCHECKED_CAST")
                                    val columns = table["columns"] as? List<Map<String, Any>> ?: emptyList()
                                    
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                selectedTable = tableName
                                                query = "SELECT * FROM $tableName LIMIT 50"
                                                currentTab = 1
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    tableName,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    "$rowCount 行",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                columns.joinToString { it["name"] as? String ?: "" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        // SQL 查询视图
                        Column {
                            // 查询输入
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("SQL 查询") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4
                            )
                            
                            Button(
                                onClick = { executeQuery() },
                                modifier = Modifier.padding(vertical = 8.dp),
                                enabled = !isLoading && query.isNotBlank()
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("执行")
                                }
                            }
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            // 查询结果
                            queryResult?.let { result ->
                                val success = result["success"] as? Boolean ?: false
                                if (!success) {
                                    // 改进错误显示，提供更详细的信息
                                    val errorMsg = result["error"] as? String
                                        ?: result["raw_result"]?.toString()
                                        ?: "未知错误: ${result.toString()}"
                                    Text(
                                        errorMsg,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    val columns = result["columns"] as? List<String> ?: emptyList()
                                    @Suppress("UNCHECKED_CAST")
                                    val data = result["data"] as? List<Map<String, Any>> ?: emptyList()
                                    val rowCount = result["row_count"] as? Int ?: 0
                                    
                                    Text(
                                        "返回 $rowCount 行",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                    
                                    if (data.isNotEmpty()) {
                                        // 简单的表格展示
                                        LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                                            // 表头
                                            item {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                                        .padding(4.dp)
                                                ) {
                                                    columns.forEach { col ->
                                                        Text(
                                                            col,
                                                            modifier = Modifier.weight(1f),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                            // 数据行
                                            items(data.take(20)) { row ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(4.dp)
                                                ) {
                                                    columns.forEach { col ->
                                                        val value = row[col]?.toString() ?: "null"
                                                        Text(
                                                            value,
                                                            modifier = Modifier.weight(1f),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                                HorizontalDivider()
                                            }
                                        }
                                    }
                                }
                            }
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
}

// 扩展函数：判断文件是否为图片
private fun String.isImageFile(): Boolean {
    val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
    return imageExtensions.contains(this.substringAfterLast(".", "").lowercase())
}

// 扩展函数：判断文件是否为 SQLite 数据库
private fun String.isSQLiteFile(): Boolean {
    val dbExtensions = setOf("db", "sqlite", "sqlite3", "db3")
    return dbExtensions.contains(this.substringAfterLast(".", "").lowercase())
}

/**
 * 容器状态卡片
 */
@Composable
private fun ContainerStatusCard() {
    val prootManager: me.rerere.rikkahub.data.container.PRootManager = koinInject()
    val containerState by prootManager.containerState.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "容器状态",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (val state = containerState) {
                is me.rerere.rikkahub.data.container.ContainerStateEnum.Running -> {
                    Text("运行中", color = MaterialTheme.colorScheme.primary)
                }
                is me.rerere.rikkahub.data.container.ContainerStateEnum.Stopped -> {
                    Text("已停止", color = MaterialTheme.colorScheme.error)
                }
                else -> {
                    Text("未知状态", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
