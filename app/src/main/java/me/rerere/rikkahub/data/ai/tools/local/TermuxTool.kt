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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import java.util.UUID

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
private const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"
private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"

// Termux delivers stdout / stderr / exitCode via a result Bundle attached to the
// RUN_COMMAND_PENDING_INTENT we register. Documented at:
// https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val EXTRA_RESULT_BUNDLE = "result"
private const val RESULT_KEY_STDOUT = "stdout"
private const val RESULT_KEY_STDERR = "stderr"
private const val RESULT_KEY_EXIT_CODE = "exitCode"
private const val RESULT_KEY_ERR = "err"
private const val RESULT_KEY_ERRMSG = "errmsg"

private const val DEFAULT_CAPTURE_TIMEOUT_MS = 60_000L
private const val MAX_RETURNED_STDOUT = 8_000
private const val MAX_RETURNED_STDERR = 2_000

/**
 * Termux installation + integration probe used by both the LLM tool and the toggle row in
 * the assistant tools page.
 */
internal object TermuxIntegration {
    enum class State { NOT_INSTALLED, NO_PERMISSION, READY }

    /**
     * Process-scoped timestamp of the last successful end-to-end smoke test. The toggle row
     * in the assistant Local-tools page reads this so the green indicator persists across
     * navigations within the session — without it the dot would reset to orange every time
     * the user left and re-entered the page. Resets on app restart, which is acceptable
     * since re-verifying is one tap.
     */
    @Volatile
    var lastVerifiedOkAtMs: Long = 0L
        private set

    fun markVerifiedOk() {
        lastVerifiedOkAtMs = System.currentTimeMillis()
    }

    fun clearVerified() {
        lastVerifiedOkAtMs = 0L
    }

    fun state(ctx: Context): State {
        val pm = ctx.packageManager
        val installed = try {
            pm.getPackageInfo(TERMUX_PACKAGE, 0); true
        } catch (_: Throwable) { false }
        if (!installed) return State.NOT_INSTALLED
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, "com.termux.permission.RUN_COMMAND"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return State.NO_PERMISSION
        return State.READY
    }

    /**
     * Run a tiny `echo` smoke test through the Termux RUN_COMMAND service and wait for the
     * result bundle. Returns true iff the bundle came back with our marker on stdout, which
     * proves the entire chain works (manifest perm + runtime perm + allow-external-apps in
     * termux.properties + Termux is allowed to start a background session).
     */
    suspend fun verify(ctx: Context, timeoutMs: Long = 8_000L): VerifyResult {
        val s = state(ctx)
        if (s == State.NOT_INSTALLED) return VerifyResult.NotInstalled
        if (s == State.NO_PERMISSION) return VerifyResult.NoPermission
        val result = runCommandCapture(
            ctx = ctx,
            executable = "$TERMUX_BIN_DIR/bash",
            arguments = arrayOf("-c", "echo RIKKAHUB_OK"),
            workingDir = TERMUX_HOME_DIR,
            timeoutMs = timeoutMs,
        )
        return when (result) {
            is CaptureResult.Success -> if (result.stdout.contains("RIKKAHUB_OK"))
                VerifyResult.Ok else VerifyResult.UnexpectedOutput(result.stdout)
            is CaptureResult.Timeout -> VerifyResult.AllowExternalAppsMissing
            is CaptureResult.Denied -> VerifyResult.NoPermission
            is CaptureResult.OtherError -> VerifyResult.OtherError(result.message)
        }
    }

    sealed class VerifyResult {
        data object NotInstalled : VerifyResult()
        data object NoPermission : VerifyResult()
        data object AllowExternalAppsMissing : VerifyResult()
        data object Ok : VerifyResult()
        data class UnexpectedOutput(val stdout: String) : VerifyResult()
        data class OtherError(val message: String) : VerifyResult()
    }
}

internal sealed class CaptureResult {
    data class Success(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) : CaptureResult()
    data object Timeout : CaptureResult()
    data object Denied : CaptureResult()
    data class OtherError(val message: String) : CaptureResult()
}

