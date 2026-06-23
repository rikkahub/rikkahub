package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [ConversationTombstones] (#360 P3): the conversation delete/restore coordination
 * extracted from ChatService. Pins the race invariants the original inline mutex + tombstone set only
 * documented — now testable with fake delete/insert bodies, without the full ChatService or a Room repo.
 */
class ConversationTombstonesTest {

    @Test
    fun `a fresh id is not tombstoned`() {
        assertFalse(ConversationTombstones().isTombstoned(Uuid.random()))
    }

    @Test
    fun `delete marks the tombstone BEFORE its body and it stays set afterward`() = runBlocking {
        val t = ConversationTombstones()
        val id = Uuid.random()
        var tombstonedInsideBody = false

        t.delete(id) { tombstonedInsideBody = t.isTombstoned(id) }

        assertTrue("tombstone must be set before the delete body runs", tombstonedInsideBody)
        assertTrue("a delete is terminal — the id stays tombstoned", t.isTombstoned(id))
    }

    @Test
    fun `restore inserts BEFORE clearing the tombstone`() = runBlocking {
        val t = ConversationTombstones()
        val id = Uuid.random()
        t.delete(id) {} // mark it deleted

        var tombstonedDuringInsert = false
        t.restore(id) { tombstonedDuringInsert = t.isTombstoned(id) }

        assertTrue("the row must be inserted while still tombstoned (no resurrect-race window)", tombstonedDuringInsert)
        assertFalse("the tombstone is cleared only after the insert", t.isTombstoned(id))
    }

    @Test
    fun `restore waits for an in-flight delete (serialized under one lock)`() = runBlocking {
        val t = ConversationTombstones()
        val id = Uuid.random()
        val deleteHoldsLock = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val deleteJob = launch {
            t.delete(id) {
                deleteHoldsLock.complete(Unit)
                releaseDelete.await() // hold the lock until the test releases it
                order += "delete"
            }
        }
        deleteHoldsLock.await() // delete now owns the lock

        val restoreJob = launch { t.restore(id) { order += "restore" } }
        repeat(5) { yield() } // give restore every chance to (wrongly) proceed

        assertEquals("restore must not proceed while delete holds the lock", emptyList<String>(), order)

        releaseDelete.complete(Unit)
        deleteJob.join()
        restoreJob.join()

        assertEquals("delete commits fully before restore runs", listOf("delete", "restore"), order)
        assertFalse("after restore the id is no longer tombstoned", t.isTombstoned(id))
    }
}
