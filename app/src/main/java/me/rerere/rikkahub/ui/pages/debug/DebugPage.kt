package me.rerere.rikkahub.ui.pages.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.MathBlock
import me.rerere.rikkahub.ui.components.richtext.Mermaid
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.sandbox.PRootSandbox
import me.rerere.sandbox.RootFsInstaller
import me.rerere.sandbox.SandboxBind
import me.rerere.sandbox.SandboxConfig
import org.koin.androidx.compose.koinViewModel
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.uuid.Uuid

@Composable
fun DebugPage(vm: DebugVM = koinViewModel()) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Debug Mode")
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { contentPadding ->
        val state = rememberPagerState { 2 }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            SecondaryTabRow(
                selectedTabIndex = state.currentPage,
            ) {
                Tab(
                    selected = state.currentPage == 0,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(0)
                        }
                    },
                    text = {
                        Text("Main")
                    }
                )
                Tab(
                    selected = state.currentPage == 1,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(1)
                        }
                    },
                    text = {
                        Text("Logging")
                    }
                )
            }
            HorizontalPager(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> MainPage(vm)
                    1 -> Box {}
                }
            }
        }
    }
}

@Composable
private fun MainPage(vm: DebugVM) {
    val settings = LocalSettings.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var avatar: Avatar by remember { mutableStateOf(Avatar.Emoji("😎")) }
        UIAvatar(
            value = avatar,
            onUpdate = {
                println("Avatar updated: $it")
                avatar = it
            },
            name = "A"
        )
        Mermaid(
            code = """
                mindmap
                  root((mindmap))
                    Origins
                      Long history
                      ::icon(fa fa-book)
                      Popularisation
                        British popular psychology author Tony Buzan
                    Research
                      On effectiveness<br/>and features
                      On Automatic creation
                        Uses
                            Creative techniques
                            Strategic planning
                            Argument mapping
                    Tools
                      Pen and paper
                      Mermaid
                """.trimIndent(),
            modifier = Modifier.fillMaxWidth(),
        )

        var counter by remember {
            mutableIntStateOf(0)
        }
        Button(
            onClick = {
                toaster.show("测试 ${counter++}")
                toaster.show("测试 ${counter++}", type = ToastType.Info)
                toaster.show("测试 ${counter++}", type = ToastType.Error)
            }
        ) {
            Text("toast")
        }
        Button(
            onClick = {
                vm.updateSettings(
                    settings.copy(
                        chatModelId = Uuid.random()
                    )
                )
            }
        ) {
            Text("重置Chat模型")
        }

        Button(
            onClick = {
                error("测试崩溃 ${Random.nextInt(0..1000)}")
            }
        ) {
            Text("崩溃")
        }

        Button(
            onClick = {
                vm.createOversizedConversation(30)
                toaster.show("正在创建 30MB 超大对话...")
            }
        ) {
            Text("创建超大对话 (30MB)")
        }

        Button(
            onClick = {
                vm.createConversationWithMessages(1024)
                toaster.show("正在创建 1024 条消息对话...")
            }
        ) {
            Text("创建 1024 个消息的聊天")
        }

        HorizontalDivider()

        Text("Sandbox", style = MaterialTheme.typography.labelMedium)
        Text(
            "这是一个基于 PRoot 的非 PTY 测试终端，适合先验证 rootfs、bind mount 和命令执行。",
            style = MaterialTheme.typography.bodySmall,
        )

        val defaultRootfs = remember(context) { File(context.filesDir, "rootfs").absolutePath }
        val defaultRootfsArchive = remember(context) { File(context.filesDir, "rootfs.tar.xz").absolutePath }
        val defaultWorkspace = remember(context) { File(context.filesDir, "sandbox-workspace").absolutePath }
        var rootfsPath by rememberSaveable { mutableStateOf(defaultRootfs) }
        var rootfsArchivePath by rememberSaveable { mutableStateOf(defaultRootfsArchive) }
        var workspacePath by rememberSaveable { mutableStateOf(defaultWorkspace) }
        var command by rememberSaveable {
            mutableStateOf("echo hello-from-proot && uname -a && id && pwd && ls / && ls -la /workspace")
        }
        var sandboxOutput by rememberSaveable { mutableStateOf("") }
        var sandboxRunning by remember { mutableStateOf(false) }
        var unzipRunning by remember { mutableStateOf(false) }

        val rootfsDir = remember(rootfsPath) { File(rootfsPath) }
        val rootfsArchiveFile = remember(rootfsArchivePath) { File(rootfsArchivePath) }
        val shellCandidate = remember(rootfsPath) { File(rootfsDir, "bin/sh") }
        val rootfsStatus = when {
            !rootfsDir.exists() -> "rootfs 不存在"
            !rootfsDir.isDirectory -> "rootfs 不是目录"
            shellCandidate.exists() -> "rootfs 已就绪，检测到 /bin/sh"
            else -> "rootfs 存在，但没有检测到 /bin/sh"
        }

        OutlinedTextField(
            value = rootfsPath,
            onValueChange = { rootfsPath = it },
            label = { Text("rootfs 路径") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = rootfsArchivePath,
            onValueChange = { rootfsArchivePath = it },
            label = { Text("rootfs.tar.xz 路径") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = workspacePath,
            onValueChange = { workspacePath = it },
            label = { Text("workspace 宿主路径") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            rootfsStatus,
            style = MaterialTheme.typography.bodySmall,
            color = if (shellCandidate.exists()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        Text(
            when {
                !rootfsArchiveFile.exists() -> "归档不存在"
                !rootfsArchiveFile.isFile -> "归档路径不是文件"
                else -> "归档已就绪，可解压到 rootfs"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (rootfsArchiveFile.isFile) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !unzipRunning,
                onClick = {
                    val trimmedArchivePath = rootfsArchivePath.trim()
                    val trimmedRootfsPath = rootfsPath.trim()
                    when {
                        trimmedArchivePath.isEmpty() -> {
                            toaster.show("请先填写 rootfs.tar.xz 路径", type = ToastType.Error)
                        }

                        trimmedRootfsPath.isEmpty() -> {
                            toaster.show("请先填写 rootfs 路径", type = ToastType.Error)
                        }

                        else -> scope.launch {
                            unzipRunning = true
                            sandboxOutput = "正在解压 rootfs.tar.xz...\n"
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val archiveFile = File(trimmedArchivePath)
                                    val targetDir = File(trimmedRootfsPath)
                                    val result = RootFsInstaller().installTarXz(
                                        archiveFile = archiveFile,
                                        targetDirectory = targetDir,
                                        cleanTargetDirectory = true,
                                    )
                                    buildString {
                                        appendLine("解压完成")
                                        appendLine("archive=${archiveFile.absolutePath}")
                                        appendLine("target=${targetDir.absolutePath}")
                                        appendLine("entries=${result.totalEntries}")
                                        appendLine("directories=${result.directoryCount}")
                                        appendLine("files=${result.fileCount}")
                                        appendLine("symlinks=${result.symbolicLinkCount}")
                                        appendLine("hardLinks=${result.hardLinkCount}")
                                        appendLine("shellExists=${File(targetDir, "bin/sh").exists()}")
                                        appendLine("envExists=${File(targetDir, "usr/bin/env").exists()}")
                                    }
                                }
                            }.onSuccess {
                                sandboxOutput = it
                                toaster.show("rootfs 解压完成")
                            }.onFailure {
                                sandboxOutput = buildString {
                                    appendLine("解压失败")
                                    appendLine(it.message ?: it.javaClass.name)
                                    appendLine()
                                    appendLine(it.stackTraceToString())
                                }
                                toaster.show("rootfs 解压失败", type = ToastType.Error)
                            }
                            unzipRunning = false
                        }
                    }
                }
            ) {
                Text(if (unzipRunning) "解压中..." else "解压 rootfs.tar.xz")
            }
        }
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("测试命令") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            textStyle = TextStyle(fontFamily = JetbrainsMono),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    command = "echo hello-from-proot && uname -a && id && pwd && ls / && ls -la /workspace"
                }
            ) {
                Text("填入 Smoke Test")
            }
            Button(
                onClick = {
                    command =
                        "echo sandbox-write-test > /workspace/hello.txt && ls -la /workspace && cat /workspace/hello.txt"
                }
            ) {
                Text("填入写文件测试")
            }
        }
        Button(
            enabled = !sandboxRunning,
            onClick = {
                val trimmedRootfsPath = rootfsPath.trim()
                val trimmedWorkspacePath = workspacePath.trim()
                val trimmedCommand = command.trim()
                when {
                    trimmedRootfsPath.isEmpty() -> {
                        toaster.show("请先填写 rootfs 路径", type = ToastType.Error)
                    }

                    trimmedWorkspacePath.isEmpty() -> {
                        toaster.show("请先填写 workspace 路径", type = ToastType.Error)
                    }

                    trimmedCommand.isEmpty() -> {
                        toaster.show("请先填写测试命令", type = ToastType.Error)
                    }

                    else -> scope.launch {
                        sandboxRunning = true
                        sandboxOutput = "正在执行...\n"
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val workspaceDir = File(trimmedWorkspacePath).apply { mkdirs() }
                                val result = PRootSandbox(context).executeShell(
                                    config = SandboxConfig(
                                        rootfsDir = File(trimmedRootfsPath),
                                        workingDirectory = "/workspace",
                                        bindMounts = listOf(
                                            SandboxBind(workspaceDir, "/workspace")
                                        ),
                                    ),
                                    script = trimmedCommand,
                                    timeoutMillis = 20_000,
                                )
                                buildString {
                                    appendLine("$ rootfs=$trimmedRootfsPath")
                                    appendLine("$ workspace=$trimmedWorkspacePath -> /workspace")
                                    appendLine("$ cmd=$trimmedCommand")
                                    appendLine("exit=${result.exitCode}, timedOut=${result.timedOut}, succeeded=${result.succeeded}")
                                    appendLine()
                                    appendLine("[stdout]")
                                    appendLine(result.stdout.ifBlank { "<empty>" })
                                    appendLine()
                                    appendLine("[stderr]")
                                    appendLine(result.stderr.ifBlank { "<empty>" })
                                }
                            }
                        }.onSuccess {
                            sandboxOutput = it
                            toaster.show("Sandbox 执行完成")
                        }.onFailure {
                            sandboxOutput = buildString {
                                appendLine("执行失败")
                                appendLine(it.message ?: it.javaClass.name)
                                appendLine()
                                appendLine(it.stackTraceToString())
                            }
                            toaster.show("Sandbox 执行失败", type = ToastType.Error)
                        }
                        sandboxRunning = false
                    }
                }
            }
        ) {
            Text(if (sandboxRunning) "执行中..." else "运行 Sandbox 命令")
        }
        OutlinedTextField(
            value = sandboxOutput,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp),
            label = { Text("输出") },
            textStyle = TextStyle(fontFamily = JetbrainsMono),
        )

        HorizontalDivider()

        Text("Launch Stats", style = MaterialTheme.typography.labelMedium)

        var launchCountInput by remember(settings.launchCount) {
            mutableStateOf(settings.launchCount.toString())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = launchCountInput,
                onValueChange = { launchCountInput = it },
                label = { Text("launchCount (current: ${settings.launchCount})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                launchCountInput.toIntOrNull()?.let {
                    vm.updateSettings(settings.copy(launchCount = it))
                }
            }) {
                Text("Set")
            }
        }

        var dismissedAtInput by remember(settings.sponsorAlertDismissedAt) {
            mutableStateOf(settings.sponsorAlertDismissedAt.toString())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = dismissedAtInput,
                onValueChange = { dismissedAtInput = it },
                label = { Text("sponsorAlertDismissedAt (current: ${settings.sponsorAlertDismissedAt})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                dismissedAtInput.toIntOrNull()?.let {
                    vm.updateSettings(settings.copy(sponsorAlertDismissedAt = it))
                }
            }) {
                Text("Set")
            }
        }

        var markdown by remember { mutableStateOf("") }
        MarkdownBlock(markdown, modifier = Modifier.fillMaxWidth())
        MathBlock(markdown)
        OutlinedTextField(
            value = markdown,
            onValueChange = { markdown = it },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
