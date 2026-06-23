package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.contract.RuntimeLogSink
import me.rerere.ai.runtime.hooks.HookConfig
import me.rerere.ai.runtime.hooks.HookDispatcher
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookExecutor
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.HookMatcher
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM suite for the ChatService hook fire-points (#200 T8): UserPromptSubmit at the send seam and
 * Stop at turn end. Production [ChatHookFirePoints] is exercised against a REAL [HookDispatcher]
 * with a scripted executor, so the payload -> parse -> aggregate -> fire-point path under test is
 * exactly what sendMessage/handleMessageComplete consume (StreamingUiCoalescer precedent: shared
 * logic, not a hand-copied loop).
 */
class ChatHookFirePointsTest {

    private class ScriptedExecutor(
        private val respond: suspend (HookEvent, String) -> String,
    ) : HookExecutor {
        val inputs = mutableListOf<String>()

        override suspend fun execute(event: HookEvent, handler: HookHandler, input: String): String {
            inputs += input
            return respond(event, input)
        }
    }

    private class SilentLogSink : RuntimeLogSink {
        override fun info(tag: String, msg: String) {}
        override fun warn(tag: String, msg: String, throwable: Throwable?) {}
        override fun error(tag: String, msg: String, throwable: Throwable?) {}
    }

    private fun firePoints(executor: HookExecutor): ChatHookFirePoints = ChatHookFirePoints(
        dispatcher = HookDispatcher(
            executors = mapOf(HookHandler.Llm::class to executor),
            logSink = SilentLogSink(),
        ),
    )

    private fun trustedConfig(event: HookEvent): HookConfig = HookConfig(
        hooks = mapOf(
            event to listOf(
                HookMatcher(matcher = null, handlers = listOf(HookHandler.Llm(prompt = "p"))),
            ),
        ),
        trusted = true,
    )

    // ---- UserPromptSubmit (send seam) ----

    @Test
    fun `user prompt submit appends hook context to the outgoing parts`() = runBlocking {
        val executor = ScriptedExecutor { _, _ ->
            """{"hookEventName":"UserPromptSubmit","additionalContext":"remember the project deadline"}"""
        }
        val parts = listOf<UIMessagePart>(UIMessagePart.Text("hello"))

        val result = firePoints(executor).onUserPromptSubmit(trustedConfig(HookEvent.UserPromptSubmit), parts)

        assertEquals(
            listOf(
                UIMessagePart.Text("hello"),
                UIMessagePart.Text("remember the project deadline"),
            ),
            result,
        )
    }

    @Test
    fun `user prompt submit payload carries the event name and the prompt text`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> """{}""" }
        val parts = listOf<UIMessagePart>(UIMessagePart.Text("what is the plan"))

        firePoints(executor).onUserPromptSubmit(trustedConfig(HookEvent.UserPromptSubmit), parts)

        assertEquals(1, executor.inputs.size)
        assertTrue(executor.inputs.single().contains("\"hookEventName\":\"UserPromptSubmit\""))
        assertTrue(executor.inputs.single().contains("what is the plan"))
    }

    @Test
    fun `user prompt submit without injected context returns the parts unchanged`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> """{}""" }
        val parts = listOf<UIMessagePart>(UIMessagePart.Text("hello"))

        val result = firePoints(executor).onUserPromptSubmit(trustedConfig(HookEvent.UserPromptSubmit), parts)

        assertEquals(parts, result)
    }

    @Test
    fun `user prompt submit with no configured hooks is passthrough and never dispatches`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> """{"additionalContext":"must not appear"}""" }
        val parts = listOf<UIMessagePart>(UIMessagePart.Text("hello"))

        val result = firePoints(executor).onUserPromptSubmit(HookConfig(trusted = true), parts)

        assertSame(parts, result)
        assertTrue(executor.inputs.isEmpty())
    }

    @Test
    fun `user prompt submit with null dispatcher is passthrough`() = runBlocking {
        val parts = listOf<UIMessagePart>(UIMessagePart.Text("hello"))

        val result = ChatHookFirePoints(dispatcher = null)
            .onUserPromptSubmit(trustedConfig(HookEvent.UserPromptSubmit), parts)

        assertSame(parts, result)
    }

    // ---- Stop (turn end) ----

    @Test
    fun `stop hook context injection requests a continuation carrying the context`() = runBlocking {
        val executor = ScriptedExecutor { _, _ ->
            """{"hookEventName":"Stop","additionalContext":"verify the result before finishing"}"""
        }

        val continuation = firePoints(executor).onStop(trustedConfig(HookEvent.Stop), lastAssistantText = "done")

        assertEquals(StopHookContinuation("verify the result before finishing"), continuation)
    }

    @Test
    fun `stop hook preventContinuation suppresses the continuation even with context`() = runBlocking {
        val executor = ScriptedExecutor { _, _ ->
            """{"additionalContext":"keep going","preventContinuation":true}"""
        }

        val continuation = firePoints(executor).onStop(trustedConfig(HookEvent.Stop), lastAssistantText = "done")

        assertNull(continuation)
    }

    @Test
    fun `stop hook without injected context requests no continuation`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> """{}""" }

        val continuation = firePoints(executor).onStop(trustedConfig(HookEvent.Stop), lastAssistantText = "done")

        assertNull(continuation)
    }

    @Test
    fun `stop payload carries the event name and the last assistant text`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> """{}""" }

        firePoints(executor).onStop(trustedConfig(HookEvent.Stop), lastAssistantText = "final answer")

        assertEquals(1, executor.inputs.size)
        assertTrue(executor.inputs.single().contains("\"hookEventName\":\"Stop\""))
        assertTrue(executor.inputs.single().contains("final answer"))
    }

    @Test
    fun `stop with no configured hooks never dispatches`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> """{"additionalContext":"must not appear"}""" }

        val continuation = firePoints(executor).onStop(HookConfig(trusted = true), lastAssistantText = null)

        assertNull(continuation)
        assertTrue(executor.inputs.isEmpty())
    }

    // ---- /goal judge (#364): the 3-way verdict that keeps a judge FAILURE from clearing the goal ----

    @Test
    fun `judge goal returns Continue with the directive when not met`() = runBlocking {
        val executor = ScriptedExecutor { _, _ ->
            """{"hookEventName":"Stop","additionalContext":"run the failing test next"}"""
        }

        val verdict = firePoints(executor).judgeGoal(trustedConfig(HookEvent.Stop), lastAssistantText = "wip")

        assertEquals(GoalVerdict.Continue("run the failing test next"), verdict)
    }

    @Test
    fun `judge goal returns Met when the LLM sets preventContinuation`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> """{"preventContinuation":true}""" }

        val verdict = firePoints(executor).judgeGoal(trustedConfig(HookEvent.Stop), lastAssistantText = "done")

        assertEquals(GoalVerdict.Met, verdict)
    }

    @Test
    fun `judge goal returns Inconclusive when the judge fails (fail-open empty)`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> throw RuntimeException("provider down") }

        val verdict = firePoints(executor).judgeGoal(trustedConfig(HookEvent.Stop), lastAssistantText = "wip")

        // A judge failure must NOT look like "met" — it must pause the goal, not clear it.
        assertEquals(GoalVerdict.Inconclusive, verdict)
    }

    @Test
    fun `judge goal returns Inconclusive on an empty no-verdict answer`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> """{}""" }

        val verdict = firePoints(executor).judgeGoal(trustedConfig(HookEvent.Stop), lastAssistantText = "wip")

        assertEquals(GoalVerdict.Inconclusive, verdict)
    }

    @Test
    fun `judge goal returns Inconclusive with a null dispatcher`() = runBlocking {
        val verdict = ChatHookFirePoints(dispatcher = null)
            .judgeGoal(trustedConfig(HookEvent.Stop), lastAssistantText = "wip")

        assertEquals(GoalVerdict.Inconclusive, verdict)
    }

    @Test
    fun `judge goal returns Inconclusive when no Stop matcher is configured`() = runBlocking {
        val executor = ScriptedExecutor { _, _ -> """{"additionalContext":"must not run"}""" }

        val verdict = firePoints(executor).judgeGoal(HookConfig(trusted = true), lastAssistantText = "wip")

        assertEquals(GoalVerdict.Inconclusive, verdict)
        assertTrue(executor.inputs.isEmpty())
    }
}
