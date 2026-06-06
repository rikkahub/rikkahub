package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.util.UUID

internal const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
internal const val TERMUX_HOME = "/data/data/com.termux/files/home"

private const val DEFAULT_COLS = 200
private const val DEFAULT_ROWS = 50
private const val DEFAULT_READ_LINES = 200
private const val DEFAULT_TIMEOUT_S = 20
private const val MAX_TIMEOUT_S = 600
private const val SETTLE_MS = 600L
private const val POLL_INTERVAL_MS = 200L
private const val MAX_SESSIONS = 8
private const val TMUX_OP_TIMEOUT_MS = 8_000L
private const val INSTALL_TIMEOUT_MS = 180_000L

/** Builds the argv passed to the tmux executable for each session operation. Pure. */
internal object TmuxOps {
    fun sessionName(userName: String?): String {
        val suffix = userName?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9_]"), "_")
            ?.take(24)
        val id = UUID.randomUUID().toString().take(8)
        return if (suffix.isNullOrBlank()) "rk_$id" else "rk_${suffix}_$id"
    }

    fun startArgv(session: String, cols: Int, rows: Int): Array<String> =
        arrayOf("new-session", "-d", "-s", session, "-x", cols.toString(), "-y", rows.toString())

    // -l sends the text literally (no tmux key-name interpretation); -- ends option parsing.
    fun sendTextArgv(session: String, text: String): Array<String> =
        arrayOf("send-keys", "-t", session, "-l", "--", text)

    // Each element is a tmux key name (e.g. "C-c", "Enter", "Up", "Tab").
    fun sendKeysArgv(session: String, keys: List<String>): Array<String> =
        (listOf("send-keys", "-t", session) + keys).toTypedArray()

    fun enterArgv(session: String): Array<String> =
        arrayOf("send-keys", "-t", session, "Enter")

    fun capturePaneArgv(session: String, lines: Int): Array<String> =
        arrayOf("capture-pane", "-t", session, "-p", "-S", "-${lines.coerceAtLeast(0)}")

    fun killArgv(session: String): Array<String> =
        arrayOf("kill-session", "-t", session)

    fun listArgv(): Array<String> =
        arrayOf("list-sessions", "-F", "#{session_name}\t#{session_created}\t#{session_activity}")
}

internal data class PaneSample(val elapsedMs: Long, val content: String)

internal sealed interface PollResult {
    data object Continue : PollResult
    data class Done(val reason: Reason, val content: String) : PollResult
    enum class Reason { SETTLED, MATCHED, TIMEOUT }
}

/** Regex match with substring fallback when the pattern is not a valid regex. */
internal fun waitForMatches(pane: String, pattern: String): Boolean {
    if (pattern.isEmpty()) return false
    val rx = runCatching { Regex(pattern) }.getOrNull()
    return if (rx != null) rx.containsMatchIn(pane) else pane.contains(pattern)
}

/**
 * Decide whether the polling loop should stop, given every pane snapshot taken so far
 * (chronological, each with its elapsed time since the send). Order of precedence:
 * wait_for match, then settle (screen unchanged for >= settleMs), then timeout.
 */
internal fun evaluatePoll(
    samples: List<PaneSample>,
    settleMs: Long,
    timeoutMs: Long,
    waitFor: String?,
): PollResult {
    val cur = samples.lastOrNull() ?: return PollResult.Continue
    if (!waitFor.isNullOrEmpty() && waitForMatches(cur.content, waitFor)) {
        return PollResult.Done(PollResult.Reason.MATCHED, cur.content)
    }
    var stableSince = cur.elapsedMs
    for (i in samples.indices.reversed()) {
        if (samples[i].content == cur.content) stableSince = samples[i].elapsedMs else break
    }
    if (samples.size >= 2 && cur.elapsedMs - stableSince >= settleMs) {
        return PollResult.Done(PollResult.Reason.SETTLED, cur.content)
    }
    if (cur.elapsedMs >= timeoutMs) {
        return PollResult.Done(PollResult.Reason.TIMEOUT, cur.content)
    }
    return PollResult.Continue
}

internal data class TmuxSessionInfo(val name: String, val created: Long, val lastActivity: Long)

