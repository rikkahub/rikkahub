package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
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
import java.net.InetAddress
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

private const val TAG_SSH = "SshTool"

/** Per-network probe timeout. Tight because we now race networks in parallel. */
private const val PROBE_PER_NETWORK_TIMEOUT_MS = 2_500
/** Default per-network connect timeout used when JSch creates its own socket. */
private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000

/**
 * Keep-alive parameters for every JSch session. Without these, JSch sends nothing on an idle
 * channel, so a long-running command (e.g. a build, a `tail -f`) sits silent long enough for
 * an intermediate NAT/firewall to drop the connection's state table entry — the next byte
 * then hits a black hole and the command "dies" with no error. 30s × 3 missed = ~90s before
 * JSch itself declares the session dead, which keeps NATs warm while still failing fast on a
 * genuinely-gone server.
 */
private const val SERVER_ALIVE_INTERVAL_MS = 30_000
private const val SERVER_ALIVE_COUNT_MAX = 3

/**
 * Process-lifetime DNS cache shared by every SSH session-build path. 60s TTL — long enough
 * to spare a 15-minute-interval workflow a fresh lookup on every run, short enough that a
 * DHCP/DNS change is picked up quickly. Self-registers with NetworkChangeMonitor so a
 * default-network handoff drops the cache (a cached IP from the old network's DNS could be
 * wrong on the new one).
 */
internal val sshDnsCache: DnsCache = DnsCache().also { cache ->
    runCatching {
        me.rerere.rikkahub.utils.NetworkChangeMonitor.addNetworkChangeListener {
            cache.invalidateAll()
        }
    }
}

/**
 * Caps on stdout / stderr returned to the LLM. Without these, `cat /var/log/syslog` /
 * `journalctl` / `ls -R /` push megabytes into the next prompt, blowing the context
 * budget on a single tool call. Mirrors the Termux tool's caps so behaviour is
 * consistent across local and remote shell.
 */
private const val MAX_RETURNED_STDOUT = 8_000
private const val MAX_RETURNED_STDERR = 2_000

/** Truncate [s] to [max] chars and append a "[truncated; N bytes more]" suffix. */
private fun cap(s: String, max: Int): String =
    if (s.length > max) s.take(max) + "\n…[truncated; ${s.length - max} bytes more]" else s

/**
 * Resolves [host] to an IPv4 address string. JSch's `Socket(addr, port)` does NOT implement
 * Happy Eyeballs — it sits on the IPv6 SYN until the connect timeout fires, even when the
 * server only listens on IPv4. Termux's OpenSSH races both stacks in parallel and never
 * sees this failure mode, which is why `ssh user@host` from Termux works while our direct
 * JSch path on the same Pixel times out.
 *
 * Returns null if [host] is already a literal IP, if no IPv4 record exists, or if DNS fails.
 * Caller falls back to the original [host] string in that case.
 *
 * Successful resolutions are memoised in [sshDnsCache] (60s TTL) so a workflow that SSHes to
 * the same host on a tight interval doesn't pay a DNS round-trip every connect. A literal IP
 * input bypasses the cache entirely — it's not a name, there's nothing to resolve or stale.
 * Failures are never cached, so a transient DNS hiccup can't poison the next 60s of connects.
 */
private fun resolveToIPv4(host: String): String? {
    sshDnsCache.get(host)?.let { cached ->
        Log.i(TAG_SSH, "resolveToIPv4: $host -> $cached (dns cache hit)")
        return cached
    }
    return try {
        val addrs = InetAddress.getAllByName(host)
        // Already a literal IP (v4 or v6)? getAllByName returns it as-is and the printed
        // hostAddress equals the input (modulo case). Don't try to "resolve" it further.
        if (addrs.isNotEmpty() && addrs[0].hostAddress.equals(host, ignoreCase = true)) return null
        val v4 = addrs.firstOrNull { it is Inet4Address } ?: return null
        v4.hostAddress?.also {
            sshDnsCache.put(host, it)
            Log.i(TAG_SSH, "resolveToIPv4: $host -> $it (skipping ${addrs.size - 1} other records)")
        }
    } catch (t: Throwable) {
        Log.w(TAG_SSH, "resolveToIPv4: $host failed", t)
        null
    }
}

