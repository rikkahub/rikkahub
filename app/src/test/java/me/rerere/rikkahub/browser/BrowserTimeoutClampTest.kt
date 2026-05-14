package me.rerere.rikkahub.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin coverage for the user-configurable browser timeout clamping introduced for
 * GitHub issue #4. The settings UI lets the user type any number; [BrowserPreferences]
 * routes every write through these clamp helpers so the persisted value can never fall
 * outside the supported range — the floor keeps a tool from being effectively disabled,
 * the ceiling keeps a runaway task from wedging the Telegram bot / burning battery.
 */
class BrowserTimeoutClampTest {

    @Test
    fun perTool_belowFloor_snapsToMin() {
        assertEquals(
            BrowserToolDefaults.MIN_PER_TOOL_TIMEOUT_MS,
            BrowserToolDefaults.clampPerToolTimeoutMs(0L),
        )
        assertEquals(
            BrowserToolDefaults.MIN_PER_TOOL_TIMEOUT_MS,
            BrowserToolDefaults.clampPerToolTimeoutMs(-5_000L),
        )
        assertEquals(
            BrowserToolDefaults.MIN_PER_TOOL_TIMEOUT_MS,
            BrowserToolDefaults.clampPerToolTimeoutMs(BrowserToolDefaults.MIN_PER_TOOL_TIMEOUT_MS - 1L),
        )
    }

    @Test
    fun perTool_aboveCeiling_snapsToMax() {
        assertEquals(
            BrowserToolDefaults.MAX_PER_TOOL_TIMEOUT_MS,
            BrowserToolDefaults.clampPerToolTimeoutMs(Long.MAX_VALUE),
        )
        assertEquals(
            BrowserToolDefaults.MAX_PER_TOOL_TIMEOUT_MS,
            BrowserToolDefaults.clampPerToolTimeoutMs(BrowserToolDefaults.MAX_PER_TOOL_TIMEOUT_MS + 1L),
        )
    }

    @Test
    fun perTool_inRange_passesThrough() {
        assertEquals(
            BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS,
            BrowserToolDefaults.clampPerToolTimeoutMs(BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS),
        )
        assertEquals(45_000L, BrowserToolDefaults.clampPerToolTimeoutMs(45_000L))
        // Exact bounds are inclusive.
        assertEquals(
            BrowserToolDefaults.MIN_PER_TOOL_TIMEOUT_MS,
            BrowserToolDefaults.clampPerToolTimeoutMs(BrowserToolDefaults.MIN_PER_TOOL_TIMEOUT_MS),
        )
        assertEquals(
            BrowserToolDefaults.MAX_PER_TOOL_TIMEOUT_MS,
            BrowserToolDefaults.clampPerToolTimeoutMs(BrowserToolDefaults.MAX_PER_TOOL_TIMEOUT_MS),
        )
    }

    @Test
    fun singleTask_belowFloor_snapsToMin() {
        assertEquals(
            BrowserToolDefaults.MIN_SINGLE_TASK_TIMEOUT_MS,
            BrowserToolDefaults.clampSingleTaskTimeoutMs(0L),
        )
        assertEquals(
            BrowserToolDefaults.MIN_SINGLE_TASK_TIMEOUT_MS,
            BrowserToolDefaults.clampSingleTaskTimeoutMs(BrowserToolDefaults.MIN_SINGLE_TASK_TIMEOUT_MS - 1L),
        )
    }

    @Test
    fun singleTask_aboveCeiling_snapsToMax() {
        assertEquals(
            BrowserToolDefaults.MAX_SINGLE_TASK_TIMEOUT_MS,
            BrowserToolDefaults.clampSingleTaskTimeoutMs(Long.MAX_VALUE),
        )
        assertEquals(
            BrowserToolDefaults.MAX_SINGLE_TASK_TIMEOUT_MS,
            BrowserToolDefaults.clampSingleTaskTimeoutMs(BrowserToolDefaults.MAX_SINGLE_TASK_TIMEOUT_MS + 1L),
        )
    }

    @Test
    fun singleTask_inRange_passesThrough() {
        assertEquals(
            BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS,
            BrowserToolDefaults.clampSingleTaskTimeoutMs(BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS),
        )
        assertEquals(600_000L, BrowserToolDefaults.clampSingleTaskTimeoutMs(600_000L))
        assertEquals(
            BrowserToolDefaults.MIN_SINGLE_TASK_TIMEOUT_MS,
            BrowserToolDefaults.clampSingleTaskTimeoutMs(BrowserToolDefaults.MIN_SINGLE_TASK_TIMEOUT_MS),
        )
        assertEquals(
            BrowserToolDefaults.MAX_SINGLE_TASK_TIMEOUT_MS,
            BrowserToolDefaults.clampSingleTaskTimeoutMs(BrowserToolDefaults.MAX_SINGLE_TASK_TIMEOUT_MS),
        )
    }

    @Test
    fun defaults_areWithinTheirOwnBounds() {
        // A default that fell outside its range would mean the very first read clamps it —
        // a silent surprise. Pin the invariant.
        assertEquals(
            BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS,
            BrowserToolDefaults.clampPerToolTimeoutMs(BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS),
        )
        assertEquals(
            BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS,
            BrowserToolDefaults.clampSingleTaskTimeoutMs(BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS),
        )
    }
}
