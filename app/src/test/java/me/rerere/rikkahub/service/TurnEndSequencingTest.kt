package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review mustFix #2 regression: title/suggestion jobs used to launch inside the completion
 * (handleMessageComplete.onSuccess) — BEFORE the Stop-hook continuation appended its turn — so a
 * stale pre-continuation job set raced the post-continuation one and could overwrite the final
 * conversation's title/suggestions. The sequencing contract under test: the continuation strictly
 * precedes a SINGLE turn-end job launch, a failed completion launches no jobs at all, and the
 * completion result is handed to the continuation so a step that must not run after a failed turn
 * (the #364 /goal loop) can gate itself on it.
 */
class TurnEndSequencingTest {

    @Test
    fun `turn-end jobs launch exactly once, after the stop-hook continuation`() = runBlocking {
        val calls = mutableListOf<String>()
        var continuationGotCompleted: Boolean? = null

        sequenceTurnEnd(
            complete = { calls += "complete"; true },
            continueAfterStopHook = { completed -> calls += "continuation"; continuationGotCompleted = completed },
            launchTurnEndJobs = { calls += "jobs" },
        )

        assertEquals(listOf("complete", "continuation", "jobs"), calls)
        assertEquals(true, continuationGotCompleted)
    }

    @Test
    fun `failed completion still runs the continuation but launches no jobs`() = runBlocking {
        val calls = mutableListOf<String>()
        var continuationGotCompleted: Boolean? = null

        sequenceTurnEnd(
            complete = { calls += "complete"; false },
            continueAfterStopHook = { completed -> calls += "continuation"; continuationGotCompleted = completed },
            launchTurnEndJobs = { calls += "jobs" },
        )

        assertEquals(listOf("complete", "continuation"), calls)
        assertEquals(false, continuationGotCompleted)
    }
}
