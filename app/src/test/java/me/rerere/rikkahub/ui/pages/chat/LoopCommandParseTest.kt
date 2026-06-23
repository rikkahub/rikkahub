package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.runtime.schedule.RecurrenceUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [parseLoopCommand] (#364 slice 2): the PURE parse of a `/loop` argument into a
 * [LoopCommand]. The invariants pinned: clear vs schedule vs usage classification, interval-token
 * extraction, and the 15-minute floor (a durable schedule cannot fire faster than WorkManager's
 * minimum, so any sub-floor interval rounds UP).
 */
class LoopCommandParseTest {

    @Test
    fun `empty or clear is a Clear command`() {
        assertEquals(LoopCommand.Clear, parseLoopCommand(""))
        assertEquals(LoopCommand.Clear, parseLoopCommand("   "))
        assertEquals(LoopCommand.Clear, parseLoopCommand("clear"))
        assertEquals(LoopCommand.Clear, parseLoopCommand("CLEAR"))
    }

    @Test
    fun `a leading interval token splits off the prompt`() {
        assertEquals(
            LoopCommand.Schedule(30, RecurrenceUnit.MINUTES, "check the build"),
            parseLoopCommand("30m check the build"),
        )
        assertEquals(
            LoopCommand.Schedule(2, RecurrenceUnit.HOURS, "summarize new PRs"),
            parseLoopCommand("2h summarize new PRs"),
        )
        assertEquals(
            LoopCommand.Schedule(1, RecurrenceUnit.DAYS, "daily standup"),
            parseLoopCommand("1d daily standup"),
        )
    }

    @Test
    fun `no interval token defaults to the 15-minute floor and uses the whole arg as prompt`() {
        assertEquals(
            LoopCommand.Schedule(MIN_LOOP_MINUTES, RecurrenceUnit.MINUTES, "keep watching the deploy"),
            parseLoopCommand("keep watching the deploy"),
        )
    }

    @Test
    fun `a sub-floor minute interval rounds up to the floor`() {
        assertEquals(
            LoopCommand.Schedule(MIN_LOOP_MINUTES, RecurrenceUnit.MINUTES, "ping"),
            parseLoopCommand("1m ping"),
        )
        // exactly the floor passes through
        assertEquals(
            LoopCommand.Schedule(15, RecurrenceUnit.MINUTES, "ping"),
            parseLoopCommand("15m ping"),
        )
        // above the floor passes through
        assertEquals(
            LoopCommand.Schedule(45, RecurrenceUnit.MINUTES, "ping"),
            parseLoopCommand("45m ping"),
        )
    }

    @Test
    fun `seconds round up to whole minutes then to the floor`() {
        assertEquals(
            LoopCommand.Schedule(MIN_LOOP_MINUTES, RecurrenceUnit.MINUTES, "tick"),
            parseLoopCommand("30s tick"),
        )
    }

    @Test
    fun `an interval with no prompt is Usage`() {
        assertEquals(LoopCommand.Usage, parseLoopCommand("30m"))
        assertEquals(LoopCommand.Usage, parseLoopCommand("2h   "))
    }

    @Test
    fun `a non-interval first token is treated as part of the prompt at the floor`() {
        // "check" is not an interval token, so the whole thing is the prompt at the floor cadence.
        assertEquals(
            LoopCommand.Schedule(MIN_LOOP_MINUTES, RecurrenceUnit.MINUTES, "check every PR"),
            parseLoopCommand("check every PR"),
        )
    }
}
