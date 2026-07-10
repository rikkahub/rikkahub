package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FolderMutationCoordinatorTest {
    private val originalFolderId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val movedFolderId = Uuid.parse("22222222-2222-4222-8222-222222222222")
    private val assistantId = Uuid.parse("33333333-3333-4333-8333-333333333333")

    @Test
    fun `mutations remain serialized across suspending transactions`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val coordinator = FolderMutationCoordinator(PassthroughTransactionRunner)

        val first = async {
            coordinator.mutate(
                validate = { true },
                mutation = {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    "first"
                },
            )
        }
        firstEntered.await()

        val second = async {
            coordinator.mutate(
                validate = { true },
                mutation = {
                    secondEntered.complete(Unit)
                    "second"
                },
            )
        }
        runCurrent()

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        assertEquals(FolderMutationResult.Applied("first"), first.await())
        assertEquals(FolderMutationResult.Applied("second"), second.await())
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun `failed validation rejects without mutating or running post commit`() = runTest {
        var mutated = false
        var committed = false
        val coordinator = FolderMutationCoordinator(PassthroughTransactionRunner)

        val result = coordinator.mutate(
            validate = { false },
            mutation = {
                mutated = true
                Unit
            },
            onCommitted = { committed = true },
        )

        assertEquals(FolderMutationResult.Rejected, result)
        assertFalse(mutated)
        assertFalse(committed)
    }

    @Test
    fun `transaction rollback and original error propagate while suppressing post commit`() = runTest {
        val expected = IllegalStateException("database write failed")
        val transactionRunner = SnapshotTransactionRunner(initialValue = "before")
        val coordinator = FolderMutationCoordinator(transactionRunner)
        var committed = false

        val thrown = runCatching {
            coordinator.mutate(
                validate = { true },
                mutation = {
                    transactionRunner.value = "during"
                    throw expected
                },
                onCommitted = { committed = true },
            )
        }.exceptionOrNull()

        assertSame(expected, thrown)
        assertEquals("before", transactionRunner.value)
        assertFalse(committed)

        val recovery = coordinator.mutate(
            validate = { true },
            mutation = { "recovered" },
        )
        assertEquals(FolderMutationResult.Applied("recovered"), recovery)
    }

    @Test
    fun `stale full save queued behind move preserves committed folder`() = runTest {
        val moveEntered = CompletableDeferred<Unit>()
        val releaseMove = CompletableDeferred<Unit>()
        val coordinator = FolderMutationCoordinator(PassthroughTransactionRunner)
        val stale = Conversation(
            assistantId = assistantId,
            folderId = originalFolderId,
            messageNodes = emptyList(),
        )
        var persisted = stale
        var session = stale

        val move = async {
            coordinator.mutate(
                validate = { true },
                mutation = {
                    moveEntered.complete(Unit)
                    releaseMove.await()
                    persisted = persisted.copy(folderId = movedFolderId)
                },
            )
        }
        moveEntered.await()

        val save = async {
            coordinator.serialize(
                onCommitted = { session = it },
                operation = {
                    val state = PersistedConversationFolder(
                        exists = true,
                        assistantId = persisted.assistantId,
                        folderId = persisted.folderId,
                    )
                    stale.withPersistedFolder(state).also { persisted = it }
                },
            )
        }
        runCurrent()
        releaseMove.complete(Unit)
        move.await()
        save.await()

        assertEquals(movedFolderId, persisted.folderId)
        assertEquals(movedFolderId, session.folderId)
    }

    @Test
    fun `stale full save queued behind delete preserves committed unfiled state`() = runTest {
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val coordinator = FolderMutationCoordinator(PassthroughTransactionRunner)
        val stale = Conversation(
            assistantId = assistantId,
            folderId = originalFolderId,
            messageNodes = emptyList(),
        )
        var persisted = stale
        var session = stale

        val delete = async {
            coordinator.mutate(
                validate = { true },
                mutation = {
                    deleteEntered.complete(Unit)
                    releaseDelete.await()
                    persisted = persisted.copy(folderId = null)
                },
            )
        }
        deleteEntered.await()

        val save = async {
            coordinator.serialize(
                onCommitted = { session = it },
                operation = {
                    val state = PersistedConversationFolder(
                        exists = true,
                        assistantId = persisted.assistantId,
                        folderId = persisted.folderId,
                    )
                    stale.withPersistedFolder(state).also { persisted = it }
                },
            )
        }
        runCurrent()
        releaseDelete.complete(Unit)
        delete.await()
        save.await()

        assertEquals(null, persisted.folderId)
        assertEquals(null, session.folderId)
    }

    @Test
    fun `new conversation save preserves its requested folder`() {
        val requested = Conversation(
            assistantId = assistantId,
            folderId = movedFolderId,
            messageNodes = emptyList(),
        )

        val normalized = requested.withPersistedFolder(
            PersistedConversationFolder(exists = false, assistantId = null, folderId = null),
        )

        assertEquals(movedFolderId, normalized.folderId)
    }

    @Test
    fun `assistant change keeps intentional folder clearing`() {
        val movedAssistant = Conversation(
            assistantId = Uuid.parse("44444444-4444-4444-8444-444444444444"),
            folderId = null,
            messageNodes = emptyList(),
        )

        val normalized = movedAssistant.withPersistedFolder(
            PersistedConversationFolder(
                exists = true,
                assistantId = assistantId,
                folderId = originalFolderId,
            ),
        )

        assertEquals(null, normalized.folderId)
    }

    @Test
    fun `serialized save error propagates and suppresses post commit`() = runTest {
        val expected = IllegalStateException("save failed")
        val coordinator = FolderMutationCoordinator(PassthroughTransactionRunner)
        var committed = false

        val thrown = runCatching {
            coordinator.serialize(
                operation = { throw expected },
                onCommitted = { committed = true },
            )
        }.exceptionOrNull()

        assertSame(expected, thrown)
        assertFalse(committed)
        assertEquals("recovered", coordinator.serialize { "recovered" })
    }
}

private object PassthroughTransactionRunner : FolderTransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
}

private class SnapshotTransactionRunner(initialValue: String) : FolderTransactionRunner {
    var value: String = initialValue

    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        val snapshot = value
        return try {
            block()
        } catch (error: Throwable) {
            value = snapshot
            throw error
        }
    }
}
