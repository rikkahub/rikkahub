package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderMutationCoordinatorTest {
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
