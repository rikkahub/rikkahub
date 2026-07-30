package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `old run identity cannot retrieve a newer generation job`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val conversationId = Uuid.random()
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId, Uuid.random()), scope) {}
        val oldJob = scope.launch { awaitCancellation() }
        session.setJob(oldJob)
        session.bindRun("old-run", oldJob)

        val newJob = scope.launch { awaitCancellation() }
        session.setJob(newJob)
        session.bindRun("new-run", newJob)

        assertNull(session.getJobForRun("old-run"))
        assertSame(newJob, session.getJobForRun("new-run"))
        session.cleanup()
    }
}
