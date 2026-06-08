package me.rerere.rikkahub.ui.pages.chat

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.contextTokens
import me.rerere.ai.core.resolveReserveOutput
import me.rerere.ai.core.shouldAutoCompact
import me.rerere.ai.core.tokenPressure
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 (design #193 Stage 1): the size-warning path and the auto-compact trigger consume the SAME
 * [tokenPressure] reading over the SAME [contextTokens] measurement, so for identical inputs they
 * cannot disagree on whether the conversation is over threshold. Also locks the deletion of the old
 * hardcoded 300k constant: the warning is now model-relative (a fixed token count means different
 * pressure on a 128k vs a 1M window).
 *
 * The warning fraction is derived from the assistant's [Model]-independent `autoCompactThreshold`
 * (capped), so the design invariant `warningFraction <= autoCompactThreshold` holds — the warning can
 * never fire LATER than the auto-compact trigger.
 *
 * Tested through the extracted pure core [computeConversationSizeInfo] that the @Composable
 * rememberConversationSizeInfo delegates to, so this exercises the production wiring on the JVM
 * without Compose.
 */
class ConversationSizeInfoTest {

    private fun assistantWithUsage(promptTokens: Int): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("reply")),
            usage = TokenUsage(
                promptTokens = promptTokens,
                completionTokens = 0,
                totalTokens = promptTokens,
            ),
        )

    private fun nodes(count: Int): List<UIMessage> =
        List(count) { UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("m$it"))) }

    // ---- P5: warning and the REAL auto-compact trigger agree on the over-threshold signal ----

    @Test
    fun `P5 warning agrees with the real auto-compact trigger on identical inputs`() {
        runBlocking {
            // window options mirror the trigger's generators.
            val models = listOf(
                Model(modelId = "gpt-4o"),          // 128k
                Model(modelId = "claude-opus-4-8"), // 200k
                Model(modelId = "gemini-2.5-pro"),  // 1M
                Model(modelId = "unknown-xyz"),     // default 128k
            )
            checkAll(
                Arb.element(models),
                Arb.int(0..2_000_000),
                Arb.int(0..200_000),                // assistant maxTokens (0 -> default reserve)
                // assistant autoCompactThreshold (the soft knob); the real persisted value is a finite
                // fraction, so exclude the NaN/Infinity edge cases kotest injects.
                Arb.float(0.05f, 1.0f).filter { it.isFinite() },
            ) { model, promptTokens, maxTokens, autoCompactThreshold ->
                val messages = listOf(assistantWithUsage(promptTokens))
                val info = computeConversationSizeInfo(
                    nodeCount = 1,
                    messages = messages,
                    model = model,
                    assistantMaxTokens = maxTokens,
                    autoCompactThreshold = autoCompactThreshold,
                )

                // Recompute the REAL auto-compact trigger's view of the same conversation: it uses the
                // assistant's autoCompactThreshold (NOT the warning fraction) and the SAME reserve. The
                // warning fires at a fraction <= autoCompactThreshold, so whenever the real trigger is
                // over threshold the warning must also be over threshold (single source of truth, P5).
                val window = ModelRegistry.getContextWindowForModel(model)
                val triggerPressure = tokenPressure(
                    contextTokens = contextTokens(messages),
                    window = window,
                    thresholdFraction = autoCompactThreshold,
                    reserveOutput = resolveReserveOutput(maxTokens),
                )
                val triggerOver = shouldAutoCompact(
                    enabled = true,
                    hasCompressibleHistory = true,
                    breakerTripped = false,
                    pressure = triggerPressure,
                )

                // Direction that matters: the warning must never be SILENT while the trigger fires.
                // (warningFraction <= autoCompactThreshold guarantees this; the converse is allowed --
                // the warning may fire a touch earlier on the soft fraction, which is its job.)
                if (triggerOver) {
                    assertTrue(
                        "warning must not be silent when the real auto-compact trigger fires " +
                            "(window=$window tokens=$promptTokens maxTokens=$maxTokens thr=$autoCompactThreshold)",
                        info.exceedTokenThreshold,
                    )
                }
            }
        }
    }

    @Test
    fun `warningFraction is never greater than the auto-compact threshold`() {
        runBlocking {
            // Real domain of autoCompactThreshold is a finite fraction; exclude kotest's NaN/Inf edges.
            checkAll(Arb.float(0.05f, 1.0f).filter { it.isFinite() }) { threshold ->
                assertTrue(
                    "warningFraction must satisfy the design invariant warning <= autoCompact (thr=$threshold)",
                    warningFraction(threshold) <= threshold,
                )
            }
        }
        // Null threshold (no chat assistant) falls back to the cap, still a sane <= 1.0 fraction.
        assertEquals(CONVERSATION_SIZE_WARNING_FRACTION_CAP, warningFraction(null), 0.0f)
        // Defensive: a corrupted non-finite persisted value must not yield a NaN fraction (which would
        // make softOver always false and silently disable the warning); it falls back to the cap.
        assertEquals(CONVERSATION_SIZE_WARNING_FRACTION_CAP, warningFraction(Float.NaN), 0.0f)
    }

    @Test
    fun `P5 small window with high maxTokens still agrees on hard guard`() {
        // The exact case the reviewer flagged: a small window + large maxTokens lowers allowedTokens, so
        // hardOver can fire below the soft fraction. The warning must use the same reserve as the trigger
        // so both see the same hardOver. Context chosen to land in the (allowedTokens, softLimit) gap.
        val model = Model(modelId = "gpt-4o") // 128k
        val maxTokens = 64_000                // -> reserve capped at 20k
        val threshold = 0.9f                  // high soft knob so the gap exists below it
        val window = ModelRegistry.getContextWindowForModel(model)
        val allowed = window - resolveReserveOutput(maxTokens) - 13_000 // SAFETY_BUFFER
        val softLimit = (window * warningFraction(threshold)).toInt()
        // Sanity: with a big reserve the hard guard is below the soft line, so a gap exists.
        assertTrue("expected allowed < softLimit for this case", allowed < softLimit)
        val gapTokens = allowed + 1 // over hard guard, under soft line

        val info = computeConversationSizeInfo(
            nodeCount = 1,
            messages = listOf(assistantWithUsage(gapTokens)),
            model = model,
            assistantMaxTokens = maxTokens,
            autoCompactThreshold = threshold,
        )
        val p = tokenPressure(
            contextTokens = contextTokens(listOf(assistantWithUsage(gapTokens))),
            window = window,
            thresholdFraction = warningFraction(threshold),
            reserveOutput = resolveReserveOutput(maxTokens),
        )
        assertTrue("trigger would fire on the hard guard here", p.hardOver && !p.softOver)
        assertTrue("warning must agree the conversation is over threshold", info.exceedTokenThreshold)
    }

    // ---- showWarning requires BOTH thresholds (rare, high-confidence) ----

    @Test
    fun `showWarning requires both node count and token thresholds`() {
        val smallWindowModel = Model(modelId = "gpt-4o") // 128k
        val threshold = 0.8f                              // default soft knob
        val window = ModelRegistry.getContextWindowForModel(smallWindowModel)
        val overTokens = (window * warningFraction(threshold)).toInt() + 5_000

        // Token over but few nodes -> no warning.
        val tokenOverFewNodes = computeConversationSizeInfo(
            nodeCount = 1,
            messages = listOf(assistantWithUsage(overTokens)),
            model = smallWindowModel,
            assistantMaxTokens = null,
            autoCompactThreshold = threshold,
        )
        assertTrue(tokenOverFewNodes.exceedTokenThreshold)
        assertFalse(tokenOverFewNodes.exceedNodeCountThreshold)
        assertFalse(tokenOverFewNodes.showWarning)

        // Many nodes but tokens under -> no warning.
        val nodeOverTokensUnder = computeConversationSizeInfo(
            nodeCount = MESSAGE_NODE_WARNING_THRESHOLD + 1,
            messages = listOf(assistantWithUsage(10)),
            model = smallWindowModel,
            assistantMaxTokens = null,
            autoCompactThreshold = threshold,
        )
        assertTrue(nodeOverTokensUnder.exceedNodeCountThreshold)
        assertFalse(nodeOverTokensUnder.exceedTokenThreshold)
        assertFalse(nodeOverTokensUnder.showWarning)

        // Both over -> warning.
        val bothOver = computeConversationSizeInfo(
            nodeCount = MESSAGE_NODE_WARNING_THRESHOLD + 1,
            messages = listOf(assistantWithUsage(overTokens)),
            model = smallWindowModel,
            assistantMaxTokens = null,
            autoCompactThreshold = threshold,
        )
        assertTrue(bothOver.showWarning)
    }

    // ---- 300k constant is gone: warning is model-relative ----

    @Test
    fun `warning is model relative not a fixed 300k token count`() {
        // 300k tokens: under a 1M window this is NOT high pressure (0.3 < 0.8 fraction), but on a 128k
        // window it is well over. The old hardcoded 300k threshold could not tell these apart.
        val tokens = 300_000
        val threshold = 0.8f
        val bigWindow = Model(modelId = "gemini-2.5-pro") // 1M
        val smallWindow = Model(modelId = "gpt-4o")       // 128k

        val onBig = computeConversationSizeInfo(1, listOf(assistantWithUsage(tokens)), bigWindow, null, threshold)
        val onSmall = computeConversationSizeInfo(1, listOf(assistantWithUsage(tokens)), smallWindow, null, threshold)

        assertFalse("300k tokens is low pressure on a 1M window", onBig.exceedTokenThreshold)
        assertTrue("300k tokens is over a 128k window", onSmall.exceedTokenThreshold)
    }

    @Test
    fun `null model uses the conservative default window`() {
        val info = computeConversationSizeInfo(
            nodeCount = 1,
            messages = listOf(assistantWithUsage(10)),
            model = null,
            assistantMaxTokens = null,
            autoCompactThreshold = 0.8f,
        )
        assertEquals(ModelRegistry.DEFAULT_CONTEXT_WINDOW, info.contextWindow)
    }
}