internal fun parseSessions(stdout: String, prefix: String = "rk_"): List<TmuxSessionInfo> =
    stdout.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || !parts[0].startsWith(prefix)) return@mapNotNull null
            TmuxSessionInfo(
                name = parts[0],
                created = parts[1].toLongOrNull() ?: 0L,
                lastActivity = parts[2].toLongOrNull() ?: 0L,
            )
        }.toList()

internal fun isSessionNotFound(stderr: String): Boolean {
    val s = stderr.lowercase()
    return s.contains("can't find session") ||
        s.contains("no server running") ||
        s.contains("session not found") ||
        s.contains("no current session")
}

private suspend fun tmux(context: Context, argv: Array<String>, timeoutMs: Long = TMUX_OP_TIMEOUT_MS): CaptureResult =
    runCommandCapture(context, "$TERMUX_BIN/tmux", argv, TERMUX_HOME, timeoutMs)

/** Ensure tmux is installed; auto-install on first use. Returns null on success, an error string otherwise. */
private suspend fun ensureTmux(context: Context): String? {
    val check = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    if (check is CaptureResult.Success && check.stdout.isNotBlank()) return null
    val install = runCommandCapture(context, "$TERMUX_BIN/bash", arrayOf("-c", "pkg install -y tmux"), TERMUX_HOME, INSTALL_TIMEOUT_MS)
    if (install is CaptureResult.Denied) return "termux_permission_denied"
    val recheck = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    return if (recheck is CaptureResult.Success && recheck.stdout.isNotBlank()) null else "tmux_install_failed"
}

private fun resolveTimeoutMs(input: JsonElement): Long {
    val raw = input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull
    val secs = when {
        raw == null || raw == 0 -> DEFAULT_TIMEOUT_S
        else -> raw.coerceIn(1, MAX_TIMEOUT_S)
    }
    return secs.toLong() * 1000
}

private fun truncateOut(s: String): String {
    // capture-pane emits the full terminal height, so the screen arrives padded with a wall
    // of blank lines below the cursor. Drop trailing blank lines so each read does not burn
    // tokens on empty padding.
    val trimmed = s.trimEnd('\n', ' ', '\t')
    val max = TermuxRuntime.maxStdoutBytes
    return if (trimmed.length > max) trimmed.takeLast(max) + "\n…[older scrollback truncated]" else trimmed
}

private fun reasonTag(r: PollResult.Reason): String = when (r) {
    PollResult.Reason.MATCHED -> "MATCHED"
    PollResult.Reason.SETTLED -> "SETTLED"
    PollResult.Reason.TIMEOUT -> "TIMEOUT"
}

/**
 * Poll capture-pane until settled / matched / timed out. Encodes the outcome in a
 * [CaptureResult.Success] where stdout is the screen and stderr carries the reason tag
 * (MATCHED / SETTLED / TIMEOUT). A capture failure (e.g. session gone) is returned as-is.
 */
private suspend fun readUntilDone(
    context: Context,
    session: String,
    lines: Int,
    waitFor: String?,
    timeoutMs: Long,
): CaptureResult {
    val start = android.os.SystemClock.elapsedRealtime()
    val samples = ArrayList<PaneSample>()
    while (true) {
        val cap = tmux(context, TmuxOps.capturePaneArgv(session, lines))
        if (cap is CaptureResult.Success) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            samples.add(PaneSample(elapsed, cap.stdout))
            when (val d = evaluatePoll(samples, SETTLE_MS, timeoutMs, waitFor)) {
                is PollResult.Done -> return CaptureResult.Success(d.content, reasonTag(d.reason), 0)
                PollResult.Continue -> {}
            }
        } else {
            return cap
        }
        if (android.os.SystemClock.elapsedRealtime() - start >= timeoutMs) {
            return CaptureResult.Success(samples.lastOrNull()?.content.orEmpty(), "TIMEOUT", 0)
        }
        delay(POLL_INTERVAL_MS)
    }
}

private fun sessionErrorEnvelope(error: String, recovery: String) = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("error", error); put("recovery", recovery)
    }.toString())
)

private fun preflight(context: Context): List<UIMessagePart>? =
    when (TermuxIntegration.state(context)) {
        TermuxIntegration.State.NOT_INSTALLED -> sessionErrorEnvelope(
            "termux_not_installed",
            "Install Termux from https://github.com/termux/termux-app/releases ."
        )
        TermuxIntegration.State.NO_PERMISSION -> sessionErrorEnvelope(
            "termux_permission_not_granted",
            "Toggle Termux on in Assistant -> Local tools, or run: adb shell pm grant ${context.packageName} com.termux.permission.RUN_COMMAND"
        )
        TermuxIntegration.State.READY -> null
    }

