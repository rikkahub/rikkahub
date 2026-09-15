package me.rerere.rikkahub.data.ai.tools.local

import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
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
        val logs = arrayListOf<String>()
        // [FIX] QuickJS 上下文持有原生内存，执行完必须 destroy()，
        // 否则每次工具调用泄漏一份 native heap（长会话反复调用会 OOM）。
        val context = QuickJSContext.create()
        var thread: Thread? = null
        try {
            context.setConsole(object : QuickJSContext.Console {
                override fun log(info: String?) {
                    logs.add("[LOG] $info")
                }

                override fun info(info: String?) {
                    logs.add("[INFO] $info")
                }

                override fun warn(info: String?) {
                    logs.add("[WARN] $info")
                }

                override fun error(info: String?) {
                    logs.add("[ERROR] $info")
                }
            })
            // [FIX] 内存炸弹防护：模型生成的代码可能 new 超大数组/疯狂拼接字符串
            // （如 new Array(1e9)），无限制时 native 堆被撑爆 → OOM 崩溃。64MB 上限。
            context.setMemoryLimit(JS_MEMORY_LIMIT_BYTES)
            // [FIX] 深递归防护：超出栈上限的递归会抛 JS 异常（可捕获），而非 native 栈溢出
            context.setMaxStackSize(JS_MAX_STACK_BYTES)

            val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
            // [FIX] QuickJS evaluate 是同步执行且 wrapper 无中断 API：死循环代码
            // （while(true){}）会永久阻塞调用线程。在独立线程执行 + 超时放弃：
            // - 正常结束 → 收集结果，destroy context 释放 native 内存
            // - 超时 → 放弃执行线程（不 destroy context——正在执行的 native 代码
            //   访问已释放的 runtime 是 use-after-free，会 SIGSEGV 崩溃整个 app）。
            //   线程/context 泄漏到进程结束，但 app 不崩溃；死循环属罕见输入，可接受。
            val resultHolder = java.util.concurrent.atomic.AtomicReference<Any?>()
            val errorHolder = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val execThread = Thread {
                try {
                    resultHolder.set(context.evaluate(code))
                } catch (t: Throwable) {
                    errorHolder.set(t)
                }
            }.apply {
                isDaemon = true
                start()
            }
            thread = execThread
            execThread.join(JS_EXECUTION_TIMEOUT_MS)

            if (execThread.isAlive) {
                listOf(
                    UIMessagePart.Text(
                        """{"error":"JavaScript execution timed out after ${JS_EXECUTION_TIMEOUT_MS / 1000}s (possible infinite loop)"}"""
                    )
                )
            } else {
                errorHolder.get()?.let { throw it }
                val result = resultHolder.get()
                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put(
                        key = "result",
                        element = when (result) {
                            null -> JsonNull
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        } finally {
            // 仅正常结束（非超时）时销毁；超时路径线程可能仍在执行 native 代码，
            // destroy 会触发 use-after-free 崩溃，故此时故意跳过（泄漏到进程结束）。
            if (thread?.isAlive != true) {
                context.destroy()
            }
        }
    }
)

private const val JS_EXECUTION_TIMEOUT_MS = 10_000L
// [FIX] javap 确认 setMemoryLimit(int)/setMaxStackSize(int) 是 int 参数
// （64MB 和 1MB 都在 Int.MAX 范围内）
private const val JS_MEMORY_LIMIT_BYTES = 64 * 1024 * 1024
private const val JS_MAX_STACK_BYTES = 1024 * 1024
