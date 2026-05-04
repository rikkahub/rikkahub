package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Properties

private const val TAG_SSH = "SshTool"

/**
 * Resolves [host] to an IPv4 address string. JSch / `InetAddress.getByName(host)` returns the
 * AAAA record first when both v4 and v6 are present, and JSch's `Socket(addr, port)` does NOT
 * implement Happy Eyeballs — it sits on the IPv6 SYN until the connect timeout fires, even
 * when the server only listens on IPv4. Termux's OpenSSH races both stacks in parallel and
 * never sees this failure mode, which is why `ssh user@host` from Termux works while our
 * direct JSch path on the same Pixel times out.
 *
 * Returns null if [host] is already a literal IP, if no IPv4 record exists, or if DNS fails.
 * Caller falls back to the original [host] string in that case.
 */
private fun resolveToIPv4(host: String): String? {
    // Fast path: literal IPs go through unchanged.
    if (runCatching { InetAddress.getByName(host) }.getOrNull() is Inet4Address) return null
    if (runCatching { InetAddress.getByName(host) }.getOrNull() is Inet6Address &&
        host.contains(':')) return null
    return try {
        val addrs = InetAddress.getAllByName(host)
        val v4 = addrs.firstOrNull { it is Inet4Address }
        v4?.hostAddress?.also {
            Log.i(TAG_SSH, "resolveToIPv4: $host -> $it (skipping ${addrs.size - 1} other records)")
        }
    } catch (t: Throwable) {
        Log.w(TAG_SSH, "resolveToIPv4: $host failed", t)
        null
    }
}

/**
 * Auth bundle. Either [password] or [privateKey] must be non-blank for connect to succeed.
 */
internal data class SshAuth(
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
)

internal fun SshAuth.isUsable() = !password.isNullOrBlank() || !privateKey.isNullOrBlank()

/**
 * Construct a JSch instance pre-loaded with the app's persistent known_hosts file. New host
 * keys are automatically appended ("StrictHostKeyChecking=accept-new"); changed keys cause
 * the connect to fail (MITM protection).
 */
internal fun newJSch(context: Context): JSch {
    val jsch = JSch()
    val knownHosts = File(context.filesDir, "known_hosts")
    if (!knownHosts.exists()) {
        try { knownHosts.createNewFile() } catch (_: Throwable) {}
    }
    try { jsch.setKnownHosts(knownHosts.absolutePath) } catch (_: Throwable) {}
    return jsch
}

/**
 * Open a connected SSH session. Caller MUST call [Session.disconnect] in a finally block.
 * Throws on auth/connect failure (caller wraps in try/catch and surfaces a JSON error).
 */
internal fun openSshSession(
    jsch: JSch,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    timeoutMs: Int,
): Session {
    if (!auth.privateKey.isNullOrBlank()) {
        val keyBytes = auth.privateKey.toByteArray(Charsets.UTF_8)
        val passBytes = auth.passphrase?.toByteArray(Charsets.UTF_8)
        jsch.addIdentity("rikkahub-ssh-key-${System.nanoTime()}", keyBytes, null, passBytes)
    }
    // Force IPv4 resolution before handing the address to JSch. Solves the "ssh works in
    // Termux but times out in our app" Pixel-only failure: Android's DnsResolver returns
    // AAAA first and JSch sits on the IPv6 SYN for the full timeout instead of falling
    // back to v4. setHostKeyAlias keeps known_hosts comparisons keyed by the human-readable
    // hostname so trust-on-first-use still works the same.
    val ipv4 = resolveToIPv4(host)
    val effectiveHost = ipv4 ?: host
    val session = jsch.getSession(user, effectiveHost, port)
    if (ipv4 != null && ipv4 != host) {
        session.setHostKeyAlias(host)
    }
    if (!auth.password.isNullOrBlank()) session.setPassword(auth.password)
    session.setConfig(Properties().apply {
        // accept-new: trust on first use, fail if a known host's key changes
        setProperty("StrictHostKeyChecking", "accept-new")
        setProperty("PreferredAuthentications", "publickey,keyboard-interactive,password")
    })
    session.connect(timeoutMs)
    return session
}

