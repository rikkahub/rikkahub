package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [SessionRegistry] (#360 P2): the per-conversation session map + lifecycle extracted
 * from ChatService. Pins create-idempotency, the in-use eviction guard, version bumps, and the cleanup
 * sweep — WITHOUT constructing the full ChatService (the point of #360). Mirrors the construction style
 * of the existing ConversationSession tests.
 */
class SessionRegistryTest {

    private fun registry(): SessionRegistry = SessionRegistry(
        scope = CoroutineScope(Dispatchers.Unconfined),
        newConversation = { id -> Conversation.ofId(id = id) },
        onGenerationStart = {},
        onGenerationStop = {},
    )

    @Test
    fun `getOrCreate is idempotent and returns the same instance for an id`() {
        val r = registry()
        val id = Uuid.random()

        val first = r.getOrCreate(id)
        val second = r.getOrCreate(id)

        assertSame("the same id resolves to the same session", first, second)
        assertEquals(id, first.id)
    }

    @Test
    fun `get is null before create and the session after`() {
        val r = registry()
        val id = Uuid.random()
        assertNull(r.get(id))

        val s = r.getOrCreate(id)
        assertSame(s, r.get(id))
    }

    @Test
    fun `version bumps on create and on remove`() {
        val r = registry()
        val v0 = r.version.value
        val id = Uuid.random()

        r.getOrCreate(id)
        assertEquals("create bumps the version", v0 + 1, r.version.value)

        r.remove(id)
        assertEquals("remove bumps the version", v0 + 2, r.version.value)
    }

    @Test
    fun `remove keeps an in-use session unless forced`() {
        val r = registry()
        val id = Uuid.random()
        val s = r.getOrCreate(id)
        s.acquire() // refcount 1 -> in use

        r.remove(id) // force = false
        assertSame("an in-use session must NOT be evicted", s, r.get(id))

        r.remove(id, force = true)
        assertNull("force evicts even an in-use session", r.get(id))
    }

    @Test
    fun `remove evicts a not-in-use session`() {
        val r = registry()
        val id = Uuid.random()
        r.getOrCreate(id)

        r.remove(id)

        assertNull(r.get(id))
    }

    @Test
    fun `snapshot reflects the live session set`() {
        val r = registry()
        val a = Uuid.random()
        val b = Uuid.random()
        r.getOrCreate(a)
        r.getOrCreate(b)
        assertEquals(setOf(a, b), r.snapshot().map { it.id }.toSet())

        r.remove(a)
        assertEquals(setOf(b), r.snapshot().map { it.id }.toSet())
    }

    @Test
    fun `cleanupAll clears every session`() {
        val r = registry()
        r.getOrCreate(Uuid.random())
        r.getOrCreate(Uuid.random())

        r.cleanupAll()

        assertTrue(r.snapshot().isEmpty())
    }
}
