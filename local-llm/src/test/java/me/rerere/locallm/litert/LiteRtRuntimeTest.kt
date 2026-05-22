package me.rerere.locallm.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-decision-function tests for [LiteRtRuntime] — the two companion-object helpers
 * are load-bearing on every model load but were untested in 22A. Closes that gap.
 *
 * `planTurns`: governs whether the warm Conversation's KV cache can be reused for the
 * next turn (Warm) or whether we must rebuild it cold. A wrong answer can never produce
 * incorrect output (the cold path is always correct), but it can either cost a missed
 * optimisation OR cost a correctness bug if the warm path is taken when it shouldn't.
 *
 * `isVisionExecutorError`: governs the vision-encoder text-only fallback path. A false
 * negative means the user sees a hard "engine could not load" instead of a working
 * text-only Gemma 4; a false positive would mis-route an unrelated load failure into
 * the text-only retry and waste an init attempt. Both cost user-visible failures.
 */
class LiteRtRuntimeTest {

    // ---- planTurns -----------------------------------------------------------------

    private val u1 = turnSignature(ROLE_USER, "hello")
    private val a1 = turnSignature(ROLE_ASSISTANT, "hi there")
    private val u2 = turnSignature(ROLE_USER, "what's up")
    private val a2 = turnSignature(ROLE_ASSISTANT, "not much")
    private val u3 = turnSignature(ROLE_USER, "tell me a joke")

    @Test
    fun `planTurns returns Cold when processed is empty (first turn ever)`() {
        val plan = LiteRtRuntime.planTurns(
            processed = emptyList(),
            historySignatures = listOf(u1),
            hasMedia = false,
        )
        assertEquals(TurnPlan.Cold, plan)
    }

    @Test
    fun `planTurns returns Cold when caller has media inputs`() {
        // Media kills the warm path because there's no way to attach per-call images onto
        // a prior turn in the KV cache.
        val plan = LiteRtRuntime.planTurns(
            processed = listOf(u1, a1),
            historySignatures = listOf(u1, a1, u2),
            hasMedia = true,
        )
        assertEquals(TurnPlan.Cold, plan)
    }

    @Test
    fun `planTurns returns Warm for a clean single-turn append on top of prior history`() {
        // The prior turn finished, the runtime recorded [u1, assistant_reply]; the caller
        // now wants to send [u1, assistant_reply, u2] — exactly one new turn appended.
        val plan = LiteRtRuntime.planTurns(
            processed = listOf(u1, a1),
            historySignatures = listOf(u1, a1, u2),
            hasMedia = false,
        )
        assertTrue("expected Warm got $plan", plan is TurnPlan.Warm)
        assertEquals(2, (plan as TurnPlan.Warm).sendFromIndex)
    }

    @Test
    fun `planTurns returns Cold when more than one turn was appended`() {
        // Two new turns at once (e.g. resumed conversation, batched input) — can't reuse.
        val plan = LiteRtRuntime.planTurns(
            processed = listOf(u1, a1),
            historySignatures = listOf(u1, a1, u2, a2, u3),
            hasMedia = false,
        )
        assertEquals(TurnPlan.Cold, plan)
    }

    @Test
    fun `planTurns returns Cold when an earlier turn was rewritten`() {
        // User edited turn u1 to u1'. The prefix no longer matches.
        val u1Edited = turnSignature(ROLE_USER, "hello there (edited)")
        val plan = LiteRtRuntime.planTurns(
            processed = listOf(u1, a1),
            historySignatures = listOf(u1Edited, a1, u2),
            hasMedia = false,
        )
        assertEquals(TurnPlan.Cold, plan)
    }

    @Test
    fun `planTurns returns Cold when caller history is shorter than processed (regenerate)`() {
        // User hit "regenerate" — history rolled back. Same prefix but fewer turns than
        // the runtime has consumed. Force cold so the new turn is generated from scratch.
        val plan = LiteRtRuntime.planTurns(
            processed = listOf(u1, a1, u2, a2),
            historySignatures = listOf(u1, a1, u2),
            hasMedia = false,
        )
        assertEquals(TurnPlan.Cold, plan)
    }

