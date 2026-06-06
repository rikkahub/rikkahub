package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the loop guard's two non-obvious rules:
 *  - Observation tools (read_window_tree / take_screenshot) run in an act-observe cycle, so an
 *    intervening ACTION resets their repeat count: identical observes around real actions must
 *    NOT trip. This is the Aurora-Store regression: click_node -> read_window_tree repeated.
 *  - Two observers merely alternating on a frozen screen (no action) still trip, preserving the
 *    token-drain protection.
 *  - Side-effecting tools count every identical call in the turn regardless of what ran between.
 *  - The freshness-TTL bypass treats a stale-enough identical real-time read as a refresh.
 */
class LoopGuardTest {

    private fun call(name: String, input: String = "{}", epochMs: Long = 0L) =
        PriorToolCall(name, "$name::$input", epochMs)

    private fun evaluate(prior: List<PriorToolCall>, name: String, input: String = "{}", nowMs: Long = 0L) =
        LoopGuard.evaluate(prior, name, "$name::$input", nowMs)

    @Test
    fun observation_repeatsAroundActions_doNotTrip() {
        // click, read, click, read, click, read ... the read args are identical every time but
        // an action ran before each one, so none of them is a loop.
        val prior = listOf(
            call("click_node", "{\"node_id\":\"1:2\"}"),
            call("read_window_tree"),
            call("click_node", "{\"node_id\":\"1:3\"}"),
            call("read_window_tree"),
            call("global_action", "{\"action\":\"back\"}"),
            call("read_window_tree"),
        )
        val decision = evaluate(prior, "read_window_tree")
        assertFalse("act-observe cycle must not be a loop", decision.block)
    }

    @Test
    fun observation_onlyCountsRepeatsSinceLastAction() {
        // Three reads before the action don't count; only the single read after it does.
        val prior = listOf(
            call("read_window_tree"),
            call("read_window_tree"),
            call("read_window_tree"),
            call("click_node", "{\"node_id\":\"1:2\"}"),
            call("read_window_tree"),
        )
        val decision = evaluate(prior, "read_window_tree")
        assertFalse(decision.block)
        assertEquals(1, decision.priorOccurrences)
    }

    @Test
    fun observation_repeatedWithNoAction_trips() {
        // No action ever ran; the model is hammering the same observation on a frozen screen.
        // Use a fresh nowMs vs epoch 0 within the 5s TTL so the bypass does not fire.
        val prior = List(3) { call("take_screenshot", epochMs = 1_000L) }
        val decision = evaluate(prior, "take_screenshot", nowMs = 2_000L)
        assertTrue(decision.block)
    }

    @Test
    fun alternatingObservers_onFrozenScreen_stillTrip() {
        // screenshot/window_tree alternating with NO action between: both are observers so
        // neither resets the other, and the screenshot repeats accumulate to the threshold.
        val prior = listOf(
            call("take_screenshot", epochMs = 1_000L),
            call("read_window_tree", epochMs = 1_000L),
            call("take_screenshot", epochMs = 1_000L),
            call("read_window_tree", epochMs = 1_000L),
            call("take_screenshot", epochMs = 1_000L),
        )
        val decision = evaluate(prior, "take_screenshot", nowMs = 2_000L)
        assertTrue("alternating observers must still trip", decision.block)
    }

    @Test
    fun sideEffectingTool_countsEveryIdenticalCall_despiteActionsBetween() {
        // Re-sending the same message 3x is a loop even with other tools between; observation
        // reset does not apply to side-effecting tools.
        val sig = "{\"text\":\"hi\"}"
        val prior = listOf(
            call("telegram_send_message", sig),
            call("read_window_tree"),
            call("telegram_send_message", sig),
            call("click_node", "{\"node_id\":\"1:2\"}"),
            call("telegram_send_message", sig),
        )
        val decision = evaluate(prior, "telegram_send_message", sig)
        assertTrue(decision.block)
    }

    @Test
    fun freshnessTtlBypass_letsStaleRealtimeReadThrough() {
        // Three identical battery reads, but the most recent is older than the 30s TTL, so the
        // next one is a legitimate refresh, not a loop.
        val prior = List(3) { call("get_battery_status", epochMs = 0L) }
        val decision = evaluate(prior, "get_battery_status", nowMs = 31_000L)
        assertFalse(decision.block)
    }

    @Test
    fun freshnessTtlBypass_doesNotFireWithinTtl() {
        val prior = List(3) { call("get_battery_status", epochMs = 0L) }
        val decision = evaluate(prior, "get_battery_status", nowMs = 5_000L)
        assertTrue(decision.block)
    }
}