/**
 * Dispatch a Termux command and suspend until it completes (or times out), returning the
 * captured output. Implementation registers a one-shot BroadcastReceiver, hands a
 * PendingIntent for it to Termux, and waits on a CompletableDeferred until Termux fires
 * the broadcast back. Always uses background mode internally because the result-bundle
 * delivery path only fires for background commands.
 */
internal suspend fun runCommandCapture(
    ctx: Context,
    executable: String,
    arguments: Array<String>,
    workingDir: String,
    timeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS,
): CaptureResult {
    // Mark Termux as freshly touched BEFORE we issue the broadcast. The notification
    // listener uses this signal to suppress Termux's foreground-service notification
    // updates (the "0 sessions, N tasks" pill) from auto-forwarding to Telegram while
    // the agent is actively running shell commands. Without this, every internal
    // runCommandCapture (whisper_status, transcribe_audio_file, etc.) makes Termux
    // flap its notification and the listener forwards each flap to the user's chat.
    // The touch here covers ALL callers of runCommandCapture, so individual tool
    // factories don't have to remember to call it themselves.
    me.rerere.rikkahub.data.ai.AgentTurnTracker.touchPackage(TERMUX_PACKAGE, "com.termux.api")
    val resultDeferred = CompletableDeferred<Bundle>()
    val resultAction = "${ctx.packageName}.TERMUX_RESULT_${UUID.randomUUID()}"
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // Termux's RunCommandService fires the PendingIntent *twice* in 0.118.x:
            // - once almost immediately as a "started" / acknowledgement broadcast with
            //   intent.extras == null
            // - again when the command actually completes, this time with a "result" Bundle
            //   containing stdout / stderr / exitCode.
            // FLAG_ONE_SHOT used to consume the first empty fire and the real one was
            // never delivered. Now we ignore empty broadcasts and only complete the
            // deferred when we see a usable payload.
            val keys = intent.extras?.keySet()?.joinToString(",")
            val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
            android.util.Log.i(
                "RikkaTermux",
                "broadcast: action=${intent.action} hasExtras=${intent.extras != null} extraKeys=[$keys] hasResultBundle=${bundle != null}",
            )
            if (bundle == null && intent.extras == null) return  // empty ack, wait for real fire
            if (bundle != null) {
                android.util.Log.i(
                    "RikkaTermux",
                    "result bundle keys=${bundle.keySet().joinToString(",")} stdout='${bundle.getString(RESULT_KEY_STDOUT, "<null>").take(200)}' stderr='${bundle.getString(RESULT_KEY_STDERR, "<null>").take(200)}' exit=${bundle.getInt(RESULT_KEY_EXIT_CODE, -999)} err=${bundle.getInt(RESULT_KEY_ERR, -999)} errmsg='${bundle.getString(RESULT_KEY_ERRMSG, "<null>")}'",
                )
            }
            // Some Termux variants put the keys directly on the broadcast intent rather than
            // nested under "result". Support both shapes by falling back to flat extras.
            val effective = bundle ?: intent.extras ?: Bundle()
            if (resultDeferred.isActive) resultDeferred.complete(effective)
        }
    }
    val filter = IntentFilter(resultAction)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        ctx.registerReceiver(receiver, filter)
    }

    val pi = try {
        val resultIntent = Intent(resultAction).setPackage(ctx.packageName)
        PendingIntent.getBroadcast(
            ctx,
            resultAction.hashCode(),
            resultIntent,
            // Termux fires this PendingIntent twice (started ack + final result). Using
            // FLAG_ONE_SHOT used to consume the ack and lose the real result; FLAG_MUTABLE
            // lets Termux append its own extras to the intent it sends.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    } catch (t: Throwable) {
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        return CaptureResult.OtherError("PendingIntent creation failed: ${t.message}")
    }

    val intent = Intent().apply {
        setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
        action = TERMUX_RUN_COMMAND_ACTION
        putExtra("com.termux.RUN_COMMAND_PATH", executable)
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
        putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        putExtra(EXTRA_PENDING_INTENT, pi)
    }

    return try {
        ctx.startService(intent)
        val bundle = withTimeoutOrNull(timeoutMs) { resultDeferred.await() }
        if (bundle == null) {
            CaptureResult.Timeout
        } else {
            // Per the Termux RUN_COMMAND wiki: `err = -1` (= Activity.RESULT_OK) means
            // "no internal error" — i.e. the success sentinel. Any value other than -1
            // is an actual Termux internal failure (service start failed, manual exit,
            // OS killed it, etc). Earlier revisions inverted this and rejected the
            // success path because err=-1 != 0.
            val errCode = bundle.getInt(RESULT_KEY_ERR, -1)
            if (errCode != -1) {
                val errMsg = bundle.getString(RESULT_KEY_ERRMSG).orEmpty()
                if (errMsg.contains("PermissionDenied", ignoreCase = true) ||
                    errMsg.contains("not allowed", ignoreCase = true)
                ) {
                    CaptureResult.Denied
                } else {
                    CaptureResult.OtherError("err=$errCode: $errMsg")
                }
            } else {
                CaptureResult.Success(
                    stdout = bundle.getString(RESULT_KEY_STDOUT).orEmpty(),
                    stderr = bundle.getString(RESULT_KEY_STDERR).orEmpty(),
                    exitCode = bundle.getInt(RESULT_KEY_EXIT_CODE, -1),
                )
            }
        }
    } catch (t: SecurityException) {
        CaptureResult.Denied
    } catch (t: Throwable) {
        CaptureResult.OtherError(t.message ?: t::class.java.simpleName)
    } finally {
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        try { pi.cancel() } catch (_: Throwable) {}
    }
}