    @Test
    fun `planTurns returns Cold when caller history exactly equals processed (no new turn)`() {
        // Defensive: a caller that sends the same history without appending should not
        // trigger a "warm" path with sendFromIndex == processed.size, which would IndexError.
        val plan = LiteRtRuntime.planTurns(
            processed = listOf(u1, a1),
            historySignatures = listOf(u1, a1),
            hasMedia = false,
        )
        assertEquals(TurnPlan.Cold, plan)
    }

    @Test
    fun `turnSignature is stable for identical role and content`() {
        assertEquals(
            turnSignature(ROLE_USER, "hello"),
            turnSignature(ROLE_USER, "hello"),
        )
    }

    @Test
    fun `turnSignature differs when role differs`() {
        assertFalse(turnSignature(ROLE_USER, "hi") == turnSignature(ROLE_ASSISTANT, "hi"))
    }

    @Test
    fun `turnSignature differs when content differs`() {
        assertFalse(turnSignature(ROLE_USER, "a") == turnSignature(ROLE_USER, "b"))
    }

    // ---- isVisionExecutorError -----------------------------------------------------

    @Test
    fun `isVisionExecutorError matches the canonical SDK file path string`() {
        // The full native error text the SDK throws on the broken-vision device.
        val msg = "Failed to create engine: INTERNAL: ERROR: " +
            "[third_party/odml/litert_lm/runtime/executor/vision_litert_compiled_model_executor.cc:273]"
        assertTrue(LiteRtRuntime.isVisionExecutorError(msg))
    }

    @Test
    fun `isVisionExecutorError matches the truncated form`() {
        // Some SDK builds truncate the file path before `_compiled_model`. Match the
        // shorter `vision_litert` prefix too.
        val msg = "vision_litert error during init"
        assertTrue(LiteRtRuntime.isVisionExecutorError(msg))
    }

    @Test
    fun `isVisionExecutorError matches CreateSharedMemoryManager UNIMPLEMENTED (upstream root cause)`() {
        // The underlying OpenGL fallback stub from upstream LiteRT-LM #2292.
        val msg = "UNIMPLEMENTED: CreateSharedMemoryManager is not implemented."
        assertTrue(LiteRtRuntime.isVisionExecutorError(msg))
    }

    @Test
    fun `isVisionExecutorError matches gpu_backend_opengl source path`() {
        val msg = "third_party/odml/litert/ml_drift/delegate/gpu_backend_opengl.cc:169"
        assertTrue(LiteRtRuntime.isVisionExecutorError(msg))
    }

    @Test
    fun `isVisionExecutorError is case-insensitive`() {
        assertTrue(LiteRtRuntime.isVisionExecutorError("Vision_LiteRT_Compiled_Model_Executor blew up"))
        assertTrue(LiteRtRuntime.isVisionExecutorError("createSHAREDmemorymanager"))
    }

    @Test
    fun `isVisionExecutorError returns false for unrelated load failures`() {
        // Common false-positive candidates.
        val unrelated = listOf(
            "Invalid magic number while reading model file",
            "No KV cache inputs found",
            "FAILED_PRECONDITION: tokenizer section missing",
            "Input token ids are too long. Exceeding the maximum: 5000 >= 4096",
            "Status Code: 3. Message: tokenizer mismatch",
            "OutOfMemoryError",
        )
        for (msg in unrelated) {
            assertFalse(
                "isVisionExecutorError should not match: $msg",
                LiteRtRuntime.isVisionExecutorError(msg),
            )
        }
    }

    @Test
    fun `isVisionExecutorError handles empty input`() {
        assertFalse(LiteRtRuntime.isVisionExecutorError(""))
    }
}
