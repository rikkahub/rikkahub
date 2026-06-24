package me.rerere.rikkahub.ui.pages.setting.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.ai.tools.local.SshAuth
import me.rerere.rikkahub.data.ai.tools.local.isUsable
import me.rerere.rikkahub.data.ai.tools.local.newJSch
import me.rerere.rikkahub.data.ai.tools.local.openSshSession
import me.rerere.rikkahub.data.ai.tools.local.probeReachability

private const val SSH_TEST_TIMEOUT_MS = 15_000

private data class TestResult(
    val networkLabel: String?,
    val networkMs: Long,
    val handshakeOk: Boolean,
    val handshakeMs: Long?,
    val fingerprint: String?,
    val commandOk: Boolean?,
    val error: String? = null,
)

@Composable
fun SshTestDialog(
    host: SshHostEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var testing by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<TestResult?>(null) }

    LaunchedEffect(host) {
        testing = true
        result = runTest(context, host)
        testing = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.setting_ssh_test_title, host.name))
        },
        text = {
            if (testing) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.setting_ssh_testing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val r = result ?: return@AlertDialog
                Column(modifier = Modifier.fillMaxWidth()) {
                    TestRow(
                        label = stringResource(R.string.setting_ssh_test_network),
                        ok = r.networkLabel != null,
                        detail = r.networkLabel?.let { "$it (${r.networkMs}ms)" }
                            ?: stringResource(R.string.setting_ssh_test_fail),
                    )
                    Spacer(Modifier.height(8.dp))
                    TestRow(
                        label = stringResource(R.string.setting_ssh_test_handshake),
                        ok = r.handshakeOk,
                        detail = r.handshakeMs?.let { "${it}ms" }
                            ?: stringResource(R.string.setting_ssh_test_fail),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (r.fingerprint != null) {
                        TestRow(
                            label = stringResource(R.string.setting_ssh_test_fingerprint),
                            ok = true,
                            detail = r.fingerprint,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (r.commandOk != null) {
                        TestRow(
                            label = stringResource(R.string.setting_ssh_test_command),
                            ok = r.commandOk,
                            detail = if (r.commandOk) stringResource(R.string.setting_ssh_test_success)
                                     else stringResource(R.string.setting_ssh_test_fail),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (r.error != null) {
                        Text(
                            text = r.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                copyTestInfo(context, host, result)
            }) {
                Icon(HugeIcons.Copy01, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.setting_ssh_copy_info))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun TestRow(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) HugeIcons.CheckmarkCircle01 else HugeIcons.AlertCircle,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private suspend fun runTest(context: Context, host: SshHostEntity): TestResult = withContext(Dispatchers.IO) {
    val auth = SshAuth(
        password = host.password,
        privateKey = host.privateKey,
        passphrase = host.passphrase,
    )
    if (!auth.isUsable()) {
        return@withContext TestResult(null, 0, false, null, null, null, "No authentication configured")
    }

    // 1) TCP 探测
    val probe = probeReachability(context, host.host, host.port)
    if (probe.winningNetwork == null) {
        return@withContext TestResult(
            networkLabel = null, networkMs = probe.totalMs,
            handshakeOk = false, handshakeMs = null,
            fingerprint = null, commandOk = null,
            error = "TCP unreachable via any network. ${probe.failures.joinToString("; ") { "${it.first}: ${it.second}" }}",
        )
    }

    // 2) SSH 握手
    val handshakeStart = System.currentTimeMillis()
    try {
        val jsch = newJSch(context)
        val session = openSshSession(
            jsch = jsch,
            host = host.host,
            port = host.port,
            user = host.user,
            auth = auth,
            timeoutMs = SSH_TEST_TIMEOUT_MS,
            network = probe.winningNetwork,
        )
        val handshakeMs = System.currentTimeMillis() - handshakeStart
        val hostKey = session.getHostKey()
        val fingerprint = hostKey?.getFingerPrint(jsch)?.takeIf { it.isNotBlank() }
            ?: "SHA256:${hostKey?.getKey()?.let { java.lang.Long.toHexString(it.hashCode().toLong()) }}"

        // 3) 验证命令
        val cmdStart = System.currentTimeMillis()
        val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
        var cmdOk = false
        try {
            channel.setCommand("echo ok && whoami")
            channel.connect(SSH_TEST_TIMEOUT_MS)
            val out = java.io.ByteArrayOutputStream()
            val input = channel.inputStream
            val buf = ByteArray(1024)
            var len: Int
            while (input.read(buf).also { len = it } > 0) {
                out.write(buf, 0, len)
            }
            val output = out.toString(Charsets.UTF_8).trim()
            cmdOk = output.contains("ok") && output.lines().size >= 2
        } catch (_: Exception) {
            cmdOk = false
        } finally {
            try { channel.disconnect() } catch (_: Exception) {}
            try { session.disconnect() } catch (_: Exception) {}
        }

        TestResult(
            networkLabel = probe.winningLabel,
            networkMs = probe.totalMs,
            handshakeOk = true,
            handshakeMs = handshakeMs,
            fingerprint = fingerprint,
            commandOk = cmdOk,
        )
    } catch (e: Exception) {
        TestResult(
            networkLabel = probe.winningLabel,
            networkMs = probe.totalMs,
            handshakeOk = false,
            handshakeMs = System.currentTimeMillis() - handshakeStart,
            fingerprint = null,
            commandOk = null,
            error = e.message ?: e::class.simpleName,
        )
    }
}

private fun copyTestInfo(context: Context, host: SshHostEntity, result: TestResult?) {
    val info = buildString {
        appendLine("SSH Test: ${host.name} (${host.user}@${host.host}:${host.port})")
        appendLine()
        if (result != null) {
            appendLine("Network: ${result.networkLabel ?: "unreachable"} (${result.networkMs}ms)")
            appendLine("Handshake: ${if (result.handshakeOk) "OK" else "FAIL"} (${result.handshakeMs ?: "N/A"}ms)")
            if (result.fingerprint != null) appendLine("Fingerprint: ${result.fingerprint}")
            if (result.commandOk != null) appendLine("Command: ${if (result.commandOk) "OK" else "FAIL"}")
            if (result.error != null) appendLine("Error: ${result.error}")
        } else {
            appendLine("No test result available")
        }
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("SSH Test Report", info))
}
