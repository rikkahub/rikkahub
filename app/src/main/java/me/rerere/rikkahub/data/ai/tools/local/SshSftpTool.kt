package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.SshHostRepository
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private const val TAG_SFTP = "SshSftpTool"

private fun openSftp(session: Session): ChannelSftp {
    val channel = session.openChannel("sftp") as ChannelSftp
    channel.connect()
    return channel
}

/**
 * Open a connected SSH session to a saved host with the same probe → bind → cancel logic
 * SSH exec uses. Stashes the live Session in [sessionRef] so the outer cancellable wrapper
 * can disconnect it on coroutine cancellation. Returns the [block]'s envelope, or a
 * connect-error envelope if the handshake failed before [block] could run.
 */
private suspend fun withSavedHostSession(
    context: Context,
    repo: SshHostRepository,
    name: String,
    timeoutMs: Int,
    sessionRef: AtomicReference<Session?>,
    block: (Session) -> JsonObject,
): JsonObject {
    val h = repo.getByName(name)
        ?: return buildJsonObject { put("error", "no saved host: $name") }
    val auth = SshAuth(password = h.password, privateKey = h.privateKey, passphrase = h.passphrase)
    if (!auth.isUsable()) {
        return buildJsonObject { put("error", "saved host has no usable credentials") }
    }
    // Same probe-then-bind path the exec tool uses. Without this, SFTP traffic is left to
    // Android's default-network selection, which on adaptive-routing devices routes the
    // app's outgoing connections away from WiFi LAN even though WiFi is up — meaning every
    // recent fix that gave SSH exec the WiFi-binding behaviour was bypassed for SFTP.
    val outcome = probeReachability(context, h.host, h.port)
    if (outcome.winningNetwork == null && outcome.failures.isNotEmpty()) {
        return unreachableEnvelope(h.host, h.port, outcome)
    }
    return runInterruptible(Dispatchers.IO) {
        val jsch = newJSch(context)
        val session = try {
            openSshSession(jsch, h.host, h.port, h.user, auth, timeoutMs, network = outcome.winningNetwork)
        } catch (e: Throwable) {
            Log.w(TAG_SFTP, "ssh handshake failed", e)
            return@runInterruptible wrapConnectError(h.host, e)
        }
        sessionRef.set(session)
        try {
            block(session)
        } finally {
            sessionRef.set(null)
            try { session.disconnect() } catch (_: Throwable) {}
        }
    }
}

/** Upload a local file to a saved-host's remote path via SFTP. */
fun sshUploadTool(context: Context, repo: SshHostRepository): Tool = Tool(
    name = "ssh_upload",
    description = """
        Upload a local file from the device to a remote path on a saved SSH host using SFTP.
        Both paths are absolute file paths. The remote directory must already exist.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("local_path", buildJsonObject { put("type", "string"); put("description", "Absolute path on the device") })
                put("remote_path", buildJsonObject { put("type", "string"); put("description", "Absolute remote path (full filename)") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 60, max 600") })
            },
            required = listOf("name", "local_path", "remote_path")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val localPath = p["local_path"]?.jsonPrimitive?.contentOrNull ?: error("local_path is required")
        val remotePath = p["remote_path"]?.jsonPrimitive?.contentOrNull ?: error("remote_path is required")
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 600)
        val localFile = File(localPath)
        if (!localFile.exists() || !localFile.isFile) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "local file not found: $localPath") }.toString()
            ))
        }
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            withSavedHostSession(context, repo, name, timeoutSec * 1000, sessionRef) { session ->
                val sftp = openSftp(session)
                try {
                    localFile.inputStream().use { input -> sftp.put(input, remotePath) }
                    buildJsonObject {
                        put("success", true)
                        put("remote_path", remotePath)
                        put("bytes", localFile.length())
                    }
                } catch (e: Throwable) {
                    buildJsonObject { put("error", "sftp put failed: ${e.message ?: "unknown"}") }
                } finally {
                    try { sftp.disconnect() } catch (_: Throwable) {}
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** Download a remote file from a saved host to a local path via SFTP. */
fun sshDownloadTool(context: Context, repo: SshHostRepository): Tool = Tool(
    name = "ssh_download",
    description = """
        Download a remote file from a saved SSH host to a local path on the device using SFTP.
        Both paths are absolute file paths. The local directory must already exist (or be the
        app's cache/files dir).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("remote_path", buildJsonObject { put("type", "string"); put("description", "Absolute remote file path") })
                put("local_path", buildJsonObject { put("type", "string"); put("description", "Absolute local path on the device") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 60, max 600") })
            },
            required = listOf("name", "remote_path", "local_path")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val remotePath = p["remote_path"]?.jsonPrimitive?.contentOrNull ?: error("remote_path is required")
        val localPath = p["local_path"]?.jsonPrimitive?.contentOrNull ?: error("local_path is required")
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 600)
        val localFile = File(localPath)
        // Ensure the local parent directory exists.
        try { localFile.parentFile?.mkdirs() } catch (_: Throwable) {}
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            withSavedHostSession(context, repo, name, timeoutSec * 1000, sessionRef) { session ->
                val sftp = openSftp(session)
                var ok = false
                try {
                    localFile.outputStream().use { output -> sftp.get(remotePath, output) }
                    ok = true
                    buildJsonObject {
                        put("success", true)
                        put("local_path", localFile.absolutePath)
                        put("bytes", localFile.length())
                    }
                } catch (e: Throwable) {
                    buildJsonObject { put("error", "sftp get failed: ${e.message ?: "unknown"}") }
                } finally {
                    // Delete partial files left by a failed get. Otherwise the user is left
                    // with a half-written file that LOOKS like a successful download —
                    // misleading and (for binaries) dangerous to execute.
                    if (!ok) try { if (localFile.exists()) localFile.delete() } catch (_: Throwable) {}
                    try { sftp.disconnect() } catch (_: Throwable) {}
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
