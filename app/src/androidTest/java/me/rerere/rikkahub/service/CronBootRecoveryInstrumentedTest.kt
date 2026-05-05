package me.rerere.rikkahub.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Instrumented tests for boot-time stranded-row sweep logic.
 *
 * CronBootReceiver.sweepStrandedRunRows() is private, so we cannot call it directly
 * from a subclass. Instead these tests exercise the two primitives that the sweep
 * relies on:
 *   1. ScheduledJobRunDao.getStranded(cutoff) — returns rows where
 *      finishedAtMs IS NULL AND startedAtMs < cutoff.
 *   2. ScheduledJobRunDao.update() — flips outcome + finishedAtMs.
 *
 * This is equivalent to what the receiver does; the receiver itself is just
 * a thin orchestrator over those two DAO calls plus a notification post.
 *
 * Calling onReceive(context, Intent(BOOT_COMPLETED)) is skipped because it
 * requires full Koin DI (ChatService, TelegramBotPreferences, etc.) bootstrapped
 * on the instrumented process — that is tested implicitly on-device when the user
 * reboots with the app installed.
 *
 * TODO: covered by audit/follow-up — wire up a minimal KoinApplication in
 *       androidTest and call receiver.onReceive() end-to-end.
 *
 * Catchup-policy-on-boot test is skipped because it requires WorkManager
 * TestDriver to inspect the enqueued work requests.
 * TODO: covered by audit/follow-up once WorkManager TestDriver scaffolding lands.
 */