private suspend fun sessionNotFoundEnvelope(context: Context, session: String): List<UIMessagePart> {
    val live = (tmux(context, TmuxOps.listArgv()) as? CaptureResult.Success)?.let { parseSessions(it.stdout) } ?: emptyList()
    return sessionErrorEnvelope(
        "session_not_found",
        "Session '$session' is gone (killed or device rebooted). Live sessions: ${live.joinToString { it.name }.ifEmpty { "none" }}. Start a new one with termux_session_start."
    )
}

fun termuxSessionStartTool(context: Context): Tool = Tool(
    name = "termux_session_start",
    description = "Open a persistent, interactive Termux terminal session (tmux-backed, real pty). Use for ssh into a saved host, anything that prompts for a password/sudo, REPLs, or stateful shells. Returns a session_id; drive it with termux_session_send / termux_session_read. Auto-installs tmux on first use.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Optional friendly label for the session.") })
            put("command", buildJsonObject { put("type", "string"); put("description", "Optional initial command line to run, e.g. 'ssh myhost'.") })
            put("cols", buildJsonObject { put("type", "integer"); put("description", "Terminal width (default $DEFAULT_COLS).") })
            put("rows", buildJsonObject { put("type", "integer"); put("description", "Terminal height (default $DEFAULT_ROWS).") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        ensureTmux(context)?.let {
            return@Tool sessionErrorEnvelope(it, "tmux could not be installed. Open Termux, run 'pkg install tmux', and retry.")
        }
        val live = (tmux(context, TmuxOps.listArgv()) as? CaptureResult.Success)?.let { parseSessions(it.stdout) } ?: emptyList()
        if (live.size >= MAX_SESSIONS) {
            return@Tool sessionErrorEnvelope("too_many_sessions", "Max $MAX_SESSIONS sessions. Kill one with termux_session_kill first. Live: ${live.joinToString { it.name }}")
        }
        val name = TmuxOps.sessionName(input.jsonObject["name"]?.jsonPrimitive?.contentOrNull)
        val cols = input.jsonObject["cols"]?.jsonPrimitive?.intOrNull ?: DEFAULT_COLS
        val rows = input.jsonObject["rows"]?.jsonPrimitive?.intOrNull ?: DEFAULT_ROWS
        val started = tmux(context, TmuxOps.startArgv(name, cols, rows))
        if (started !is CaptureResult.Success) {
            return@Tool sessionErrorEnvelope("session_start_failed", "tmux new-session failed.")
        }
        val initial = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (!initial.isNullOrBlank()) {
            HardlineCommandGuard.checkCommand(initial)?.let {
                return@Tool sessionErrorEnvelope("blocked_by_safety_floor", it)
            }
            tmux(context, TmuxOps.sendTextArgv(name, initial))
            tmux(context, TmuxOps.enterArgv(name))
        }
        val read = readUntilDone(context, name, DEFAULT_READ_LINES, null, DEFAULT_TIMEOUT_S * 1000L) as CaptureResult.Success
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true); put("session_id", name); put("screen", truncateOut(read.stdout))
        }.toString()))
    }
)

