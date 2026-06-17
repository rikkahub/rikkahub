package me.rerere.ai.runtime.hooks

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.RecordingLogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit suite for [HookDispatcher] with fake executors (plan T5). The contract under test:
 * `dispatch(event, input, ctx)` = trust gate -> matcher filter -> injected [HookExecutor] port ->
 * `withTimeoutOrNull` -> [parseHookOutput] -> [aggregate]. The agent loop only ever sees the
 * [AggregatedHookResult]; individual handlers stay behind the dispatcher.
 */
class HookDispatcherTest {

    /** Fake [HookExecutor]: records every invocation and answers from a script keyed by prompt. */
    private class ScriptedExecutor(
        private val respond: suspend (HookHandler, String) -> String,
    ) : HookExecutor {
        val invoked = mutableListOf<HookHandler>()

        override suspend fun execute(event: HookEvent, handler: HookHandler, input: String): String {
            invoked += handler
            return respond(handler, input)
        }
    }

    private fun llm(prompt: String, failClosed: Boolean = false): HookHandler.Llm =
        HookHandler.Llm(prompt = prompt, failClosed = failClosed)

    private fun dispatcher(
        executor: HookExecutor,
        logSink: RecordingLogSink = RecordingLogSink(),
        perHookTimeout: Duration = 5.seconds,
    ): HookDispatcher = HookDispatcher(
        executors = mapOf(HookHandler.Llm::class to executor),
        logSink = logSink,
        perHookTimeout = perHookTimeout,
    )