/**
 * LLM-callable termux command tool. Defaults to capture mode (background command, output
 * returned in the JSON envelope so the model can reason about it). Pass `interactive=true`
 * for the legacy "open visible Termux session" mode where the user sees output live but
 * the bot cannot read it.
 */
fun termuxRunCommandTool(context: Context): Tool = Tool(
    name = "termux_run_command",
    description = """
        Execute a shell command in Termux. By default the command runs in the background and
        its stdout / stderr / exit_code are returned to you so you can reason on the output
        (e.g. check if a package is installed, read a file, run a script). Pass
        interactive=true to instead open a visible Termux session - useful when the user
        explicitly wants to watch output live or when the command needs an interactive prompt;
        in that mode no output is returned. Termux must have allow-external-apps=true set in
        ~/.termux/termux.properties (one-time setup). In command mode, apt/apt-get are
        automatically wrapped with DEBIAN_FRONTEND=noninteractive and safe dpkg defaults;
        do not add extra -y flags unless the user specifically asked for unattended upgrades.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command line, e.g. 'pkg update && pkg upgrade -y'. Mutually exclusive with executable+arguments.")
                })
                put("executable", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to executable, e.g. /data/data/com.termux/files/usr/bin/bash. Pairs with arguments[].")
                })
                put("arguments", buildJsonObject {
                    put("type", "array")
                    put("description", "Argument list when using executable mode")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("working_dir", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory. Defaults to Termux home (/data/data/com.termux/files/home).")
                })
                put("interactive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, opens a visible Termux session and does NOT capture output. Default false (background + capture).")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Capture-mode timeout. Default 60. Use 0 to wait up to 5 minutes for long commands.")
                })
            }
        )
    },
    execute = { input ->
        val rawCommand = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        val executable = input.jsonObject["executable"]?.jsonPrimitive?.contentOrNull
        val argumentsArr = input.jsonObject["arguments"]?.jsonArray
        val workingDir = input.jsonObject["working_dir"]?.jsonPrimitive?.contentOrNull
            ?: TERMUX_HOME_DIR
        val interactive = input.jsonObject["interactive"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        val rawTimeout = input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60
        val timeoutMs = when {
            rawTimeout == 0 -> 5L * 60 * 1000  // explicit "long" override
            else -> rawTimeout.coerceIn(1, 300).toLong() * 1000
        }

        if (rawCommand.isNullOrBlank() && executable.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "either 'command' or 'executable' is required") }.toString()
                )
            )
        }
        if (!rawCommand.isNullOrBlank() && !executable.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "command and executable are mutually exclusive") }.toString()
                )
            )
        }

        // Pre-flight: Termux installed?
        when (TermuxIntegration.state(context)) {
            TermuxIntegration.State.NOT_INSTALLED -> {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "termux_not_installed")
                            put("recovery", "Install Termux from the official GitHub releases page: https://github.com/termux/termux-app/releases . Do not use the Play Store or F-Droid build - those are unmaintained.")
                        }.toString()
                    )
                )
            }
            TermuxIntegration.State.NO_PERMISSION -> {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "termux_permission_not_granted")
                            put("recovery", "Toggle Termux on in Assistant -> Local tools so the runtime permission dialog appears, OR run: adb shell pm grant ${context.packageName} com.termux.permission.RUN_COMMAND")
                        }.toString()
                    )
                )
            }
            TermuxIntegration.State.READY -> Unit  // proceed
        }

        // Mark Termux + Termux:API as freshly touched by the agent so the notification
        // listener stops forwarding their persistent foreground notifications ("0 sessions",
        // "1 session", "2 sessions") to the user's Telegram chat for the duration of the
        // turn plus a short grace window. The user knows the agent is running Termux work;
        // they don't need a Telegram ping for every session counter flap.
        AgentTurnTracker.touchPackage(TERMUX_PACKAGE, "com.termux.api")

        // Prepend a noninteractive preamble for `command` mode so apt/pkg upgrades don't
        // hang waiting for "keep your existing config?" debconf prompts. Only applies to
        // the bash -c path; raw executable+arguments callers get no wrapping.
        val (resolvedExe, resolvedArgs) = if (rawCommand != null) {
            val preamble = "export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a; " +
                "apt(){ command apt -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
                "apt-get(){ command apt-get -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
                "export -f apt apt-get; "
            "$TERMUX_BIN_DIR/bash" to arrayOf("-c", preamble + rawCommand)
        } else {
            val args = argumentsArr?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.toTypedArray()
                ?: emptyArray()
            executable!! to args
        }

        if (interactive) {
            // Legacy fire-and-forget interactive session. Cannot read output back.
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                action = TERMUX_RUN_COMMAND_ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", resolvedExe)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", resolvedArgs)
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            return@Tool try {
                context.startService(intent)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("mode", "interactive")
                            put("note", "Opened a visible Termux session. Output is NOT captured by this tool. The user can see it directly.")
                        }.toString()
                    )
                )
            } catch (t: SecurityException) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "termux_permission_denied")
                            put("recovery", "In Termux, run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties. Force-stop Termux and reopen, then retry.")
                        }.toString()
                    )
                )
            } catch (t: Throwable) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "dispatch_failed")
                            put("reason", t.message ?: t::class.java.simpleName)
                        }.toString()
                    )
                )
            }
        }

        // Capture mode (default).
        val payload = when (val res = runCommandCapture(
            ctx = context,
            executable = resolvedExe,
            arguments = resolvedArgs,
            workingDir = workingDir,
            timeoutMs = timeoutMs,
        )) {
            is CaptureResult.Success -> buildJsonObject {
                put("success", true)
                put("mode", "capture")
                put("exit_code", res.exitCode)
                put(
                    "stdout",
                    res.stdout.let { if (it.length > MAX_RETURNED_STDOUT) it.take(MAX_RETURNED_STDOUT) + "\n…[truncated; ${it.length - MAX_RETURNED_STDOUT} bytes more]" else it }
                )
                if (res.stderr.isNotBlank()) {
                    put(
                        "stderr",
                        res.stderr.let { if (it.length > MAX_RETURNED_STDERR) it.take(MAX_RETURNED_STDERR) + "\n…[truncated]" else it }
                    )
                }
                if (res.exitCode != 0) {
                    put("note", "Non-zero exit code; check stderr.")
                }
            }
            is CaptureResult.Timeout -> buildJsonObject {
                put("error", "timeout")
                put("recovery", "Command did not return within ${timeoutMs / 1000}s. Either bump timeout_seconds or, if Termux gave no result at all, the user likely has not set allow-external-apps=true in ~/.termux/termux.properties (or did not restart Termux after editing it).")
            }
            is CaptureResult.Denied -> buildJsonObject {
                put("error", "termux_permission_denied")
                put("recovery", "Open Termux, then run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties. Force-stop Termux from app info and reopen it. Then retry.")
            }
            is CaptureResult.OtherError -> buildJsonObject {
                put("error", "termux_run_failed")
                put("reason", res.message)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