/**
 * Returns every Network the device has, ordered for the SSH probe. Each entry is a
 * (label, network-or-null) pair. The null entry means "do not bind — let Android pick the
 * default route." All WiFi networks the device exposes are included individually (a phone
 * may have a saved-but-inactive hotspot AND the active WiFi visible at the same time;
 * filtering down to one would skip the working network on adaptive-routing devices).
 *
 * WiFi networks are sorted with VALIDATED-INTERNET ones first so the probe doesn't waste
 * its first slot on a captive-portal WiFi when a real one is available. With the parallel
 * probe in [probeReachability], having more candidates costs no extra latency anyway.
 */
private fun enumerateCandidateNetworks(ctx: Context): List<Pair<String, Network?>> {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return listOf("default" to null)
    val out = mutableListOf<Pair<String, Network?>>()
    // allNetworks deprecated at API 31; the recommended NetworkCallback path is async and
    // doesn't fit our synchronous enumeration here. Behaviour-equivalent and still functional.
    @Suppress("DEPRECATION")
    val all = try { cm.allNetworks.toList() } catch (_: Throwable) { emptyList() }
    fun caps(n: Network) = try { cm.getNetworkCapabilities(n) } catch (_: Throwable) { null }

    val wifis = all.filter { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        .sortedByDescending {
            val c = caps(it)
            val validated = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val internet = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            (if (validated) 2 else 0) + (if (internet) 1 else 0)
        }
    wifis.forEachIndexed { i, n ->
        out += (if (wifis.size == 1) "wifi" else "wifi$i") to n
    }
    all.firstOrNull { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
        ?.let { out += "ethernet" to it }
    all.firstOrNull { caps(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }
        ?.let { out += "cellular" to it }
    out += "default" to null
    return out
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
    val knownHosts = knownHostsFile(context)
    if (!knownHosts.exists()) {
        try { knownHosts.createNewFile() } catch (_: Throwable) {}
    }
    try { jsch.setKnownHosts(knownHosts.absolutePath) } catch (_: Throwable) {}
    return jsch
}

internal fun knownHostsFile(context: Context): File = File(context.filesDir, "known_hosts")

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
    network: Network? = null,
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
    // Always install our custom socket factory so JSch's internal sockets get a bounded
    // connect timeout. When [network] is non-null we ALSO bind sockets to that specific
    // Network so SSH traffic stays on the chosen transport, bypassing Android's adaptive
    // routing that would otherwise re-route app traffic away from the chosen WiFi LAN.
    session.setSocketFactory(NetworkBoundSocketFactory(network, SOCKET_CONNECT_TIMEOUT_MS))
    // Keep-alive: send a server-alive probe every 30s of channel idle, give up after 3
    // unanswered. Without this an intermediate NAT/firewall silently drops the connection's
    // state entry during a long-running command and the session black-holes. This is the
    // single shared session-build path for ssh_exec, ssh_exec_saved, and SFTP
    // upload/download, so setting it here covers every JSch session in the app.
    session.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
    session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
    session.connect(timeoutMs)
    return session
}

/**
 * JSch SocketFactory with two responsibilities:
 *  1. Optionally bind every newly-created socket to a specific Android [Network], to keep
 *     SSH traffic on a chosen transport when the OS's default-network selection would
 *     route it elsewhere.
 *  2. Always pass an explicit connect timeout to Socket.connect() so a stalled SYN can't
 *     hang for the kernel default (~75s) — JSch's session.connect(timeout) controls only
 *     the handshake reads, NOT this socket's TCP connect.
 */
private class NetworkBoundSocketFactory(
    private val network: Network?,
    private val connectTimeoutMs: Int,
) : com.jcraft.jsch.SocketFactory {
    override fun createSocket(host: String, port: Int): java.net.Socket {
        val s = java.net.Socket()
        if (network != null) {
            try { network.bindSocket(s) } catch (_: Throwable) { /* best-effort */ }
        }
        s.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
        return s
    }

    override fun getInputStream(socket: java.net.Socket): java.io.InputStream =
        socket.getInputStream()

    override fun getOutputStream(socket: java.net.Socket): java.io.OutputStream =
        socket.getOutputStream()
}

/**
 * Outcome of a reachability probe. [winningNetwork] is the Network that successfully
 * completed the TCP handshake (null if the unbound default-route attempt was the winner);
 * pass it to [openSshSession] so JSch's handshake follows the same route. [failures] lists
 * the per-network failure reasons when no probe succeeded.
 */
internal data class ProbeOutcome(
    val winningNetwork: Network?,
    val winningLabel: String?,
    val failures: List<Pair<String, String>>,
    val resolvedIp: String,
    val totalMs: Long,
)

/**
 * Race a TCP handshake to (host, port) across every available transport in parallel. The
 * sequential predecessor (5s timeout × 4 networks = up to 20s) ate most of the user's
 * 30s budget on misconfigured devices; the parallel version takes ~2.5s in the worst case
 * and immediately surfaces the right network for the JSch handshake to follow.
 */
internal suspend fun probeReachability(context: Context, host: String, port: Int): ProbeOutcome {
    val probeStart = System.currentTimeMillis()
    val resolvedIp = resolveToIPv4(host) ?: host
    val attempts = enumerateCandidateNetworks(context)
    // Race all candidates in parallel; awaitAll caps total time at one probe-timeout.
    val results = withContext(Dispatchers.IO) {
        coroutineScope {
            attempts.map { (label, candidate) ->
                async {
                    val s = java.net.Socket()
                    try {
                        if (candidate != null) {
                            try { candidate.bindSocket(s) } catch (t: Throwable) {
                                Log.w(TAG_SSH, "bindSocket to $label failed", t)
                            }
                        }
                        s.connect(java.net.InetSocketAddress(resolvedIp, port), PROBE_PER_NETWORK_TIMEOUT_MS)
                        Triple(label, candidate, null as String?)
                    } catch (e: Throwable) {
                        Triple(label, candidate, "${e::class.java.simpleName}: ${e.message ?: "unknown"}")
                    } finally {
                        try { s.close() } catch (_: Throwable) {}
                    }
                }
            }.awaitAll()
        }
    }
    val winner = results.firstOrNull { it.third == null }
    val failures = results.filter { it.third != null }.map { it.first to (it.third ?: "unknown") }
    val totalMs = System.currentTimeMillis() - probeStart
    if (winner != null) {
        Log.i(TAG_SSH, "tcp probe ok via ${winner.first} in ${totalMs}ms")
        return ProbeOutcome(winner.second, winner.first, failures, resolvedIp, totalMs)
    }
    return ProbeOutcome(null, null, failures, resolvedIp, totalMs)
}

/**
 * Standard envelope for "we couldn't reach the host on any transport". Surfaces the per-
 * network failure reasons so the LLM can quote them back to the user when explaining what
 * went wrong, plus the recovery hint for the most common Android routing pitfalls.
 */
internal fun unreachableEnvelope(host: String, port: Int, outcome: ProbeOutcome): JsonObject =
    buildJsonObject {
        put("error", "tcp_unreachable")
        put("host", host)
        put("ip", outcome.resolvedIp)
        put("port", port)
        put("attempts", buildJsonObject {
            outcome.failures.forEach { (label, reason) -> put(label, reason) }
        })
        put("recovery", "Direct TCP to ${outcome.resolvedIp}:$port failed across every available " +
            "network (${outcome.totalMs}ms total). If Termux ssh from the same device reaches " +
            "this host, RikkaHub's process is being filtered. Check Settings → Network → " +
            "Private DNS (try Off), any active VPN's per-app routing, and Settings → Apps → " +
            "RikkaHub → Mobile data & Wi-Fi (enable Background data and Unrestricted data usage).")
    }

/** Run a single command on an open session. Returns a JSON object with exit_code/stdout/stderr. */
internal fun runOnSession(session: Session, command: String, timeoutMs: Int): JsonObject {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val channel = session.openChannel("exec") as ChannelExec
    var hitDeadline = false
    try {
        channel.setCommand(command)
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        channel.connect(timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!channel.isClosed) {
            if (System.currentTimeMillis() >= deadline) { hitDeadline = true; break }
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                // Coroutine cancellation propagated through runInterruptible. Bail out.
                Thread.currentThread().interrupt()
                break
            }
        }
        if (hitDeadline) {
            // Command outlasted its timeout. Channel.exitStatus would return -1 here, which
            // earlier versions reported as exit_code=-1 + success=false — indistinguishable
            // from a real exit code -1. Make the timeout explicit so the model can choose
            // to bump timeout_seconds rather than retrying with a different command.
            return buildJsonObject {
                put("error", "command_timeout")
                put("recovery", "Command did not complete within ${timeoutMs / 1000}s. Bump " +
                    "timeout_seconds, or detach the command (e.g. nohup ... & disown) so it " +
                    "runs in the background after your ssh exec returns. Partial stdout/stderr " +
                    "captured before the timeout is included.")
                put("partial_stdout", cap(stdout.toString(Charsets.UTF_8), MAX_RETURNED_STDOUT))
                put("partial_stderr", cap(stderr.toString(Charsets.UTF_8), MAX_RETURNED_STDERR))
            }
        }
        val exitCode = channel.exitStatus
        return buildJsonObject {
            put("success", exitCode == 0)
            put("exit_code", exitCode)
            put("stdout", cap(stdout.toString(Charsets.UTF_8), MAX_RETURNED_STDOUT))
            put("stderr", cap(stderr.toString(Charsets.UTF_8), MAX_RETURNED_STDERR))
        }
    } finally {
        try { channel.disconnect() } catch (_: Throwable) {}
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
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            execOneShot(context, host, port, user, auth, command, timeoutSec * 1000, sessionRef)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * Wrap a suspend SSH operation in coroutine-friendly cancellation. JSch's blocking JNI
 * socket reads do NOT honour Thread.interrupt() reliably, so withTimeoutOrNull alone
 * leaves the operation running on the IO thread until the OS TCP timeout (~75s on most
 * kernels) even though we've already returned "timeout" to the model.
 *
 * Strategy: the inner [block] stashes its live Session in [sessionRef] as soon as it's
 * open. If the outer withTimeoutOrNull cancels (or anything else throws), we forcibly
 * disconnect that Session in finally — closing the underlying socket unblocks JSch's JNI
 * read on the IO thread.
 */
internal suspend fun runCancellableSshOp(
    timeoutMs: Long,
    block: suspend (sessionRef: AtomicReference<Session?>) -> JsonObject,
): JsonObject {
    val sessionRef = AtomicReference<Session?>(null)
    return try {
        withTimeoutOrNull(timeoutMs) { block(sessionRef) }
            ?: buildJsonObject { put("error", "timeout") }
    } finally {
        // Forcibly disconnect the session if it's still open. Safe to call on an already-
        // disconnected session — JSch's Session.disconnect is idempotent.
        sessionRef.getAndSet(null)?.let { s ->
            try { s.disconnect() } catch (_: Throwable) {}
        }
    }
}

/**
 * Probe → connect → run → disconnect. The probe is suspend (parallel async); the JSch
 * handshake + exec are blocking, so we hand them off to runInterruptible(IO) which gives
 * us best-effort thread interrupt on coroutine cancellation. The Session is stashed in
 * [sessionRef] so the outer [runCancellableSshOp] can also forcibly disconnect from
 * outside if interrupt isn't honoured by JNI.
 */
internal suspend fun execOneShot(
    context: Context,
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    command: String,
    timeoutMs: Int,
    sessionRef: AtomicReference<Session?>,
): JsonObject {
    // Stage 1 (suspend): low-level reachability probe in parallel across every transport.
    // JSch's connect timeout fires at the END of the SSH handshake, so when the network is
    // silently broken the LLM sees a 30s "timeout" with no clue why. Probing with a raw
    // java.net.Socket first lets us tell the model exactly which layer is failing, AND
    // pick the working network for JSch to bind to.
    val outcome = probeReachability(context, host, port)
    if (outcome.winningNetwork == null && outcome.failures.isNotEmpty()) {
        return unreachableEnvelope(host, port, outcome)
    }

    // Stage 2 (blocking IO, interruptible): JSch handshake + exec.
    return runInterruptible(Dispatchers.IO) {
        val jsch = newJSch(context)
        val handshakeStart = System.currentTimeMillis()
        val session = try {
            openSshSession(jsch, host, port, user, auth, timeoutMs, network = outcome.winningNetwork)
        } catch (e: Throwable) {
            Log.w(TAG_SSH, "ssh handshake failed in ${System.currentTimeMillis() - handshakeStart}ms", e)
            return@runInterruptible wrapConnectError(host, e)
        }
        sessionRef.set(session)
        Log.i(TAG_SSH, "ssh session up via ${outcome.winningLabel ?: "default"} in ${System.currentTimeMillis() - handshakeStart}ms")
        try {
            runOnSession(session, command, timeoutMs)
        } catch (e: Throwable) {
            buildJsonObject { put("error", "exec failed: ${e.message ?: "unknown"}") }
        } finally {
            sessionRef.set(null)
            try { session.disconnect() } catch (_: Throwable) {}
        }
    }
}

/**
 * Translate JSch connection failures into structured envelopes the LLM can act on. Three
 * distinct branches:
 *   - host_key_changed: server identity differs from known_hosts. Hint to call
 *     ssh_forget_host_key only after explicit user confirmation.
 *   - auth_failed: credentials rejected. Hint to verify with user before retrying so the
 *     model doesn't burn round-trips brute-forcing the same wrong password.
 *   - generic connect_failed: anything else.
 */
internal fun wrapConnectError(host: String, e: Throwable): JsonObject {
    val msg = e.message.orEmpty()
    val isHostKeyChange = msg.contains("HostKey", ignoreCase = true) ||
        msg.contains("host key", ignoreCase = true) ||
        msg.contains("identification has changed", ignoreCase = true) ||
        msg.contains("REMOTE HOST IDENTIFICATION", ignoreCase = true)
    if (isHostKeyChange) {
        return buildJsonObject {
            put("error", "host_key_changed")
            put("host", host)
            put("recovery", "Stored key for $host doesn't match what the server presented. " +
                "If the user trusts this host (e.g. they just reinstalled it), call " +
                "ssh_forget_host_key with host=\"$host\" then retry. Do NOT forget the key " +
                "without explicit user confirmation — a changed key can also indicate an attacker.")
            put("raw", msg)
        }
    }
    val isAuthFailure = msg.contains("Auth fail", ignoreCase = true) ||
        msg.contains("auth cancel", ignoreCase = true) ||
        msg.contains("USERAUTH fail", ignoreCase = true) ||
        msg.contains("Authentication failed", ignoreCase = true) ||
        msg.contains("Permission denied (publickey", ignoreCase = true) ||
        msg.contains("Permission denied (password", ignoreCase = true)
    if (isAuthFailure) {
        return buildJsonObject {
            put("error", "auth_failed")
            put("host", host)
            put("recovery", "Credentials rejected by $host. Verify the password / private_key / " +
                "username with the user before retrying — DO NOT keep guessing or you'll lock " +
                "the account out. If using a private_key, double-check the user passed the " +
                "FULL PEM contents (including the BEGIN/END markers).")
            put("raw", msg)
        }
    }
    return buildJsonObject {
        put("error", "connect_failed")
        put("host", host)
        put("reason", msg.ifBlank { e::class.simpleName ?: "unknown" })
    }
}

/**
 * Remove all stored host keys for [host] from the persistent known_hosts file. Use after the
 * user confirms they reinstalled the remote — the next connect will trust the new key per
 * the accept-new policy.
 *
 * IMPORTANT: JSch's [com.jcraft.jsch.HostKeyRepository.remove] only updates the in-memory
 * KnownHosts; it does not write back to the file. JSch's KnownHosts.dump(OutputStream)
 * exists but is package-private, so we manually re-serialise the in-memory list to the
 * known_hosts(5) format. Without this, the next newJSch() reloads from the unchanged file
 * and the "host key changed" error returns immediately — i.e. ssh_forget_host_key silently
 * no-ops.
 */
internal fun forgetHostKey(context: Context, host: String): Int {
    val jsch = newJSch(context)
    val repo = jsch.hostKeyRepository
    val before = repo.hostKey?.count { it.host == host } ?: 0
    if (before == 0) return 0
    repo.remove(host, null)
    try {
        knownHostsFile(context).bufferedWriter().use { w ->
            repo.hostKey?.forEach { hk ->
                val marker = hk.marker?.takeIf { it.isNotEmpty() }
                val line = buildString {
                    if (marker != null) append('@').append(marker).append(' ')
                    append(hk.host).append(' ')
                    append(hk.type).append(' ')
                    append(hk.key)
                }
                w.write(line)
                w.newLine()
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG_SSH, "forgetHostKey: failed to persist known_hosts after remove", e)
    }
    return before
}
