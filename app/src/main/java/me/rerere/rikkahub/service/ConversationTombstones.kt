package me.rerere.rikkahub.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Conversation delete/restore coordination (#360 P3), extracted from ChatService so its race
 * invariants are JVM-unit-testable with fake delete/insert bodies — without the full ChatService or a
 * Room repository. The file-cleanup diff and the CAS-paired side-effect invariants were already
 * extracted to top-level [removedFileUris] / [casUpdateState] and component-tested; this is the
 * remaining persistence-coordination piece.
 *
 * The tombstone set blocks [ChatService.saveConversation] for an id whose delete is in flight: during a
 * delete the Room row still EXISTS until the repo delete commits, so an in-flight finalizer (generation
 * onCompletion, a title/suggestion job) must be blocked unconditionally — keying off row-existence would
 * race the delete and resurrect the chat.
 *
 * [delete] and [restore] run under one mutex so a History "Undo" cannot interleave with an in-flight
 * delete (delete is fire-and-forget + Undo is offered immediately). Two ordering invariants are encoded
 * here, not at the call site:
 *  - [delete] marks the tombstone BEFORE running its body, so the id reads tombstoned for the whole
 *    delete (blocking concurrent saves).
 *  - [restore] inserts the row FIRST and clears the tombstone AFTER, so while the insert is in flight
 *    the id is still tombstoned (a stale save is blocked, not racing in an insert); once cleared the row
 *    already exists, so any later save takes the update path and can never resurrect via insert.
 */
class ConversationTombstones {
    private val tombstoned = ConcurrentHashMap<Uuid, Unit>()
    private val deleteRestoreMutex = Mutex()

    /** True while [id]'s delete is in flight (marked) and not yet restored. */
    fun isTombstoned(id: Uuid): Boolean = tombstoned.containsKey(id)

    /**
     * Delete coordination: mark [id] tombstoned, then run [body] (stop generation, cancel background,
     * evict the session, delete the row) under the delete/restore lock. The tombstone is set BEFORE
     * [body] and stays set (a delete is terminal; only [restore] clears it).
     */
    suspend fun delete(id: Uuid, body: suspend () -> Unit) = deleteRestoreMutex.withLock {
        tombstoned[id] = Unit
        body()
    }

    /**
     * Restore coordination (History "Undo"): under the same lock as [delete] (so it waits for an
     * in-flight delete to fully commit), run [insert] FIRST and then clear the tombstone — never the
     * other way around (see the class invariant).
     */
    suspend fun restore(id: Uuid, insert: suspend () -> Unit) = deleteRestoreMutex.withLock {
        insert()
        tombstoned.remove(id)
    }
}
