package me.rerere.rikkahub.data.ai.tools.local

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Duration.Companion.milliseconds

internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript code using QuickJS engine (ES2020).
        The result is the value of the last expression in the code.
        For calculations with decimals, use toFixed() to control precision.
        Console output (log/info/warn/error) is captured and returned in 'logs' field.
        No DOM or Node.js APIs available.
        Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        val code = requireNotNull(it.jsonObject["code"]?.jsonPrimitive?.contentOrNull) {
            "JavaScript code is required"
        }
        listOf(UIMessagePart.Text(evaluateJavascript(code)))
    }
)

internal suspend fun evaluateJavascript(
    code: String,
    timeoutMillis: Long = JS_EXECUTION_TIMEOUT_MS,
): String = withContext(Dispatchers.Default) {
    val logs = StringBuilder()
    var logsTruncated = false
    fun appendLog(line: String) {
        val remaining = JS_MAX_LOG_CHARS - logs.length
        if (remaining <= 0) {
            logsTruncated = true
            return
        }
        val entry = if (logs.isEmpty()) line else "\n$line"
        logs.append(entry.take(remaining))
        if (entry.length > remaining) logsTruncated = true
    }

    fun errorPayload(message: String) = buildJsonObject {
        put("error", message)
    }.toString()

    try {
        withTimeoutOrNull(timeoutMillis.milliseconds) {
            // This binding interrupts native evaluation on timeout/cancellation and closes
            // the runtime only after execution stops, including result serialization.
            quickJs(Dispatchers.Default) {
                memoryLimit = JS_MEMORY_LIMIT_BYTES
                maxStackSize = JS_MAX_STACK_BYTES
                evaluationTimeoutMillis = timeoutMillis
                function("__rikkahubLog") { args ->
                    appendLog(args[0] as String)
                }
                // Convert values inside the timed evaluation: getters/toJSON can run JS,
                // and returning arbitrary objects would also bypass the output limit.
                val result = evaluate<String?>(
                    """
                    (() => {
                        const log = globalThis.__rikkahubLog;
                        delete globalThis.__rikkahubLog;
                        const stringify = JSON.stringify;
                        const toString = String;
                        const format = value => {
                            if (value !== null && typeof value === 'object') {
                                try { return stringify(value); } catch (_) {}
                            }
                            return toString(value);
                        };
                        globalThis.console = {};
                        for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
                            console[level] = (...args) => {
                                let line = '[' + (level === 'debug' ? 'LOG' : level.toUpperCase()) + ']';
                                for (const arg of args) {
                                    line += ' ' + format(arg);
                                    if (line.length > $JS_MAX_LOG_CHARS) {
                                        line = line.slice(0, $JS_MAX_LOG_CHARS) + ' [truncated]';
                                        break;
                                    }
                                }
                                log(line);
                            };
                        }
                        const result = (0, eval)(${JsonPrimitive(code)});
                        if (result == null) return null;
                        const type = typeof result;
                        const text = type === 'object' || type === 'function'
                            ? stringify(result) : toString(result);
                        if (text != null && text.length > $JS_MAX_RESULT_CHARS) {
                            throw new Error('JavaScript result exceeds output limit');
                        }
                        return text == null ? null : text;
                    })()
                    """.trimIndent()
                )
                buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", logs.toString() + if (logsTruncated) "\n[Logs truncated]" else "")
                    }
                    put("result", result?.let(::JsonPrimitive) ?: JsonNull)
                }.toString()
            }
        } ?: errorPayload("JavaScript execution timed out after ${timeoutMillis}ms")
    } catch (_: QuickJsInterruptedException) {
        errorPayload("JavaScript execution timed out after ${timeoutMillis}ms")
    } catch (e: QuickJsException) {
        errorPayload((e.message ?: "JavaScript execution failed").take(JS_MAX_LOG_CHARS))
    }
}

private const val JS_EXECUTION_TIMEOUT_MS = 10_000L
private const val JS_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
private const val JS_MAX_STACK_BYTES = 256L * 1024
private const val JS_MAX_LOG_CHARS = 64 * 1024
private const val JS_MAX_RESULT_CHARS = 1024 * 1024
