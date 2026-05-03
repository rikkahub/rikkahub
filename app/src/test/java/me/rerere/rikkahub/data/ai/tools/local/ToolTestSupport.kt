package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import sun.misc.Unsafe

/**
 * A "ghost" ContextWrapper allocated via sun.misc.Unsafe — has the right type identity to
 * satisfy non-null parameter checks, but its constructor was never run. Any actual call
 * dispatched onto it will explode (and that's the point — these tests must NEVER reach a
 * Context method).
 *
 * Suitable only for testing validation paths that early-return BEFORE any Context method is
 * called. Real Android API behavior must be exercised with instrumented tests.
 */
private val UNSAFE: Unsafe = run {
    val field = Unsafe::class.java.getDeclaredField("theUnsafe")
    field.isAccessible = true
    field.get(null) as Unsafe
}

internal val NULL_CONTEXT: Context =
    UNSAFE.allocateInstance(ContextWrapper::class.java) as Context

internal fun execTool(tool: Tool, args: String): String = runBlocking {
    val parts = tool.execute(Json.parseToJsonElement(args))
    (parts.first() as UIMessagePart.Text).text
}
