package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FingerPrint
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.ssh.SshAuthType
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val MAX_PRIVATE_KEY_BYTES = 64 * 1024

@Composable
fun WorkspaceSshEditorPage(id: String?) {
    val navController = LocalNavController.current
    val vm: WorkspaceSshEditorVM = koinViewModel(key = "ssh-editor-$id", parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 测试连接和保存针对开始时的表单快照, 期间锁定表单
    val editable = !state.busy

    LaunchedEffect(state.saved) {
        if (state.saved) navController.popBackStack()
    }

    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readLimited(MAX_PRIVATE_KEY_BYTES) }
        }.getOrNull()
        if (text != null) vm.update { it.copy(privateKey = text.trim()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "新建 SSH 工作区" else "编辑 SSH 连接") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::save, enabled = !state.busy) {
                        Icon(HugeIcons.Tick02, contentDescription = "保存")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text("基本信息") }) {
                    formItem(headlineContent = { Text("名称") }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.name,
                            onValueChange = { value -> vm.update { it.copy(name = value) } },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("我的服务器") },
                            singleLine = true,
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("连接") }) {
                    formItem(headlineContent = { Text("主机") }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.host,
                            onValueChange = { value -> vm.update { it.copy(host = value.trim()) } },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("example.com 或 192.168.1.10") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    formItem(headlineContent = { Text("端口") }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.port,
                            onValueChange = { value ->
                                vm.update { it.copy(port = value.filter(Char::isDigit).take(5)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    formItem(headlineContent = { Text("用户名") }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.username,
                            onValueChange = { value -> vm.update { it.copy(username = value.trim()) } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("认证") }) {
                    formItem(headlineContent = { Text("认证方式") }) {
                        val options = listOf(SshAuthType.PASSWORD to "密码", SshAuthType.PRIVATE_KEY to "私钥")
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            options.forEachIndexed { index, (type, label) ->
                                SegmentedButton(
                                    enabled = editable,
                                    selected = state.authType == type,
                                    onClick = { vm.update { it.copy(authType = type) } },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                    when (state.authType) {
                        SshAuthType.PASSWORD -> formItem(headlineContent = { Text("密码") }) {
                            SecretTextField(
                                enabled = editable,
                                value = state.password,
                                onValueChange = { value -> vm.update { it.copy(password = value) } },
                                placeholder = if (state.hasSavedPassword) "已保存，留空保持不变" else null,
                            )
                        }

                        SshAuthType.PRIVATE_KEY -> {
                            formItem(
                                headlineContent = { Text("私钥") },
                                supportingContent = { Text("支持 OpenSSH 和 PEM 格式的 Ed25519、ECDSA、RSA 私钥") },
                            ) {
                                OutlinedTextField(
                                    enabled = editable,
                                    value = state.privateKey,
                                    onValueChange = { value -> vm.update { it.copy(privateKey = value) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text(
                                            if (state.hasSavedPrivateKey) {
                                                "已保存，留空保持不变"
                                            } else {
                                                "-----BEGIN OPENSSH PRIVATE KEY-----"
                                            }
                                        )
                                    },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    minLines = 3,
                                    maxLines = 8,
                                )
                                OutlinedButton(
                                    enabled = editable,
                                    onClick = { privateKeyPicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        HugeIcons.FileImport,
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize),
                                    )
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text("从文件导入")
                                }
                            }
                            formItem(headlineContent = { Text("私钥口令") }) {
                                SecretTextField(
                                    enabled = editable,
                                    value = state.passphrase,
                                    onValueChange = { value -> vm.update { it.copy(passphrase = value) } },
                                    placeholder = "私钥未加密时留空",
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("工作目录") }) {
                    formItem(
                        headlineContent = { Text("远程目录") },
                        supportingContent = { Text("AI 默认在这里读写文件和执行命令。留空则测试连接后使用远程用户的主目录") },
                    ) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.remoteDir,
                            onValueChange = { value -> vm.update { it.copy(remoteDir = value.trim()) } },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("/home/user/project") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("安全") }) {
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.FingerPrint) },
                        headlineContent = { Text("主机密钥") },
                        supportingContent = {
                            val hostKey = state.hostKey
                            if (hostKey == null) {
                                Text("尚未确认，测试连接或保存时会请你核对服务器指纹")
                            } else {
                                Text(
                                    text = "${hostKey.type}\n${hostKey.fingerprint}",
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                    )
                }
            }

            item {
                ProbeSection(
                    state = state,
                    onTest = vm::testConnection,
                )
            }
        }
    }

    state.pendingHostKey?.let { pending ->
        HostKeyDialog(
            pending = pending,
            address = "${pending.draft.host}:${pending.draft.port}",
            onTrust = vm::trustHostKey,
            onDismiss = vm::dismissHostKey,
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text(if (state.canSaveAnyway) "连接失败" else "出错了") },
            text = {
                Text(
                    if (state.canSaveAnyway) "$message\n\n仍要保存吗？之后可以在工作区详情里重新测试连接。" else message
                )
            },
            confirmButton = {
                if (state.canSaveAnyway) {
                    TextButton(onClick = vm::saveAnyway) { Text("仍然保存") }
                } else {
                    TextButton(onClick = vm::dismissError) { Text("确定") }
                }
            },
            dismissButton = if (state.canSaveAnyway) {
                { TextButton(onClick = vm::dismissError) { Text("取消") } }
            } else {
                null
            },
        )
    }
}

@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    enabled: Boolean,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
            }
        },
    )
}

@Composable
private fun ProbeSection(
    state: WorkspaceSshEditorState,
    onTest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(
            onClick = onTest,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.testing) {
                LoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
            } else {
                Icon(HugeIcons.Connect, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(if (state.testing) "正在连接…" else "测试连接")
        }
        state.probeResult?.let { result ->
            CardGroup(title = { Text("连接成功") }) {
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.CloudServer) },
                    headlineContent = { Text("Shell") },
                    supportingContent = { Text(result.shell) },
                )
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                    headlineContent = { Text("工作目录") },
                    supportingContent = {
                        Text(
                            when {
                                state.remoteDir.isBlank() || state.remoteDir == result.homeDir -> "${result.homeDir}（主目录）"
                                result.remoteDirExists -> state.remoteDir
                                else -> "${state.remoteDir}（不存在，首次使用时自动创建）"
                            }
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun HostKeyDialog(
    pending: PendingHostKey,
    address: String,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    val changed = pending.expected != null
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(HugeIcons.Key01, contentDescription = null) },
        title = { Text(if (changed) "主机密钥已变化" else "确认主机密钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (changed) {
                        "$address 出示的主机密钥和之前信任的不一致。可能是服务器重装过，也可能有人在冒充服务器（中间人攻击）。确认无误前不要继续。"
                    } else {
                        "首次连接 $address，请核对服务器的主机密钥指纹。可以在服务器上运行 ssh-keygen -lf /etc/ssh/ssh_host_${pending.actual.type.removePrefix("ssh-").substringBefore('-')}_key.pub 对照。"
                    }
                )
                Text(
                    text = "${pending.actual.type}\n${pending.actual.fingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            if (changed) {
                Button(
                    onClick = onTrust,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("信任新密钥") }
            } else {
                TextButton(onClick = onTrust) { Text("信任并继续") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// 私钥文件很小, 超过上限的多半是选错了文件, 不整个读进内存
private fun InputStream.readLimited(limit: Int): String? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (output.size() + read > limit) return null
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}
