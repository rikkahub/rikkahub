package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID

private const val TERMUX_PACKAGE = "com.termux"
private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"
private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
private const val RUN_PATH = "com.termux.RUN_COMMAND_PATH"
private const val RUN_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
private const val RUN_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
private const val RUN_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
private const val RUN_RESULT = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val RESULT_BUNDLE = "result"
private const val RESULT_STDOUT = "stdout"
private const val RESULT_STDERR = "stderr"
private const val RESULT_EXIT = "exitCode"
private const val RESULT_ERROR = "err"
private const val RESULT_MESSAGE = "errmsg"
private const val DEFAULT_TIMEOUT_MS = 60_000L
private const val MAX_TIMEOUT_SECONDS = 600
private const val DEFAULT_ROWS = 32
private const val DEFAULT_COLS = 120
private const val MAX_OUTPUT_CHARS = 32_000

private fun termuxResult(json: kotlinx.serialization.json.JsonObject) =
    listOf(UIMessagePart.Text(json.toString()))

private fun termuxError(code: String, detail: String) = termuxResult(buildJsonObject {
    put("error", code)
    put("detail", detail)
})

private fun termuxPreflight(context: Context): String? = runCatching {
    context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
    if (androidx.core.content.ContextCompat.checkSelfPermission(
            context, "com.termux.permission.RUN_COMMAND"
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) "Termux RUN_COMMAND permission is not granted." else null
}.getOrElse {
    "Termux is not installed or is hidden by Android package visibility."
}

private suspend fun termuxRun(
    context: Context,
    executable: String,
    arguments: Array<String>,
    workdir: String = TERMUX_HOME,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
): TermuxCapture {
    val deferred = CompletableDeferred<Bundle>()
    val action = "${context.packageName}.TERMUX_RESULT_${UUID.randomUUID()}"
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            val result = intent.getBundleExtra(RESULT_BUNDLE) ?: intent.extras ?: return
            if (deferred.isActive) deferred.complete(result)
        }
    }
    val filter = IntentFilter(action)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, filter)
    }
    val pendingIntent = runCatching {
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }.getOrElse {
        runCatching { context.unregisterReceiver(receiver) }
        return TermuxCapture.Error(it.message ?: "Unable to create result callback.")
    }
    val command = Intent().apply {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        this.action = RUN_COMMAND_ACTION
        putExtra(RUN_PATH, executable)
        putExtra(RUN_ARGS, arguments)
        putExtra(RUN_WORKDIR, workdir)
        putExtra(RUN_BACKGROUND, true)
        putExtra(RUN_RESULT, pendingIntent)
    }
    return try {
        context.startService(command)
        val bundle = withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 600_000L)) { deferred.await() }
            ?: return TermuxCapture.Timeout
        val errorCode = bundle.getInt(RESULT_ERROR, -1)
        if (errorCode != -1) {
            TermuxCapture.Error(bundle.getString(RESULT_MESSAGE).orEmpty().ifBlank { "Termux rejected the command ($errorCode)." })
        } else {
            TermuxCapture.Success(
                stdout = bundle.getString(RESULT_STDOUT).orEmpty(),
                stderr = bundle.getString(RESULT_STDERR).orEmpty(),
                exitCode = bundle.getInt(RESULT_EXIT, -1),
            )
        }
    } catch (error: SecurityException) {
        TermuxCapture.Error("Termux denied RUN_COMMAND: ${error.message.orEmpty()}")
    } catch (error: Throwable) {
        TermuxCapture.Error(error.message ?: error::class.simpleName.orEmpty())
    } finally {
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { pendingIntent.cancel() }
    }
}

private sealed class TermuxCapture {
    data class Success(val stdout: String, val stderr: String, val exitCode: Int) : TermuxCapture()
    data object Timeout : TermuxCapture()
    data class Error(val message: String) : TermuxCapture()
}

