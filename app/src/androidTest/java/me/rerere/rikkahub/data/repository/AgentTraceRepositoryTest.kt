package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.AgentTraceEvent
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentTraceEventType
import me.rerere.rikkahub.data.model.AgentTraceStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun newTraceWritesAreDisabledWhileExistingRowsRemainReadable() = runBlocking {
        database.agentTraceEventDao().insert(
            AgentTraceEvent(
                id = "historical",
                runId = "run",
                sequence = 1,
                type = AgentTraceEventType.RUN_STARTED.name,
                status = AgentTraceStatus.STARTED.name,
                timestampMillis = 1,
                errorCategory = "NONE",
                attributesJson = "{}",
                createdAt = 1,
            ),
        )

        assertFalse(
            repository.recordTrace(
                "run",
                AgentTraceEventType.CHECKPOINT,
                AgentTraceStatus.FINISHED,
            ),
        )
        assertEquals(listOf("historical"), repository.getTraceEvents("run").map { it.id })
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
}
