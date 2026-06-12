package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review mustFix #2 regression: title/suggestion jobs used to launch inside the completion
 * (handleMessageComplete.onSuccess) — BEFORE the Stop-hook continuation appended its turn — so a
 * stale pre-continuation job set raced the post-continuation one and could overwrite the final
 * conversation's title/suggestions. The sequencing contract under test: the continuation strictly
 * precedes a SINGLE turn-end job launch, and a failed completion launches no jobs at all.
 */
class TurnEndSequencingTest {

    @Test
    fun `turn-end jobs launch exactly once, after the stop-hook continuation`() = runBlocking {
        val calls = mutableListOf<String>()

        sequenceTurnEnd(
            complete = { calls += "complete"; true },
            continueAfterStopHook = { calls += "continuation" },
            launchTurnEndJobs = { calls += "jobs" },
        )

        assertEquals(listOf("complete", "continuation", "jobs"), calls)
    }

    @Test
    fun `failed completion still runs the continuation but launches no jobs`() = runBlocking {
        val calls = mutableListOf<String>()

        sequenceTurnEnd(
            complete = { calls += "complete"; false },
            continueAfterStopHook = { calls += "continuation" },
            launchTurnEndJobs = { calls += "jobs" },
        )

        assertEquals(listOf("complete", "continuation"), calls)
    }
}