private fun captureJson(capture: TermuxCapture, command: String? = null) = when (capture) {
    is TermuxCapture.Success -> termuxResult(buildJsonObject {
        put("success", capture.exitCode == 0)
        put("exit_code", capture.exitCode)
        put("stdout", capture.stdout.takeLast(MAX_OUTPUT_CHARS))
        put("stderr", capture.stderr.takeLast(MAX_OUTPUT_CHARS))
        command?.let { put("command", it) }
    })
    TermuxCapture.Timeout -> termuxError("timeout", "Termux command timed out.")
    is TermuxCapture.Error -> termuxError("termux_error", capture.message)
}

fun termuxRunCommandTool(context: Context): Tool = Tool(
    name = "termux_run_command",
    description = "Execute a shell command in the installed Termux app and return stdout, stderr, and exit_code. Requires Termux allow-external-apps=true and user approval.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("command", buildJsonObject { put("type", "string") })
        put("working_dir", buildJsonObject { put("type", "string") })
        put("timeout_seconds", buildJsonObject { put("type", "integer") })
    }, required = listOf("command")) },
    needsApproval = { true },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val obj = input.jsonObject
        val command = obj["command"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@Tool termuxError("missing_command", "command is required.")
        val timeout = (obj["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60)
            .coerceIn(1, MAX_TIMEOUT_SECONDS) * 1_000L
        captureJson(termuxRun(context, TERMUX_BASH, arrayOf("-lc", command), obj["working_dir"]?.jsonPrimitive?.contentOrNull ?: TERMUX_HOME, timeout), command)
    },
)

private fun sessionName(raw: String?): String {
    val safe = raw.orEmpty().replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-')
    return "rikkahub-${safe.ifBlank { UUID.randomUUID().toString().take(8) }}"
}

private fun sessionIdSchema(properties: kotlinx.serialization.json.JsonObjectBuilder) {
    properties.put("session_id", buildJsonObject { put("type", "string") })
}

fun termuxSessionStartTool(context: Context): Tool = Tool(
    name = "termux_session_start",
    description = "Start a persistent tmux-backed interactive Termux session. Use termux_session_send/read to control it.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("name", buildJsonObject { put("type", "string") })
        put("command", buildJsonObject { put("type", "string") })
        put("cols", buildJsonObject { put("type", "integer") })
        put("rows", buildJsonObject { put("type", "integer") })
    }) },
    needsApproval = { true },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val obj = input.jsonObject
        val name = sessionName(obj["name"]?.jsonPrimitive?.contentOrNull)
        val cols = (obj["cols"]?.jsonPrimitive?.intOrNull ?: DEFAULT_COLS).coerceIn(40, 240)
        val rows = (obj["rows"]?.jsonPrimitive?.intOrNull ?: DEFAULT_ROWS).coerceIn(8, 120)
        val command = "tmux new-session -d -s ${shellQuote(name)} -x $cols -y $rows"
        val started = termuxRun(context, TERMUX_BASH, arrayOf("-lc", command))
        if (started !is TermuxCapture.Success || started.exitCode != 0) {
            return@Tool captureJson(started, command)
        }
        val initial = obj["command"]?.jsonPrimitive?.contentOrNull
        if (!initial.isNullOrBlank()) {
            termuxRun(context, TERMUX_BASH, arrayOf("-lc", "tmux send-keys -t ${shellQuote(name)} ${shellQuote(initial)} Enter"))
        }
        val screen = termuxRun(context, TERMUX_BASH, arrayOf("-lc", "tmux capture-pane -p -t ${shellQuote(name)} -S -200"))
        when (screen) {
            is TermuxCapture.Success -> termuxResult(buildJsonObject { put("success", true); put("session_id", name); put("screen", screen.stdout.takeLast(MAX_OUTPUT_CHARS)) })
            else -> termuxResult(buildJsonObject { put("success", true); put("session_id", name); put("screen", "") })
        }
    },
)

