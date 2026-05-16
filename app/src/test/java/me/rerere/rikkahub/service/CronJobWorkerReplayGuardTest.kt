package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the replay-guard bug where a manual fire (trigger_job_now) stamped the FUTURE
 * scheduled slot into its run row, causing the next regular fire at that slot to be
 * suppressed as `process_killed_replay`.
 *
 * Live reproduction on `beta` 2026-05-16:
 *   - Job "Daily Weather Brief" created May 15 3:27:06 PM Riyadh (cron `0 8 * * *`).
 *     `nextRunAtMs` was correctly set to May 16 8:00:00 AM.
 *   - The LLM immediately auto-fired `trigger_job_now` (30s later) as a test run.
 *     The manual fire stamped `scheduledAtMs = May 16 8:00 AM` and succeeded.
 *   - May 16 8:00 AM the real cron fired. Replay guard saw the manual row with the
 *     same `scheduledAtMs` and suppressed today's run as `process_killed_replay`.
 *   - User did not get their weather brief.
 *
 * The fix is twofold and both halves are tested here:
 *   1. [computeRunSlot]: manual fires stamp nowMs, not job.nextRunAtMs.
 *   2. [shouldSuppressAsReplay]: requires priorRow.startedAtMs within REPLAY_WINDOW_MS
 *      of nowMs, so a stale (>10 min old) prior row can never suppress a fresh fire.
 */
class CronJobWorkerReplayGuardTest {

    private fun row(
        scheduledAtMs: Long,
        startedAtMs: Long,
        outcome: String = "success",
    ) = ScheduledJobRunEntity(
        id = "row-${startedAtMs}",
        jobId = "job-1",
        mode = "llm",
        scheduledAtMs = scheduledAtMs,
        startedAtMs = startedAtMs,
        finishedAtMs = startedAtMs + 1_000L,
        outcome = outcome,
        conversationId = null,
        errorMessage = null,
    )

    // ---------- computeRunSlot ----------

    @Test
    fun `manual fire stamps nowMs even when nextRunAtMs points to a future slot`() {
        val now = 1_000L
        val futureSlot = 1_000_000L
        assertEquals(
            "manual fires aren't bound to a scheduled slot — they happened at nowMs",
            now,
            computeRunSlot(isManual = true, jobNextRunAtMs = futureSlot, nowMs = now),
        )
    }

    @Test
    fun `natural fire stamps the planned slot so a WorkManager replay can be detected`() {
        val now = 999_999L
        val slot = 1_000_000L
        assertEquals(slot, computeRunSlot(isManual = false, jobNextRunAtMs = slot, nowMs = now))
    }

    @Test
    fun `natural fire with null nextRunAtMs falls back to nowMs`() {
        val now = 42L
        assertEquals(now, computeRunSlot(isManual = false, jobNextRunAtMs = null, nowMs = now))
    }

    // ---------- shouldSuppressAsReplay ----------

    @Test
    fun `no prior row never suppresses`() {
        assertFalse(shouldSuppressAsReplay(priorRow = null, slotMs = 100L, nowMs = 200L))
    }

    @Test
    fun `different scheduledAtMs never suppresses (subsequent natural tick)`() {
        val prior = row(scheduledAtMs = 100L, startedAtMs = 100L)
        assertFalse(shouldSuppressAsReplay(prior, slotMs = 200L, nowMs = 200L))
    }

    @Test
    fun `matching scheduledAtMs and recent startedAtMs suppresses (genuine replay)`() {
        // A worker crashed mid-execute; WorkManager replays it ~30s later.
        val prior = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L)
        assertTrue(shouldSuppressAsReplay(prior, slotMs = 1_000L, nowMs = 1_000L + 30_000L))
    }

    @Test
    fun `matching scheduledAtMs at the window edge still suppresses`() {
        val prior = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L)
        // nowMs is exactly REPLAY_WINDOW_MS after the prior startedAtMs — still in window.
        assertTrue(shouldSuppressAsReplay(prior, slotMs = 1_000L, nowMs = 1_000L + REPLAY_WINDOW_MS))
    }

    @Test
    fun `matching scheduledAtMs but stale startedAtMs does NOT suppress — the real bug`() {
        // Manual fire from 16h ago stamped scheduledAtMs = future slot. Today's natural
        // fire at that slot must not be suppressed by the stale row.
        val sixteenHoursMs = 16L * 60 * 60 * 1_000L
        val prior = row(scheduledAtMs = 1_778_907_600_000L, startedAtMs = 1_778_848_056_695L)
        val nowMs = prior.startedAtMs + sixteenHoursMs
        assertFalse(
            "a 16h-old row with matching slot is not a WorkManager replay",
            shouldSuppressAsReplay(prior, slotMs = prior.scheduledAtMs, nowMs = nowMs),
        )
    }

    @Test
    fun `concurrent_skip prior row never suppresses`() {
        val prior = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L, outcome = "concurrent_skip")
        assertFalse(shouldSuppressAsReplay(prior, slotMs = 1_000L, nowMs = 1_001L))
    }

    @Test
    fun `skipped_catchup prior row never suppresses`() {
        val prior = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L, outcome = "skipped_catchup")
        assertFalse(shouldSuppressAsReplay(prior, slotMs = 1_000L, nowMs = 1_001L))
    }

    // ---------- composition: the actual bug end-to-end (pure-function form) ----------

    @Test
    fun `manual fire at creation does not poison the next natural fire`() {
        // Replays the live Daily Weather Brief sequence using only the pure helpers.
        val createdAtMs = 1_778_848_026_977L                  // May 15 3:27:06 PM Riyadh
        val plannedSlot = 1_778_907_600_000L                  // May 16 8:00:00 AM Riyadh

        // 1. LLM calls trigger_job_now 30s after creation. Manual fire stamps nowMs.
        val manualNow = createdAtMs + 30_000L
        val manualSlot = computeRunSlot(isManual = true, jobNextRunAtMs = plannedSlot, nowMs = manualNow)
        assertEquals("manual fire must NOT stamp the future slot", manualNow, manualSlot)
        val manualRow = row(scheduledAtMs = manualSlot, startedAtMs = manualNow)

        // 2. May 16 8:00 AM — natural fire. Stamps the planned slot. Guard sees the
        //    manual row but its scheduledAtMs is different (manualNow != plannedSlot)
        //    AND it's 16h old. Both halves of the fix must hold; either alone suffices.
        val naturalNow = plannedSlot
        val naturalSlot = computeRunSlot(isManual = false, jobNextRunAtMs = plannedSlot, nowMs = naturalNow)
        assertEquals(plannedSlot, naturalSlot)
        assertFalse(
            "natural fire must not be suppressed by a stale manual row",
            shouldSuppressAsReplay(manualRow, slotMs = naturalSlot, nowMs = naturalNow),
        )
    }
}