@OptIn(ExperimentalUuidApi::class)
@RunWith(AndroidJUnit4::class)
class CronBootRecoveryInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var jobRepo: ScheduledJobRepository
    private lateinit var runRepo: ScheduledJobRunRepository

    private val fakeAssistantId = Uuid.random().toString()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        jobRepo = ScheduledJobRepository(db.scheduledJobDao())
        runRepo = ScheduledJobRunRepository(db.scheduledJobRunDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Helper — insert a minimal job so getById() in the sweep doesn't return null.
    // -------------------------------------------------------------------------
    private suspend fun insertJob(jobId: String) {
        jobRepo.upsert(
            ScheduledJobEntity(
                id = jobId,
                name = "sweep-test-job",
                assistantId = fakeAssistantId,
                scheduleType = "cron",
                cronExpression = "0 * * * *",
                mode = "direct",
                actionsJson = """[{"tool":"noop","args":{}}]""",
                enabled = true,
                createdAtMs = System.currentTimeMillis(),
            )
        )
    }

    // -------------------------------------------------------------------------
    // Replicates CronBootReceiver.sweepStrandedRunRows() against the in-memory DB.
    // Uses a configurable cutoff to allow test-controlled staleness windows.
    // -------------------------------------------------------------------------
    private suspend fun sweepStranded(cutoffMs: Long) {
        val stranded = runRepo.getStranded(stalenessMs = cutoffMs)
        for (row in stranded) {
            runRepo.update(
                row.copy(
                    finishedAtMs = System.currentTimeMillis(),
                    outcome = "process_killed_replay",
                    errorMessage = "worker terminated mid-execute",
                )
            )
        }
    }

    // =========================================================================
    // Test 1 — stranded row (started_at_ms > 30 min ago, no finishedAtMs)
    //          gets flipped to process_killed_replay after sweep.
    // =========================================================================
    @Test
    fun strandedRunRow_getsFlippedToProcessKilledReplay() = runBlocking {
        val jobId = Uuid.random().toString()
        insertJob(jobId)

        // Insert a run row that looks like the worker was killed mid-execute:
        // startedAtMs is 60 minutes in the past, finishedAtMs is null.
        val strandedStartMs = System.currentTimeMillis() - 60L * 60_000L
        val rowId = Uuid.random().toString()
        runRepo.insert(
            ScheduledJobRunEntity(
                id = rowId,
                jobId = jobId,
                mode = "direct",
                scheduledAtMs = strandedStartMs,
                startedAtMs = strandedStartMs,
                finishedAtMs = null,          // never finished — the telltale sign
                outcome = "success",           // optimistic row not yet updated
                conversationId = null,
                errorMessage = null,
            )
        )

        // cutoff = now - 30 min; the row started 60 min ago → should be stranded
        val cutoff = System.currentTimeMillis() - 30L * 60_000L
        sweepStranded(cutoffMs = cutoff)

        val updated = runRepo.getRecent(jobId, 1).firstOrNull()
        assertNotNull("Row should still exist after sweep", updated)
        assertEquals("Outcome should be process_killed_replay", "process_killed_replay", updated!!.outcome)
        assertNotNull("finishedAtMs should now be set", updated.finishedAtMs)
        assertEquals(
            "errorMessage should indicate worker termination",
            "worker terminated mid-execute",
            updated.errorMessage,
        )
    }

    // =========================================================================
    // Test 2 — row that started < 30 min ago (still within grace window) is
    //          NOT swept — it may still be running legitimately.
    // =========================================================================
    @Test
    fun recentRunRow_isNotSwept() = runBlocking {
        val jobId = Uuid.random().toString()
        insertJob(jobId)

        // startedAtMs is only 5 minutes ago — inside the 30-min grace window.
        val recentStartMs = System.currentTimeMillis() - 5L * 60_000L
        val rowId = Uuid.random().toString()
        runRepo.insert(
            ScheduledJobRunEntity(
                id = rowId,
                jobId = jobId,
                mode = "direct",
                scheduledAtMs = recentStartMs,
                startedAtMs = recentStartMs,
                finishedAtMs = null,
                outcome = "success",      // optimistic / in-flight
                conversationId = null,
                errorMessage = null,
            )
        )

        val cutoff = System.currentTimeMillis() - 30L * 60_000L
        sweepStranded(cutoffMs = cutoff)

        val afterSweep = runRepo.getRecent(jobId, 1).firstOrNull()
        assertNotNull("Row should still exist", afterSweep)
        // Outcome should still be the optimistic 'success' — sweep must not touch it.
        assertEquals("Recent in-flight row should not be swept", "success", afterSweep!!.outcome)
        assertTrue("finishedAtMs should remain null", afterSweep.finishedAtMs == null)
    }

    // =========================================================================
    // Test 3 — already-finished row (finishedAtMs != null) is not swept,
    //          even if startedAtMs is old.
    // =========================================================================
    @Test
    fun finishedRunRow_isNotSwept() = runBlocking {
        val jobId = Uuid.random().toString()
        insertJob(jobId)

        val oldStartMs = System.currentTimeMillis() - 120L * 60_000L  // 2 hours ago
        val rowId = Uuid.random().toString()
        runRepo.insert(
            ScheduledJobRunEntity(
                id = rowId,
                jobId = jobId,
                mode = "direct",
                scheduledAtMs = oldStartMs,
                startedAtMs = oldStartMs,
                finishedAtMs = oldStartMs + 5_000L,    // completed 5 seconds after start
                outcome = "success",
                conversationId = null,
                errorMessage = null,
            )
        )

        val cutoff = System.currentTimeMillis() - 30L * 60_000L
        sweepStranded(cutoffMs = cutoff)

        val afterSweep = runRepo.getRecent(jobId, 1).firstOrNull()
        assertNotNull("Row should still exist", afterSweep)
        assertEquals("Finished row outcome should be unchanged", "success", afterSweep!!.outcome)
    }

    // =========================================================================
    // Test 4 — multiple stranded rows for different jobs all swept correctly.
    // =========================================================================
    @Test
    fun multipleStrandedRows_allSwept() = runBlocking {
        val jobId1 = Uuid.random().toString()
        val jobId2 = Uuid.random().toString()
        insertJob(jobId1)
        insertJob(jobId2)

        val oldStartMs = System.currentTimeMillis() - 90L * 60_000L   // 90 min ago

        repeat(2) { i ->
            runRepo.insert(
                ScheduledJobRunEntity(
                    id = Uuid.random().toString(),
                    jobId = if (i == 0) jobId1 else jobId2,
                    mode = "direct",
                    scheduledAtMs = oldStartMs,
                    startedAtMs = oldStartMs,
                    finishedAtMs = null,
                    outcome = "success",
                    conversationId = null,
                    errorMessage = null,
                )
            )
        }

        val cutoff = System.currentTimeMillis() - 30L * 60_000L
        sweepStranded(cutoffMs = cutoff)

        val row1 = runRepo.getRecent(jobId1, 1).firstOrNull()
        val row2 = runRepo.getRecent(jobId2, 1).firstOrNull()

        assertNotNull(row1)
        assertNotNull(row2)
        assertEquals("process_killed_replay", row1!!.outcome)
        assertEquals("process_killed_replay", row2!!.outcome)
    }

    // TODO: catchup-policy-on-boot test — skipped because verifying the
    //       enqueued WorkManager requests requires WorkManager TestDriver.
    //       Covered by audit/follow-up once WorkManager TestDriver scaffolding lands.
}
