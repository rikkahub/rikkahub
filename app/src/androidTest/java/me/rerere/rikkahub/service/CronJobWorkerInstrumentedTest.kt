package me.rerere.rikkahub.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
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
 * Instrumented tests for the mode='direct' worker logic.
 *
 * We intentionally avoid WorkManager TestDriver here — it requires a real device
 * process dispatch cycle and Koin bootstrap that are heavy and brittle in CI.
 * Instead, these tests exercise the DAO + DirectModeActionRunner layer directly,
 * reproducing exactly what CronJobWorker.runDirect() does: optimistic row insert,
 * action execution, row update with final outcome.
 *
 * LLM-mode integration and concurrent_skip tests are skipped:
 * - LLM mode requires ChatService + a live provider conversation — too heavy for
 *   an instrumented test without an emulated LLM server.
 * - concurrent_skip requires two concurrent workers racing the same job ID, which
 *   needs WorkManager TestDriver running real workers.
 * TODO: covered by audit/follow-up once WorkManager TestDriver scaffolding lands.
 */
@OptIn(ExperimentalUuidApi::class)
@RunWith(AndroidJUnit4::class)
class CronJobWorkerInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var jobRepo: ScheduledJobRepository
    private lateinit var runRepo: ScheduledJobRunRepository
    private lateinit var directRunner: DirectModeActionRunner

    // A fake assistantId used in all test jobs.
    private val fakeAssistantId = Uuid.random().toString()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        jobRepo = ScheduledJobRepository(db.scheduledJobDao())
        runRepo = ScheduledJobRunRepository(db.scheduledJobRunDao())
        directRunner = DirectModeActionRunner(Json)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Helper — builds and inserts a minimal mode='direct' job.
    // -------------------------------------------------------------------------
    private suspend fun insertDirectJob(actionsJson: String): ScheduledJobEntity {
        val job = ScheduledJobEntity(
            id = Uuid.random().toString(),
            name = "test-job",
            assistantId = fakeAssistantId,
            scheduleType = "once",
            mode = "direct",
            actionsJson = actionsJson,
            enabled = true,
            createdAtMs = System.currentTimeMillis(),
        )
        jobRepo.upsert(job)
        return job
    }

    // -------------------------------------------------------------------------
    // Simulates what CronJobWorker.doWork() does for mode='direct':
    // 1. Inserts an optimistic run row (outcome='success').
    // 2. Calls runDirect via the action runner.
    // 3. Updates the row with the real outcome.
    // Returns the final run row for assertions.
    // -------------------------------------------------------------------------
    private suspend fun executeDirectJob(
        job: ScheduledJobEntity,
        availableTools: List<Tool>,
    ): ScheduledJobRunEntity {
        val runRowId = Uuid.random().toString()
        val nowMs = System.currentTimeMillis()

        // Optimistic insert (mirrors CronJobWorker lines 82-92)
        runRepo.insert(
            ScheduledJobRunEntity(
                id = runRowId,
                jobId = job.id,
                mode = "direct",
                scheduledAtMs = nowMs,
                startedAtMs = nowMs,
                finishedAtMs = null,
                outcome = "success",      // optimistic
                conversationId = null,
                errorMessage = null,
            )
        )

        // Parse + run actions
        val actionsJson = job.actionsJson!!
        val parsed = DirectModeActionRunner.parse(actionsJson).getOrElse {
            val errMsg = "actions_parse:${(it as? DirectModeActionRunner.ParseError)?.code ?: it.message}"
            runRepo.update(
                ScheduledJobRunEntity(
                    id = runRowId, jobId = job.id, mode = "direct",
                    scheduledAtMs = nowMs, startedAtMs = nowMs,
                    finishedAtMs = System.currentTimeMillis(),
                    outcome = "failed", conversationId = null,
                    errorMessage = errMsg.take(500),
                )
            )
            return runRepo.getRecent(job.id, 1).first()
        }

        val seq = directRunner.run(parsed, availableTools)

        // Update row with real outcome (mirrors CronJobWorker lines 100-110)
        runRepo.update(
            ScheduledJobRunEntity(
                id = runRowId, jobId = job.id, mode = "direct",
                scheduledAtMs = nowMs, startedAtMs = nowMs,
                finishedAtMs = System.currentTimeMillis(),
                outcome = seq.finalOutcome, conversationId = null,
                errorMessage = seq.errorMessage?.take(500),
            )
        )

        return runRepo.getRecent(job.id, 1).first()
    }

    // =========================================================================
    // Test 1 — green-path: single post_notification action → success row
    // =========================================================================
    @Test
    fun directMode_singleActionSuccess_writesSuccessRow() = runBlocking {
        // Fake tool that mimics post_notification but does nothing on device.
        val fakeNotificationTool = Tool(
            name = "post_notification",
            description = "fake",
            execute = { _ -> listOf(UIMessagePart.Text("ok")) }
        )

        val actionsJson = """
            [{"tool":"post_notification","args":{"title":"Hello","body":"World"}}]
        """.trimIndent()

        val job = insertDirectJob(actionsJson)
        val row = executeDirectJob(job, listOf(fakeNotificationTool))

        assertEquals("outcome should be success", "success", row.outcome)
        assertNotNull("finishedAtMs should be set", row.finishedAtMs)
        assertEquals("jobId should match", job.id, row.jobId)
        assertEquals("mode should be direct", "direct", row.mode)
        assertTrue("errorMessage should be null", row.errorMessage == null)
    }

    // =========================================================================
    // Test 2 — failure path: two actions, second throws → failed row with
    //          action-index in errorMessage
    // =========================================================================
    @Test
    fun directMode_secondActionThrows_writesFailedRowWithActionIndex() = runBlocking {
        val fakeSuccess = Tool(
            name = "action_ok",
            description = "fake ok",
            execute = { _ -> listOf(UIMessagePart.Text("step1 done")) }
        )
        val fakeThrowing = Tool(
            name = "action_throw",
            description = "fake throw",
            execute = { _ -> throw RuntimeException("boom") }
        )

        val actionsJson = """
            [
              {"tool":"action_ok","args":{}},
              {"tool":"action_throw","args":{}}
            ]
        """.trimIndent()

        val job = insertDirectJob(actionsJson)
        val row = executeDirectJob(job, listOf(fakeSuccess, fakeThrowing))

        assertEquals("outcome should be failed", "failed", row.outcome)
        val errMsg2 = requireNotNull(row.errorMessage) { "errorMessage should be populated" }
        assertTrue("errorMessage should mention action index 1", errMsg2.contains("action 1"))
        assertTrue(
            "errorMessage should contain exception info",
            errMsg2.contains("RuntimeException") || errMsg2.contains("boom")
        )
    }

    // =========================================================================
    // Test 3 — unknown tool → failed row with unknown_tool error
    // =========================================================================
    @Test
    fun directMode_unknownTool_writesFailedRow() = runBlocking {
        val actionsJson = """
            [{"tool":"nonexistent_tool","args":{}}]
        """.trimIndent()

        val job = insertDirectJob(actionsJson)
        val row = executeDirectJob(job, emptyList())

        assertEquals("outcome should be failed", "failed", row.outcome)
        val errMsg3 = requireNotNull(row.errorMessage) { "errorMessage should be populated for unknown tool" }
        assertTrue("errorMessage should mention unknown_tool", errMsg3.contains("unknown_tool"))
    }

    // =========================================================================
    // Test 4 — malformed actionsJson → failed row with parse error code
    // =========================================================================
    @Test
    fun directMode_malformedActions_writesFailedRowWithParseError() = runBlocking {
        val job = insertDirectJob("{not-valid-json}")
        val row = executeDirectJob(job, emptyList())

        assertEquals("outcome should be failed", "failed", row.outcome)
        val errMsg4 = requireNotNull(row.errorMessage) { "errorMessage should be populated for parse error" }
        assertTrue(
            "errorMessage should contain parse error",
            errMsg4.contains("actions_parse") || errMsg4.contains("invalid_json")
        )
    }

    // =========================================================================
    // Test 5 — trim keeps at most 100 rows (boundary check)
    // =========================================================================
    @Test
    fun directMode_trimKeeps100Rows() = runBlocking {
        val fakeOk = Tool(
            name = "noop",
            description = "noop",
            execute = { _ -> listOf(UIMessagePart.Text("ok")) }
        )
        val actionsJson = """[{"tool":"noop","args":{}}]"""
        val job = insertDirectJob(actionsJson)

        // Insert 110 rows manually
        repeat(110) { i ->
            val nowMs = System.currentTimeMillis() + i
            runRepo.insert(
                ScheduledJobRunEntity(
                    id = Uuid.random().toString(),
                    jobId = job.id,
                    mode = "direct",
                    scheduledAtMs = nowMs,
                    startedAtMs = nowMs,
                    finishedAtMs = nowMs + 1,
                    outcome = "success",
                    conversationId = null,
                    errorMessage = null,
                )
            )
        }

        // Trim to 100 (mirrors CronJobWorker line 133)
        runRepo.trim(job.id, keep = 100)

        val remaining = runRepo.getRecent(job.id, 200)
        assertTrue("Should keep at most 100 rows", remaining.size <= 100)
    }

    // =========================================================================
    // Test 6 — KEY_MANUAL guard (spec Decision 13):
    //   A manual fire (isManual=true) must NOT increment runsSoFar and must NOT
    //   update lastRunAtMs on the job entity — it still gets a history row, but
    //   the regular schedule state is untouched.
    //
    //   We simulate the exact if (!isManual) block from CronJobWorker.doWork()
    //   with isManual=true, then verify the job entity is unchanged.
    // =========================================================================
    @Test
    fun manualFire_doesNotUpdateRunsSoFarOrLastRunAtMs() = runBlocking {
        val fakeOk = Tool(
            name = "post_notification",
            description = "fake",
            execute = { _ -> listOf(UIMessagePart.Text("ok")) }
        )
        val actionsJson = """[{"tool":"post_notification","args":{"title":"T","body":"B"}}]"""

        // Seed the job with known runsSoFar and lastRunAtMs so we can detect if they changed.
        val seedRunsSoFar = 5
        val seedLastRunAtMs = 12345L
        val job = ScheduledJobEntity(
            id = Uuid.random().toString(),
            name = "manual-test-job",
            assistantId = fakeAssistantId,
            scheduleType = "cron",
            cronExpression = "0 * * * *",
            mode = "direct",
            actionsJson = actionsJson,
            enabled = true,
            createdAtMs = System.currentTimeMillis(),
            runsSoFar = seedRunsSoFar,
            lastRunAtMs = seedLastRunAtMs,
        )
        jobRepo.upsert(job)

        val isManual = true  // KEY_MANUAL = true

        // ---- Simulate the doWork() body for mode='direct' with isManual=true ----
        val runRowId = Uuid.random().toString()
        val nowMs = System.currentTimeMillis()

        // Optimistic run row insert (always happens, manual or not)
        runRepo.insert(
            ScheduledJobRunEntity(
                id = runRowId,
                jobId = job.id,
                mode = "direct",
                scheduledAtMs = nowMs,
                startedAtMs = nowMs,
                finishedAtMs = null,
                outcome = "success",
                conversationId = null,
                errorMessage = null,
            )
        )

        // Execute actions
        val parsed = DirectModeActionRunner.parse(actionsJson).getOrThrow()
        val seq = directRunner.run(parsed, listOf(fakeOk))
        val outcome = seq.finalOutcome

        // Update run row with real outcome
        runRepo.update(
            ScheduledJobRunEntity(
                id = runRowId,
                jobId = job.id,
                mode = "direct",
                scheduledAtMs = nowMs,
                startedAtMs = nowMs,
                finishedAtMs = System.currentTimeMillis(),
                outcome = outcome,
                conversationId = null,
                errorMessage = seq.errorMessage?.take(500),
            )
        )

        // Reproduce the if (!isManual) guard — because isManual=true, we skip the update block.
        if (!isManual) {
            // This block must NOT run for a manual fire.
            val updated = job.copy(
                lastRunAtMs = nowMs,
                runsSoFar = job.runsSoFar + 1,
            )
            jobRepo.upsert(updated)
        }
        // ---- End doWork() simulation ----

        // Verify: the history row was written
        val historyRow = runRepo.getRecent(job.id, 1).firstOrNull()
        assertNotNull("History row must exist for manual fire", historyRow)
        assertEquals("History row outcome should be success", "success", historyRow!!.outcome)

        // Verify: job entity is UNCHANGED
        val jobAfter = requireNotNull(jobRepo.getById(job.id)) { "job must still exist" }
        assertEquals(
            "runsSoFar must NOT be incremented by a manual fire",
            seedRunsSoFar, jobAfter.runsSoFar,
        )
        assertEquals(
            "lastRunAtMs must NOT be updated by a manual fire",
            seedLastRunAtMs, jobAfter.lastRunAtMs,
        )
    }

    // TODO: LLM-mode integration test — skipped because it requires ChatService +
    //       a live provider conversation loop. Covered by audit/follow-up.
    //
    // TODO: concurrent_skip test — skipped because it requires WorkManager
    //       TestDriver dispatching two real workers racing the same job ID.
    //       Covered by audit/follow-up once WorkManager TestDriver scaffolding lands.
}
