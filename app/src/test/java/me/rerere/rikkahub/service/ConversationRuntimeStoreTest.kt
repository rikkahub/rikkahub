package me.rerere.rikkahub.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRuntimeStoreTest {
    @Test
    fun `begin generation is atomic and rejects idle-only mutation`() = runTest {
        val persistence = FakePersistence()
        val store = createStore(persistence)
        val id = Uuid.random()
        store.ensure(id) { Conversation.ofId(id, Uuid.random()) }

        val handle = store.beginGeneration(id) { it.copy(title = "queued") }

        assertEquals(GenerationState.Queued(handle.generationId), store.get(id).generation)
        assertEquals("queued", persistence.values.getValue(id).title)
        val error = captureFailure<ChatCommandException> {
            store.mutate(id, requireIdle = true) { it.copy(title = "conflict") }
        }
        assertEquals(ChatFailureCode.Conflict, error.failure.code)
        assertEquals("queued", store.get(id).conversation.title)
    }

    @Test
    fun `awaiting approval remains busy for generation and structural commands`() = runTest {
        val store = createStore(FakePersistence())
        val id = Uuid.random()
        store.ensure(id) { Conversation.ofId(id, Uuid.random()) }
        val handle = store.beginGeneration(id) { it }
        store.updateGeneration(id, handle.generationId, persistConversation = true) {
            GenerationState.AwaitingApproval(handle.generationId, listOf("tool-1"))
        }
        store.mutate(id, persist = true) { it.copy(title = "allowed metadata") }

        assertEquals("allowed metadata", store.get(id).conversation.title)

        assertEquals(
            ChatFailureCode.Conflict,
            captureFailure<ChatCommandException> {
                store.beginGeneration(id) { it }
            }.failure.code,
        )
        assertEquals(
            ChatFailureCode.Conflict,
            captureFailure<ChatCommandException> {
                store.mutate(id, requireIdle = true) { it.copy(title = "blocked") }
            }.failure.code,
        )
        assertEquals(
            ChatFailureCode.Conflict,
            captureFailure<ChatCommandException> { store.delete(id) }.failure.code,
        )
    }

    @Test
    fun `revision increases for conversation and generation changes`() = runTest {
        val store = createStore(FakePersistence())
        val id = Uuid.random()
        val initial = store.ensure(id) { Conversation.ofId(id, Uuid.random()) }
        val changed = store.mutate(id) { it.copy(title = "one") }
        val handle = store.beginGeneration(id) { it }
        val running = store.updateGeneration(id, handle.generationId) {
            GenerationState.Running(handle.generationId)
        }

        assertEquals(initial.revision + 1, changed.revision)
        assertEquals(changed.revision + 1, store.get(id).revision - 1)
        assertEquals(store.get(id).revision, running.revision)
    }

    @Test
    fun `completed old job cannot clear a resumed job`() = runTest {
        val store = createStore(FakePersistence())
        val id = Uuid.random()
        store.ensure(id) { Conversation.ofId(id, Uuid.random()) }
        val handle = store.beginGeneration(id) { it }
        val first = launch { }
        store.registerJob(id, handle.generationId, first)
        first.join()
        runCurrent()
        store.updateGeneration(id, handle.generationId) {
            GenerationState.AwaitingApproval(handle.generationId, listOf("tool"))
        }
        store.resumeGeneration(id, handle.generationId)

        val second = launch { awaitCancellation() }
        store.registerJob(id, handle.generationId, second)

        assertSame(second, store.cancelJob(id, handle.generationId))
        second.join()
    }

    @Test
    fun `terminal old job cannot clear a newly registered generation`() = runTest {
        val store = createStore(FakePersistence())
        val id = Uuid.random()
        store.ensure(id) { Conversation.ofId(id, Uuid.random()) }
        val firstHandle = store.beginGeneration(id) { it }
        val first = launch { awaitCancellation() }
        store.registerJob(id, firstHandle.generationId, first)
        store.updateGeneration(id, firstHandle.generationId) {
            GenerationState.Completed(firstHandle.generationId)
        }

        val secondHandle = store.beginGeneration(id) { it }
        val second = launch { awaitCancellation() }
        store.registerJob(id, secondHandle.generationId, second)
        first.cancel()
        first.join()
        runCurrent()

        assertSame(second, store.cancelJob(id, secondHandle.generationId))
        second.join()
    }

    @Test
    fun `concurrent metadata updates merge on latest conversation`() = runTest {
        val store = createStore(FakePersistence())
        val id = Uuid.random()
        store.ensure(id) { Conversation.ofId(id, Uuid.random()) }

        val title = launch { store.mutate(id, persist = true) { it.copy(title = "title") } }
        val suggestions = launch {
            store.mutate(id, persist = true) { it.copy(chatSuggestions = listOf("next")) }
        }
        title.join()
        suggestions.join()

        val conversation = store.get(id).conversation
        assertEquals("title", conversation.title)
        assertEquals(listOf("next"), conversation.chatSuggestions)
    }

    @Test
    fun `persistence failure leaves memory unchanged and does not clean files`() = runTest {
        val persistence = FakePersistence(failPersist = true)
        var cleaned = false
        val store = ConversationRuntimeStore(
            appScope = this,
            persistence = persistence,
            fileCleaner = ConversationFileCleaner { cleaned = true },
        )
        val id = Uuid.random()
        store.ensure(id) { Conversation.ofId(id, Uuid.random()) }

        captureFailure<IllegalStateException> {
            store.mutate(id, persist = true) { it.copy(title = "lost") }
        }
        assertEquals("", store.get(id).conversation.title)
        assertEquals(false, cleaned)
    }

    private fun TestScope.createStore(persistence: FakePersistence) = ConversationRuntimeStore(
        appScope = this,
        persistence = persistence,
        fileCleaner = ConversationFileCleaner { },
    )

    private suspend inline fun <reified T : Throwable> captureFailure(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        error("unreachable")
    }

    private class FakePersistence(
        private val failPersist: Boolean = false,
    ) : ConversationRuntimePersistence {
        val values = linkedMapOf<Uuid, Conversation>()

        override suspend fun load(conversationId: Uuid): Conversation? = values[conversationId]

        override suspend fun persist(conversation: Conversation) {
            if (failPersist) error("database unavailable")
            values[conversation.id] = conversation
        }

        override suspend fun delete(conversation: Conversation) {
            values.remove(conversation.id)
        }
    }
}
