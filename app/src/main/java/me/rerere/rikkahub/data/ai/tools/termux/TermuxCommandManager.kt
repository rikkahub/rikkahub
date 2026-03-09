package me.rerere.rikkahub.data.ai.tools.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.utils.JsonInstant

class TermuxCommandManager(
    private val context: Context,
) {
    private val callbackRequestCodeCounter = AtomicInteger(1000)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxResult>>()
    private val executionStore = TermuxExecutionStore(
        baseDir = File(context.filesDir, "termux/run_command"),
        json = JsonInstant,
    )

    suspend fun run(request: TermuxRunCommandRequest): TermuxResult {
        ensureTermuxInstalled()
        ensurePermissionGranted()
        return runInternal(request)
    }

    fun complete(executionId: String, result: TermuxResult) {
        if (executionStore.readPending(executionId) != null) {
            executionStore.markCompleted(executionId, result)
        }
        pending.remove(executionId)?.complete(result)
    }

    private suspend fun runInternal(request: TermuxRunCommandRequest): TermuxResult {
        val executionId = UUID.randomUUID().toString()
        val requestCode = callbackRequestCodeCounter.getAndIncrement()
        val deferred = CompletableDeferred<TermuxResult>()
        val actualRequest = if (request.trackLifecycle) {
            buildTrackedRequest(request = request, executionId = executionId)
        } else {
            request
        }

        if (request.trackLifecycle) {
            executionStore.recordPending(
                TermuxPendingExecutionRecord(
                    executionId = executionId,
                    commandPath = request.commandPath,
                    arguments = request.arguments,
                    workdir = request.workdir,
                    label = request.label,
                )
            )
        }
        pending[executionId] = deferred

        val resultIntent = Intent(context, TermuxResultService::class.java).apply {
            putExtra(TermuxResultService.EXTRA_EXECUTION_ID, executionId)
        }
        val pendingIntentFlags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getService(
            context,
            requestCode,
            resultIntent,
            pendingIntentFlags,
        )

        val intent = Intent().apply {
            setClassName(TermuxProtocol.TERMUX_PACKAGE_NAME, TermuxProtocol.RUN_COMMAND_SERVICE)
            action = TermuxProtocol.ACTION_RUN_COMMAND

            putExtra(TermuxProtocol.EXTRA_COMMAND_PATH, actualRequest.commandPath)
            putExtra(TermuxProtocol.EXTRA_ARGUMENTS, actualRequest.arguments.toTypedArray())
            putExtra(TermuxProtocol.EXTRA_WORKDIR, actualRequest.workdir)
            putExtra(TermuxProtocol.EXTRA_BACKGROUND, actualRequest.background)
            putExtra(TermuxProtocol.EXTRA_PENDING_INTENT, pendingIntent)

            actualRequest.stdin?.let { putExtra(TermuxProtocol.EXTRA_STDIN, it) }
            actualRequest.label?.let { putExtra(TermuxProtocol.EXTRA_COMMAND_LABEL, it) }
            actualRequest.description?.let { putExtra(TermuxProtocol.EXTRA_COMMAND_DESCRIPTION, it) }
        }

        val startedService = runCatching {
            context.startService(intent)
        }.getOrElse { e ->
            pending.remove(executionId)
            if (request.trackLifecycle) executionStore.clearAll(executionId)
            throw e
        }
        if (startedService == null) {
            pending.remove(executionId)
            if (request.trackLifecycle) executionStore.clearAll(executionId)
            error("Failed to start Termux RunCommandService (startService returned null)")
        }

        return try {
            val result = withTimeout(request.timeoutMs) {
                deferred.await()
            }
            if (request.trackLifecycle) {
                executionStore.clearAll(executionId)
            }
            result
        } catch (_: TimeoutCancellationException) {
            if (deferred.isCompleted) {
                val completedResult = runCatching { deferred.getCompleted() }.getOrNull()
                if (completedResult != null) {
                    if (request.trackLifecycle) {
                        executionStore.clearAll(executionId)
                    }
                    return completedResult
                }
            }
            val terminationMessage = if (request.trackLifecycle) {
                withContext(NonCancellable) {
                    terminateTrackedExecution(
                        executionId = executionId,
                        reason = "timed out",
                    )
                }
            } else {
                null
            }
            TermuxResult(
                timedOut = true,
                errMsg = buildList {
                    add("Timed out after ${request.timeoutMs}ms")
                    terminationMessage?.takeIf { it.isNotBlank() }?.let(::add)
                }.joinToString(separator = "\n"),
            )
        } catch (e: CancellationException) {
            if (request.trackLifecycle) {
                withContext(NonCancellable) {
                    terminateTrackedExecution(
                        executionId = executionId,
                        reason = "was cancelled",
                    )
                }
            }
            throw e
        } finally {
            pending.remove(executionId)
        }
    }

    private fun buildTrackedRequest(
        request: TermuxRunCommandRequest,
        executionId: String,
    ): TermuxRunCommandRequest {
        return request.copy(
            commandPath = TERMUX_BASH_PATH,
            arguments = listOf(
                "-lc",
                buildExecutionWrapperScript(executionId = executionId),
                "_",
                request.commandPath,
            ) + request.arguments,
            trackLifecycle = false,
        )
    }

    private suspend fun terminateTrackedExecution(
        executionId: String,
        reason: String,
    ): String? {
        val result = runCatching {
            runInternal(
                TermuxRunCommandRequest(
                    commandPath = TERMUX_BASH_PATH,
                    arguments = listOf("-lc", buildTerminateScript(executionId)),
                    workdir = TERMUX_HOME_PATH,
                    background = false,
                    timeoutMs = TERMINATION_TIMEOUT_MS,
                    label = "RikkaHub terminate termux command",
                    description = "Terminate tracked Termux execution",
                    trackLifecycle = false,
                )
            )
        }.getOrElse { e ->
            return "Failed to terminate underlying Termux process after it $reason: ${e.message ?: e.javaClass.name}"
        }

        if (result.isSuccessful()) return null

        return TermuxOutputFormatter.merge(
            stdout = result.stdout,
            stderr = result.stderr,
            errMsg = result.errMsg,
        ).ifBlank {
            TermuxOutputFormatter.statusSummary(result)
        }.ifBlank {
            "Failed to terminate underlying Termux process after it $reason."
        }
    }

    private fun buildExecutionWrapperScript(executionId: String): String {
        return buildString {
            appendLine("set -eu")
            appendLine()
            appendLine("STATE_DIR=\"${'$'}HOME/.rikkahub/run_command\"")
            appendLine("PID_FILE=\"${'$'}STATE_DIR/$executionId.pid\"")
            appendLine("TARGET_COMMAND=\"${'$'}1\"")
            appendLine("shift")
            appendLine()
            appendLine("mkdir -p \"${'$'}STATE_DIR\"")
            appendLine("cleanup() {")
            appendLine("  rm -f \"${'$'}PID_FILE\"")
            appendLine("}")
            appendLine("trap cleanup EXIT INT TERM")
            appendLine("export RIKKAHUB_TERMUX_EXECUTION_ID='$executionId'")
            appendLine("printf '%s\\n' \"${'$'}${'$'}\" > \"${'$'}PID_FILE\"")
            appendLine("\"${'$'}TARGET_COMMAND\" \"${'$'}@\"")
        }
    }

    private fun buildTerminateScript(executionId: String): String {
        val safeExecutionId = escapeForSingleQuotedShell(executionId)
        return buildString {
            appendLine("set -eu")
            appendLine()
            appendLine("EXECUTION_ID='$safeExecutionId'")
            appendLine("STATE_DIR=\"${'$'}HOME/.rikkahub/run_command\"")
            appendLine("PID_FILE=\"${'$'}STATE_DIR/${'$'}EXECUTION_ID.pid\"")
            appendLine()
            appendLine("is_tracked_pid() {")
            appendLine("  _pid=\"${'$'}1\"")
            appendLine("  [ -n \"${'$'}_pid\" ] || return 1")
            appendLine("  [ -r \"/proc/${'$'}_pid/environ\" ] || return 1")
            appendLine("  tr '\\0' '\\n' < \"/proc/${'$'}_pid/environ\" 2>/dev/null | grep -Fxq \"RIKKAHUB_TERMUX_EXECUTION_ID=${'$'}EXECUTION_ID\"")
            appendLine("}")
            appendLine()
            appendLine("list_child_pids() {")
            appendLine("  _parent=\"${'$'}1\"")
            appendLine("  for _proc in /proc/[0-9]*; do")
            appendLine("    _pid=\"${'$'}{_proc#/proc/}\"")
            appendLine("    [ \"${'$'}_pid\" = \"${'$'}_parent\" ] && continue")
            appendLine("    [ -r \"${'$'}_proc/status\" ] || continue")
            appendLine("    _ppid=\"${'$'}(sed -n 's/^PPid:[[:space:]]*//p' \"${'$'}_proc/status\" | head -n 1)\"")
            appendLine("    [ \"${'$'}_ppid\" = \"${'$'}_parent\" ] || continue")
            appendLine("    list_child_pids \"${'$'}_pid\"")
            appendLine("    echo \"${'$'}_pid\"")
            appendLine("  done")
            appendLine("}")
            appendLine()
            appendLine("collect_alive_pids() {")
            appendLine("  _alive=\"\"")
            appendLine("  for _pid in ${'$'}@; do")
            appendLine("    if kill -0 \"${'$'}_pid\" 2>/dev/null; then")
            appendLine("      if [ -z \"${'$'}_alive\" ]; then")
            appendLine("        _alive=\"${'$'}_pid\"")
            appendLine("      else")
            appendLine("        _alive=\"${'$'}_alive ${'$'}_pid\"")
            appendLine("      fi")
            appendLine("    fi")
            appendLine("  done")
            appendLine("  printf '%s' \"${'$'}_alive\"")
            appendLine("}")
            appendLine()
            appendLine("ROOT_PID=\"${'$'}(cat \"${'$'}PID_FILE\" 2>/dev/null || true)\"")
            appendLine("if [ -z \"${'$'}ROOT_PID\" ]; then")
            appendLine("  echo \"Execution already finished.\"")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine()
            appendLine("if ! kill -0 \"${'$'}ROOT_PID\" 2>/dev/null; then")
            appendLine("  rm -f \"${'$'}PID_FILE\"")
            appendLine("  echo \"Execution already finished.\"")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine()
            appendLine("if ! is_tracked_pid \"${'$'}ROOT_PID\"; then")
            appendLine("  rm -f \"${'$'}PID_FILE\"")
            appendLine("  echo \"Tracked PID is stale: ${'$'}ROOT_PID\"")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine()
            appendLine("TARGET_PIDS=\"${'$'}(list_child_pids \"${'$'}ROOT_PID\"; echo \"${'$'}ROOT_PID\")\"")
            appendLine("ALIVE_PIDS=\"${'$'}(collect_alive_pids ${'$'}TARGET_PIDS)\"")
            appendLine("if [ -n \"${'$'}ALIVE_PIDS\" ]; then")
            appendLine("  for _pid in ${'$'}ALIVE_PIDS; do")
            appendLine("    kill -TERM \"${'$'}_pid\" 2>/dev/null || true")
            appendLine("  done")
            appendLine("  sleep 0.3")
            appendLine("  ALIVE_PIDS=\"${'$'}(collect_alive_pids ${'$'}TARGET_PIDS)\"")
            appendLine("fi")
            appendLine("if [ -n \"${'$'}ALIVE_PIDS\" ]; then")
            appendLine("  for _pid in ${'$'}ALIVE_PIDS; do")
            appendLine("    kill -KILL \"${'$'}_pid\" 2>/dev/null || true")
            appendLine("  done")
            appendLine("  sleep 0.3")
            appendLine("  ALIVE_PIDS=\"${'$'}(collect_alive_pids ${'$'}TARGET_PIDS)\"")
            appendLine("fi")
            appendLine()
            appendLine("rm -f \"${'$'}PID_FILE\"")
            appendLine("if [ -n \"${'$'}ALIVE_PIDS\" ]; then")
            appendLine("  echo \"Failed to terminate tracked Termux execution: ${'$'}ALIVE_PIDS\" >&2")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("echo \"Terminated tracked Termux execution.\"")
        }
    }

    private fun escapeForSingleQuotedShell(value: String): String {
        return value.replace("'", "'\"'\"'")
    }

    private fun ensureTermuxInstalled() {
        val pm = context.packageManager
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    TermuxProtocol.TERMUX_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TermuxProtocol.TERMUX_PACKAGE_NAME, 0)
            }
        }.isSuccess

        check(installed) {
            "Termux is not installed. Please install Termux (com.termux) first."
        }
    }

    private fun ensurePermissionGranted() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            TermuxProtocol.PERMISSION_RUN_COMMAND,
        ) == PackageManager.PERMISSION_GRANTED

        check(granted) {
            "Permission ${TermuxProtocol.PERMISSION_RUN_COMMAND} is not granted. " +
                "Open this app -> Settings -> Termux and tap the Grant button."
        }
    }

    private companion object {
        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"
        private const val TERMINATION_TIMEOUT_MS = 10_000L
    }
}