fun termuxSessionSendTool(context: Context): Tool = Tool(
    name = "termux_session_send",
    description = "Send text and control keys to a persistent Termux tmux session and return its screen.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        sessionIdSchema(this)
        put("input", buildJsonObject { put("type", "string") })
        put("enter", buildJsonObject { put("type", "boolean") })
        put("keys", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
    }) },
    needsApproval = { true },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val obj = input.jsonObject
        val session = obj["session_id"]?.jsonPrimitive?.contentOrNull ?: return@Tool termuxError("missing_session_id", "session_id is required.")
        val text = obj["input"]?.jsonPrimitive?.contentOrNull
        val keys = obj["keys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val sends = buildList {
            text?.takeIf { it.isNotEmpty() }?.let { add("tmux send-keys -t ${shellQuote(session)} -l ${shellQuote(it)}") }
            keys.forEach { add("tmux send-keys -t ${shellQuote(session)} ${tmuxKey(it)}") }
            if (obj["enter"]?.jsonPrimitive?.booleanOrNull ?: true) add("tmux send-keys -t ${shellQuote(session)} Enter")
        }
        if (sends.isEmpty()) return@Tool termuxSessionReadTool(context).execute(input)
        val sent = termuxRun(context, TERMUX_BASH, arrayOf("-lc", sends.joinToString(" && ")))
        if (sent !is TermuxCapture.Success || sent.exitCode != 0) return@Tool captureJson(sent)
        val screen = termuxRun(context, TERMUX_BASH, arrayOf("-lc", "tmux capture-pane -p -t ${shellQuote(session)} -S -200"))
        captureJson(screen)
    },
)

fun termuxSessionReadTool(context: Context): Tool = Tool(
    name = "termux_session_read",
    description = "Read the current screen of a persistent Termux tmux session.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { sessionIdSchema(this) }) },
    needsApproval = { false },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull ?: return@Tool termuxError("missing_session_id", "session_id is required.")
        captureJson(termuxRun(context, TERMUX_BASH, arrayOf("-lc", "tmux capture-pane -p -t ${shellQuote(session)} -S -200")))
    },
)

fun termuxSessionKillTool(context: Context): Tool = Tool(
    name = "termux_session_kill",
    description = "Terminate a persistent Termux tmux session.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { sessionIdSchema(this) }) },
    needsApproval = { true },
    execute = { input ->
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull ?: return@Tool termuxError("missing_session_id", "session_id is required.")
        captureJson(termuxRun(context, TERMUX_BASH, arrayOf("-lc", "tmux kill-session -t ${shellQuote(session)}")))
    },
)

fun termuxSessionListTool(context: Context): Tool = Tool(
    name = "termux_session_list",
    description = "List persistent Termux tmux sessions.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    needsApproval = { false },
    execute = {
        termuxPreflight(context)?.let { return@Tool termuxError("unavailable", it) }
        val result = termuxRun(context, TERMUX_BASH, arrayOf("-lc", "tmux list-sessions -F '#{session_name}|#{session_created}|#{session_activity}'"))
        when (result) {
            is TermuxCapture.Success -> termuxResult(buildJsonObject {
                put("sessions", buildJsonArray {
                    result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val parts = line.split('|')
                        add(buildJsonObject { put("session_id", parts.firstOrNull().orEmpty()); put("created", parts.getOrNull(1).orEmpty()); put("last_activity", parts.getOrNull(2).orEmpty()) })
                    }
                })
            })
            else -> captureJson(result)
        }
    },
)

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

private fun tmuxKey(value: String): String = when (value.trim().lowercase()) {
    "enter", "return" -> "Enter"
    "tab" -> "Tab"
    "esc", "escape" -> "Escape"
    "up" -> "Up"
    "down" -> "Down"
    "left" -> "Left"
    "right" -> "Right"
    "c-c" -> "C-c"
    "c-d" -> "C-d"
    "c-z" -> "C-z"
    else -> value
}
