package me.rerere.rikkahub.ui.pages.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentStepStatus
import me.rerere.rikkahub.data.repository.AgentRunRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRunVMTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AgentRunRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = AgentRunRepository(database.agentRunDao(), database)
        database.conversationDao().insert(
            ConversationEntity(
                id = "conversation",
                assistantId = "assistant",
                title = "title",
                nodes = "[]",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun selectedRunCombinesRoomFlowsIntoDetail() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot(modelId = "model"))
        repository.recordStep("step", "run", "model", AgentStepStatus.RUNNING)
        val viewModel = AgentRunVM("conversation", repository)

        viewModel.openRun("run")

        val detail = viewModel.detail.filter { it is AgentRunDetailState.Content }.first()
            as AgentRunDetailState.Content
        assertEquals("run", detail.detail.run.id)
        assertEquals("run", detail.runId)
        assertEquals(1, detail.detail.steps.size)
        assertEquals(AgentRunStatus.QUEUED.name, viewModel.activeRun.filterNotNull().first().status)
        assertEquals(1, viewModel.activeDetail.filterNotNull().first().steps.size)
    }

    @Test
    fun selectedRunBecomesMissingAfterConversationCascadeDeletion() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        val viewModel = AgentRunVM("conversation", repository)

        viewModel.openRun("run")
        viewModel.detail.filter { it is AgentRunDetailState.Content }.first()
        database.conversationDao().deleteById("conversation")

        assertEquals(
            AgentRunDetailState.Missing("run"),
            viewModel.detail.filter { it == AgentRunDetailState.Missing("run") }.first(),
        )
    }

    @Test
    fun activeDetailConvergesOnReplacementRunIdentity() = runBlocking {
        repository.createRun("run-a", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.recordStep("step-a", "run-a", "model", AgentStepStatus.RUNNING)
        val viewModel = AgentRunVM("conversation", repository)
        assertEquals("run-a", viewModel.activeDetail.filterNotNull().first().run.id)

        repository.replaceActiveRun("run-b", "conversation", "assistant", AgentRunConfigSnapshot())

        assertEquals("run-b", viewModel.activeRun.filterNotNull().first { it.id == "run-b" }.id)
        assertEquals("run-b", viewModel.activeDetail.filterNotNull().first { it.run.id == "run-b" }.run.id)
    }

    @Test
    fun childDetailCarriesBackNavigationUntilReturningToParent() = runBlocking {
        repository.createRun("root", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.createRun(
            "child",
            "conversation",
            "assistant",
            AgentRunConfigSnapshot(),
            parentRunId = "root",
        )
        val viewModel = AgentRunVM("conversation", repository)

        viewModel.openRun("root")
        viewModel.detail.filter { it.runId == "root" && it is AgentRunDetailState.Content }.first()
        viewModel.openChildRun("child")
        val child = viewModel.detail.filter {
            it.runId == "child" && it is AgentRunDetailState.Content
        }.first()

        assertTrue(child.canNavigateBack)
        assertEquals(2, child.navigationDepth)
        viewModel.navigateBack()
        val parent = viewModel.detail.filter {
            it.runId == "root" && it is AgentRunDetailState.Content
        }.first()
        assertFalse(parent.canNavigateBack)
        assertEquals(1, parent.navigationDepth)
    }
}
