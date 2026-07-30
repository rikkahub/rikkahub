package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentTraceAttributes
import me.rerere.rikkahub.data.model.AgentTraceEventType
import me.rerere.rikkahub.data.model.AgentTraceStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentTraceRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AgentRunRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = AgentRunRepository(database.agentRunDao(), database)
        database.conversationDao().insert(
            ConversationEntity("conversation", "assistant", "title", "[]", 1, 1, "[]", false),
        )
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun traceSequencesAreUniqueMonotonicAndBoundedUnderConcurrentWrites() = runBlocking {
        coroutineScope {
            (0 until 600).map { index -> async(Dispatchers.Default) {
                repository.recordTrace(
                    "run",
                    AgentTraceEventType.CHECKPOINT,
                    AgentTraceStatus.FINISHED,
                    AgentTraceAttributes(stepIndex = index),
                )
            } }.awaitAll()
        }
        val events = repository.getTraceEvents("run")
        assertEquals(512, events.size)
        assertTrue(events.zipWithNext().all { (before, after) -> after.sequence > before.sequence })
        assertTrue(events.any { it.type == AgentTraceEventType.RUN_STARTED.name })
        assertTrue(events.any { it.type == AgentTraceEventType.TRACE_TRUNCATED.name })
    }

    @Test
    fun retentionKeepsLifecycleAnchorsWhenATerminalTraceIsRecordedAfterTruncation() = runBlocking {
        repeat(600) {
            repository.recordTrace("run", AgentTraceEventType.CHECKPOINT, AgentTraceStatus.FINISHED)
        }
        repository.recordTrace("run", AgentTraceEventType.RUN_FINISHED, AgentTraceStatus.SUCCEEDED)

        val events = repository.getTraceEvents("run")
        assertTrue(events.any { it.type == AgentTraceEventType.RUN_STARTED.name })
        assertTrue(events.any { it.type == AgentTraceEventType.TRACE_TRUNCATED.name })
        assertTrue(events.any { it.type == AgentTraceEventType.RUN_FINISHED.name })
    }

    @Test
    fun invalidTraceIsRedactedAndCannotBreakRunState() = runBlocking {
        assertFalse(repository.recordTrace(
            "run", AgentTraceEventType.TOOL_FINISHED, AgentTraceStatus.SUCCEEDED,
            AgentTraceAttributes(toolNameHash = "Authorization: Bearer secret"),
        ))
        assertTrue(repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT))
        assertTrue(repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING))
        assertEquals(AgentRunStatus.RUNNING.name, repository.getRun("run")?.status)
        assertFalse(repository.getTraceEvents("run").any { it.attributesJson.contains("secret") })
    }

    @Test
    fun conversationDeletionCascadesTraceEvents() = runBlocking {
        assertTrue(repository.recordTrace("run", AgentTraceEventType.CHECKPOINT, AgentTraceStatus.FINISHED))
        database.conversationDao().deleteById("conversation")
        assertNull(repository.getRun("run"))
        assertTrue(repository.getTraceEvents("run").isEmpty())
    }

    @Test
    fun parentArtifactAuthorizationRequiresTheDirectParentAndMatchingScope() = runBlocking {
        repository.createRun(
            "child",
            "conversation",
            "assistant",
            AgentRunConfigSnapshot(),
            parentRunId = "run",
        )

        assertTrue(repository.isAuthorizedParentArtifactRun("child", "run", "assistant", "conversation"))
        assertFalse(repository.isAuthorizedParentArtifactRun("child", "other", "assistant", "conversation"))
        assertFalse(repository.isAuthorizedParentArtifactRun("child", "run", "other-assistant", "conversation"))
    }

    @Test
    fun retentionRemovesEventsOlderThanThirtyDays() = runBlocking {
        repository.recordTrace("run", AgentTraceEventType.CHECKPOINT, AgentTraceStatus.FINISHED, timestampMillis = 1)
        repository.recordTrace(
            "run", AgentTraceEventType.CHECKPOINT, AgentTraceStatus.FINISHED,
            timestampMillis = 31L * 24 * 60 * 60 * 1000,
        )
        assertFalse(repository.getTraceEvents("run").any { it.timestampMillis == 1L })
    }
}