    @Test
    fun `dispatch with empty handler list executes none and does not invoke executor`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = emptyList()),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { _, _ -> """{"decision":"deny","reason":"should not run"}""" }

            val result = dispatcher(executor).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertEquals(AggregatedHookResult(), result)
            assertTrue(executor.invoked.isEmpty())
        }
    }

    @Test
    fun `dispatch runs the ordered prefix up to the per-dispatch cap and logs skipped suffix handlers`() {
        runBlocking {
            val h1 = llm("first")
            val h2 = llm("second")
            val h3 = llm("third")
            val h4 = llm("fourth")
            val h5 = llm("fifth")
            val h6 = llm("sixth")
            val h7 = llm("seventh")
            val h8 = llm("eighth")
            val h9 = llm("ninth")
            val h10 = llm("tenth")
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = "search", handlers = listOf(h1, h2, h3, h4, h5, h6, h7, h8, h9, h10)),
                    ),
                ),
                trusted = true,
            )
            val logSink = RecordingLogSink()
            val executor = ScriptedExecutor { _, _ -> "{}" }

            val result = dispatcher(executor, logSink = logSink).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertEquals(listOf(h1, h2, h3, h4, h5, h6, h7, h8), executor.invoked)
            assertEquals(HookDecision.Allow, result.decision)
            assertEquals(2, logSink.lines.count { it.level == "warn" && it.msg.contains("skipping hook handler") })
        }
    }

    @Test
    fun `dispatch with exactly-cap handlers runs all and with cap+1 only cap are executed`() {
        runBlocking {
            val handlers = (1..8).map { llm("h$it") }
            val onePast = llm("h_extra")
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = handlers + onePast),
                    ),
                ),
                trusted = true,
            )
            val logSink = RecordingLogSink()
            val executor = ScriptedExecutor { _, _ -> "{}" }

            val result = dispatcher(executor, logSink = logSink).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertEquals(handlers, executor.invoked)
            assertEquals(1, logSink.lines.count { it.level == "warn" && it.msg.contains("skipping hook handler") })
            assertEquals(HookDecision.Allow, result.decision)
        }
    }

    @Test
    fun `dispatch budget is shared across dispatches in one generation`() {
        runBlocking {
            val h1 = llm("g1")
            val h2 = llm("g2")
            val h3 = llm("g3")
            val h4 = llm("g4")
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(h1, h2, h3, h4)),
                    ),
                ),
                trusted = true,
            )
            val budget = HookWorkBudget(HookDispatchLimits(maxHandlerExecutionsPerGeneration = 3))
            val logSink = RecordingLogSink()
            val executor = ScriptedExecutor { _, _ -> "{}" }
            val dispatcher = dispatcher(executor, logSink = logSink)
            val context = HookDispatchContext(
                config = config,
                toolName = "search",
                budget = budget,
            )

            dispatcher.dispatch(event = HookEvent.PreToolUse, input = "{}", ctx = context)
            dispatcher.dispatch(event = HookEvent.PreToolUse, input = "{}", ctx = context)

            assertEquals(3, executor.invoked.size)
            assertEquals(0, budget.remaining)
            assertEquals(5, logSink.lines.count { it.level == "warn" && it.msg.contains("skipping hook handler") })
        }
    }

    @Test
    fun `skipped failClosed handler emits deny without invoking executor`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("closed", failClosed = true))),
                    ),
                ),
                trusted = true,
            )
            val logSink = RecordingLogSink()
            val executor = ScriptedExecutor { _, _ ->
                error("should never run")
            }
            val budget = HookWorkBudget(HookDispatchLimits(maxHandlerExecutionsPerGeneration = 0))

            val result = dispatcher(executor, logSink = logSink).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search", budget = budget),
            )

            assertTrue(result.decision is HookDecision.Deny)
            assertTrue(executor.invoked.isEmpty())
            assertTrue(logSink.lines.any { it.level == "warn" && it.msg.contains("skipping hook handler") })
        }
    }

    @Test
    fun `skipped fail-open handler logs but does not deny`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("open", failClosed = false))),
                    ),
                ),
                trusted = true,
            )
            val logSink = RecordingLogSink()
            val executor = ScriptedExecutor { _, _ ->
                error("should never run")
            }
            val budget = HookWorkBudget(HookDispatchLimits(maxHandlerExecutionsPerGeneration = 0))

            val result = dispatcher(executor, logSink = logSink).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search", budget = budget),
            )

            assertEquals(HookDecision.Allow, result.decision)
            assertTrue(executor.invoked.isEmpty())
            assertTrue(logSink.lines.any { it.level == "warn" && it.msg.contains("skipping hook handler") })
        }
    }

    @Test
    fun `dispatch runs only handlers whose matcher matches the tool and aggregates their outputs`() {
        runBlocking {
            val matched = llm("matched")
            val unmatched = llm("unmatched")
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = "search", handlers = listOf(matched)),
                        HookMatcher(matcher = "other_tool", handlers = listOf(unmatched)),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { _, _ ->
                """{"decision":"deny","reason":"blocked by hook"}"""
            }

            val result = dispatcher(executor).dispatch(
                event = HookEvent.PreToolUse,
                input = """{"toolName":"search"}""",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertEquals(HookDecision.Deny("blocked by hook"), result.decision)
            assertEquals(listOf<HookHandler>(matched), executor.invoked)
        }
    }

    @Test
    fun `dispatch aggregates multiple matched handlers - deny wins and context concatenates in order`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(
                            matcher = null,
                            handlers = listOf(llm("first"), llm("second"), llm("third")),
                        ),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { handler, _ ->
                when ((handler as HookHandler.Llm).prompt) {
                    "first" -> """{"decision":"allow","additionalContext":"ctx-1","updatedInput":"{\"a\":1}"}"""
                    "second" -> """{"decision":"ask","additionalContext":"ctx-2"}"""
                    else -> """{"decision":"deny","reason":"nope","updatedInput":"{\"a\":2}"}"""
                }
            }

            val result = dispatcher(executor).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "any"),
            )

            assertEquals(HookDecision.Deny("nope"), result.decision)
            assertEquals("ctx-1\nctx-2", result.additionalContext)
            assertEquals("""{"a":2}""", result.updatedInput)
        }
    }

    @Test
    fun `null matcher fires for events that carry no tool name`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.UserPromptSubmit to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("inject"))),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { _, _ -> """{"additionalContext":"remember the rules"}""" }

            val result = dispatcher(executor).dispatch(
                event = HookEvent.UserPromptSubmit,
                input = """{"prompt":"hi"}""",
                ctx = HookDispatchContext(config = config, toolName = null),
            )

            assertEquals(HookDecision.Allow, result.decision)
            assertEquals("remember the rules", result.additionalContext)
            assertEquals(1, executor.invoked.size)
        }
    }

    @Test
    fun `untrusted config dispatches as passthrough without invoking any executor`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("never", failClosed = true))),
                    ),
                ),
                trusted = false,
            )
            val executor = ScriptedExecutor { _, _ -> """{"decision":"deny","reason":"x"}""" }

            val result = dispatcher(executor).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertEquals(AggregatedHookResult(), result)
            assertTrue(executor.invoked.isEmpty())
        }
    }

    @Test
    fun `failClosed executor error aggregates as Deny`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("boom", failClosed = true))),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { _, _ -> throw IllegalStateException("provider exploded") }

            val result = dispatcher(executor).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertTrue(result.decision is HookDecision.Deny)
        }
    }

    @Test
    fun `non-failClosed executor error aggregates as Allow and is logged`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("boom", failClosed = false))),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { _, _ -> throw IllegalStateException("provider exploded") }
            val logSink = RecordingLogSink()

            val result = dispatcher(executor, logSink = logSink).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertEquals(HookDecision.Allow, result.decision)
            assertTrue(logSink.lines.any { it.level == "warn" && it.msg.contains("provider exploded") })
        }
    }

    @Test
    fun `failClosed executor timeout aggregates as Deny`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("slow", failClosed = true))),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { _, _ ->
                delay(10.seconds)
                """{"decision":"allow"}"""
            }

            val result = dispatcher(executor, perHookTimeout = 50.milliseconds).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertTrue(result.decision is HookDecision.Deny)
        }
    }

    @Test
    fun `non-failClosed executor timeout aggregates as Allow and is logged`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("slow", failClosed = false))),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { _, _ ->
                delay(10.seconds)
                """{"decision":"deny","reason":"too late"}"""
            }
            val logSink = RecordingLogSink()

            val result = dispatcher(executor, logSink = logSink, perHookTimeout = 50.milliseconds).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertEquals(HookDecision.Allow, result.decision)
            assertTrue(logSink.lines.any { it.level == "warn" })
        }
    }

    @Test
    fun `malformed handler output maps through the failClosed policy`() {
        runBlocking {
            fun configWith(failClosed: Boolean) = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("garbage", failClosed = failClosed))),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { _, _ -> "this is not a hook output" }

            val closed = dispatcher(executor).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = configWith(failClosed = true), toolName = "search"),
            )
            val open = dispatcher(executor).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = configWith(failClosed = false), toolName = "search"),
            )

            assertTrue(closed.decision is HookDecision.Deny)
            assertEquals(HookDecision.Allow, open.decision)
        }
    }

    @Test
    fun `output spoofing a different event is rejected through the failClosed policy`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("spoof", failClosed = true))),
                    ),
                ),
                trusted = true,
            )
            val executor = ScriptedExecutor { _, _ ->
                """{"hookEventName":"Stop","decision":"allow","preventContinuation":true}"""
            }

            val result = dispatcher(executor).dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertTrue(result.decision is HookDecision.Deny)
            assertEquals(false, result.preventContinuation)
        }
    }

    @Test
    fun `handler type with no bound executor maps through the failClosed policy`() {
        runBlocking {
            val config = HookConfig(
                hooks = mapOf(
                    HookEvent.PreToolUse to listOf(
                        HookMatcher(matcher = null, handlers = listOf(llm("orphan", failClosed = true))),
                    ),
                ),
                trusted = true,
            )
            val logSink = RecordingLogSink()
            val dispatcher = HookDispatcher(
                executors = emptyMap(),
                logSink = logSink,
                perHookTimeout = 1.seconds,
            )

            val result = dispatcher.dispatch(
                event = HookEvent.PreToolUse,
                input = "{}",
                ctx = HookDispatchContext(config = config, toolName = "search"),
            )

            assertTrue(result.decision is HookDecision.Deny)
            assertTrue(logSink.lines.any { it.level == "warn" })
        }
    }

    @Test
    fun `event with no configured matchers is the Allow passthrough`() {
        runBlocking {
            val executor = ScriptedExecutor { _, _ -> """{"decision":"deny","reason":"x"}""" }

            val result = dispatcher(executor).dispatch(
                event = HookEvent.Stop,
                input = "{}",
                ctx = HookDispatchContext(config = HookConfig(trusted = true), toolName = null),
            )

            assertEquals(AggregatedHookResult(), result)
            assertTrue(executor.invoked.isEmpty())
        }
    }
}