fun termuxSessionSendTool(context: Context): Tool = Tool(
    name = "termux_session_send",
    description = "Type input into a session and read what comes back. Set enter=false to type without a newline (e.g. answering a prompt). Use keys for control keys (tmux names: 'C-c', 'Enter', 'Up', 'Tab'). Pass wait_for (substring/regex) to return as soon as expected text appears (e.g. 'password:'). Returns the screen, matched_wait_for, timed_out.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string"); put("description", "Session id from termux_session_start.") })
            put("input", buildJsonObject { put("type", "string"); put("description", "Text to type. Optional if keys is given.") })
            put("enter", buildJsonObject { put("type", "boolean"); put("description", "Press Enter after input. Default true.") })
            put("keys", buildJsonObject { put("type", "array"); put("description", "tmux key names to send (e.g. ['C-c']).") ; put("items", buildJsonObject { put("type", "string") }) })
            put("wait_for", buildJsonObject { put("type", "string"); put("description", "Return as soon as this substring/regex appears on screen.") })
            put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Default $DEFAULT_TIMEOUT_S, max $MAX_TIMEOUT_S.") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool sessionErrorEnvelope("missing_session_id", "Pass session_id from termux_session_start.")
        val text = input.jsonObject["input"]?.jsonPrimitive?.contentOrNull
        val enter = input.jsonObject["enter"]?.jsonPrimitive?.booleanOrNull ?: true
        val keys = input.jsonObject["keys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val waitFor = input.jsonObject["wait_for"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = resolveTimeoutMs(input)
        if (!text.isNullOrEmpty()) {
            HardlineCommandGuard.checkCommand(text)?.let {
                return@Tool sessionErrorEnvelope("blocked_by_safety_floor", it)
            }
            val sent = tmux(context, TmuxOps.sendTextArgv(session, text))
            if (sent is CaptureResult.OtherError && isSessionNotFound(sent.message)) {
                return@Tool sessionNotFoundEnvelope(context, session)
            }
        }
        if (keys.isNotEmpty()) tmux(context, TmuxOps.sendKeysArgv(session, keys))
        if (enter) tmux(context, TmuxOps.enterArgv(session))
        val read = readUntilDone(context, session, DEFAULT_READ_LINES, waitFor, timeoutMs)
        if (read is CaptureResult.OtherError && isSessionNotFound(read.message)) {
            return@Tool sessionNotFoundEnvelope(context, session)
        }
        val r = read as CaptureResult.Success
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("screen", truncateOut(r.stdout))
            put("matched_wait_for", r.stderr == "MATCHED")
            put("timed_out", r.stderr == "TIMEOUT")
        }.toString()))
    }
)

fun termuxSessionReadTool(context: Context): Tool = Tool(
    name = "termux_session_read",
    description = "Re-read a session's screen without sending input (e.g. check on a long-running command). Optional wait_for + timeout_seconds to wait for expected text; otherwise returns the current screen immediately. lines sets scrollback depth (default $DEFAULT_READ_LINES).",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string"); put("description", "Session id.") })
            put("wait_for", buildJsonObject { put("type", "string"); put("description", "Optional substring/regex to wait for.") })
            put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Used only with wait_for. Default $DEFAULT_TIMEOUT_S.") })
            put("lines", buildJsonObject { put("type", "integer"); put("description", "Scrollback lines (default $DEFAULT_READ_LINES).") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool sessionErrorEnvelope("missing_session_id", "Pass session_id from termux_session_start.")
        val waitFor = input.jsonObject["wait_for"]?.jsonPrimitive?.contentOrNull
        val lines = input.jsonObject["lines"]?.jsonPrimitive?.intOrNull ?: DEFAULT_READ_LINES
        val read = if (waitFor.isNullOrEmpty()) {
            tmux(context, TmuxOps.capturePaneArgv(session, lines))
        } else {
            readUntilDone(context, session, lines, waitFor, resolveTimeoutMs(input))
        }
        if (read is CaptureResult.OtherError && isSessionNotFound(read.message)) {
            return@Tool sessionNotFoundEnvelope(context, session)
        }
        val r = read as? CaptureResult.Success
            ?: return@Tool sessionErrorEnvelope("read_failed", "Could not read session.")
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true); put("screen", truncateOut(r.stdout))
        }.toString()))
    }
)

fun termuxSessionKillTool(context: Context): Tool = Tool(
    name = "termux_session_kill",
    description = "End a Termux session opened by termux_session_start.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string"); put("description", "Session id to kill.") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool sessionErrorEnvelope("missing_session_id", "Pass session_id.")
        tmux(context, TmuxOps.killArgv(session))
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("killed", session) }.toString()))
    }
)

fun termuxSessionListTool(context: Context): Tool = Tool(
    name = "termux_session_list",
    description = "List live Termux sessions opened by the agent (id, name, last activity).",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { _ ->
        preflight(context)?.let { return@Tool it }
        val list = (tmux(context, TmuxOps.listArgv()) as? CaptureResult.Success)?.let { parseSessions(it.stdout) } ?: emptyList()
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("sessions", buildJsonArray {
                list.forEach { s ->
                    add(buildJsonObject {
                        put("session_id", s.name); put("created", s.created); put("last_activity", s.lastActivity)
                    })
                }
            })
        }.toString()))
    }
)