/** Run a single command on an open session. Returns a JSON object with exit_code/stdout/stderr. */
internal fun runOnSession(session: Session, command: String, timeoutMs: Int): JsonObject {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val exitCode: Int
    val channel = session.openChannel("exec") as ChannelExec
    try {
        channel.setCommand(command)
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        channel.connect(timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!channel.isClosed && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        exitCode = channel.exitStatus
    } finally {
        try { channel.disconnect() } catch (_: Throwable) {}
    }
    return buildJsonObject {
        put("success", exitCode == 0)
        put("exit_code", exitCode)
        put("stdout", stdout.toString(Charsets.UTF_8))
        put("stderr", stderr.toString(Charsets.UTF_8))
    }
}

/**
 * One-shot SSH exec — for hosts the user doesn't want to save (anonymous / ad-hoc / one-time).
 * For frequently-used hosts, prefer save_ssh_host + ssh_exec_saved which is shorter for the LLM
 * to call and avoids leaking credentials into chat history on every call.
 */
fun sshExecTool(context: Context): Tool = Tool(
    name = "ssh_exec",
    description = """
        Connect to a remote host via SSH and run a single shell command. Returns stdout, stderr,
        and exit code. For destructive or system-level commands you should explicitly confirm
        with the user before invoking. Pass either password OR private_key for authentication.
        For hosts you'll use repeatedly, prefer save_ssh_host + ssh_exec_saved instead so
        credentials don't appear in chat history every time.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP address") })
                put("port", buildJsonObject { put("type", "integer"); put("description", "SSH port, default 22") })
                put("user", buildJsonObject { put("type", "string"); put("description", "SSH username") })
                put("password", buildJsonObject { put("type", "string"); put("description", "Password (use only if no private_key)") })
                put("private_key", buildJsonObject { put("type", "string"); put("description", "Full PEM/OpenSSH private key contents") })
                put("passphrase", buildJsonObject { put("type", "string"); put("description", "Optional passphrase for the private key") })
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run on the remote host") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout including connect+exec, default 30, max 300") })
            },
            required = listOf("host", "user", "command")
        )
    },
    execute = {
        val p = it.jsonObject
        val host = p["host"]?.jsonPrimitive?.contentOrNull ?: error("host is required")
        val user = p["user"]?.jsonPrimitive?.contentOrNull ?: error("user is required")
        val command = p["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        val port = p["port"]?.jsonPrimitive?.intOrNull ?: 22
        val auth = SshAuth(
            password = p["password"]?.jsonPrimitive?.contentOrNull,
            privateKey = p["private_key"]?.jsonPrimitive?.contentOrNull,
            passphrase = p["passphrase"]?.jsonPrimitive?.contentOrNull,
        )
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        if (!auth.isUsable()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "must provide password or private_key") }.toString()
            ))
        }
        val payload = withTimeoutOrNull(timeoutSec * 1000L) {
            withContext(Dispatchers.IO) {
                execOneShot(context, host, port, user, auth, command, timeoutSec * 1000)
            }
        } ?: buildJsonObject { put("error", "timeout") }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** Helper used by sshExecTool: open session, run command, disconnect, return JSON. */
internal fun execOneShot(
    context: Context,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    command: String,
    timeoutMs: Int,
): JsonObject {
    val jsch = newJSch(context)
    val session = try {
        openSshSession(jsch, host, port, user, auth, timeoutMs)
    } catch (e: Throwable) {
        return wrapConnectError(host, e)
    }
    return try {
        runOnSession(session, command, timeoutMs)
    } catch (e: Throwable) {
        buildJsonObject { put("error", "exec failed: ${e.message ?: "unknown"}") }
    } finally {
        try { session.disconnect() } catch (_: Throwable) {}
    }
}

/**
 * Translate JSch connection failures into structured envelopes the LLM can act on. The big
 * one is "HostKey has been changed" — happens when the user reinstalled the remote (or, less
 * commonly, when there's a real MITM). We surface this as a distinct error with a `recovery`
 * field telling the LLM to call ssh_forget_host_key after confirming with the user.
 */
internal fun wrapConnectError(host: String, e: Throwable): JsonObject {
    val msg = e.message.orEmpty()
    val isHostKeyChange = msg.contains("HostKey", ignoreCase = true) ||
        msg.contains("host key", ignoreCase = true) ||
        msg.contains("identification has changed", ignoreCase = true) ||
        msg.contains("REMOTE HOST IDENTIFICATION", ignoreCase = true)
    return if (isHostKeyChange) {
        buildJsonObject {
            put("error", "host key changed for $host (possible reinstall, or MITM)")
            put("recovery", "If the user trusts this host (e.g. they just reinstalled it), call ssh_forget_host_key with host=\"$host\" then retry. Do NOT forget the key without explicit user confirmation — a changed key can also indicate an attacker.")
            put("raw", msg)
        }
    } else {
        buildJsonObject { put("error", "connect failed: ${msg.ifBlank { e::class.simpleName ?: "unknown" }}") }
    }
}

/**
 * Remove all stored host keys for [host] from the persistent known_hosts file. Use after the
 * user confirms they reinstalled the remote — the next connect will trust the new key per
 * the accept-new policy.
 */
internal fun forgetHostKey(context: Context, host: String): Int {
    val jsch = newJSch(context)
    val before = jsch.hostKeyRepository.hostKey?.count { it.host == host } ?: 0
    if (before == 0) return 0
    jsch.hostKeyRepository.remove(host, null)
    return before
}
