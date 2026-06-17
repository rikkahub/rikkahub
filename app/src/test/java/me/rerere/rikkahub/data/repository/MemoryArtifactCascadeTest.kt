package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryVectorEntity
import me.rerere.rikkahub.data.repository.fakes.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.fakes.FakeMemoryVectorDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [deleteAssistantMemoryArtifacts] (audit C30). memory_vector declares NO foreign key to
 * memoryentity, so Room's CASCADE never reaches it on a bulk assistant-memory delete; the cascade
 * must be performed explicitly. These pin that the explicit cascade removes BOTH the content rows
 * and their vector rows for the deleted assistant — and ONLY that assistant's rows.
 */
class MemoryArtifactCascadeTest {

    private fun vector(memoryId: Int) = MemoryVectorEntity(
        memoryId = memoryId,
        contentHash = "h$memoryId",
        embedding = "[0.0]",
        embeddingSpace = "space",
    )

    @Test
    fun `cascade deletes the assistant's memories and their vector rows`() = runBlocking {
        val memoryDAO = FakeMemoryDAO()
        val vectorDAO = FakeMemoryVectorDAO()
        val a1 = memoryDAO.seed(MemoryEntity(assistantId = "A", content = "m1"))
        val a2 = memoryDAO.seed(MemoryEntity(assistantId = "A", content = "m2"))
        vectorDAO.upsert(vector(a1))
        vectorDAO.upsert(vector(a2))

        deleteAssistantMemoryArtifacts(memoryDAO, vectorDAO, "A")

        assertTrue("content rows gone", memoryDAO.getMemoriesOfAssistant("A").isEmpty())
        assertTrue("vector rows must not be orphaned", vectorDAO.getByMemoryIds(listOf(a1, a2)).isEmpty())
        assertTrue("no orphan vector ids remain", vectorDAO.ids().isEmpty())
    }

    @Test
    fun `cascade leaves another assistant's memories and vectors untouched`() = runBlocking {
        val memoryDAO = FakeMemoryDAO()
        val vectorDAO = FakeMemoryVectorDAO()
        val del = memoryDAO.seed(MemoryEntity(assistantId = "DEL", content = "x"))
        val keep = memoryDAO.seed(MemoryEntity(assistantId = "KEEP", content = "y"))
        vectorDAO.upsert(vector(del))
        vectorDAO.upsert(vector(keep))

        deleteAssistantMemoryArtifacts(memoryDAO, vectorDAO, "DEL")

        assertTrue(memoryDAO.getMemoriesOfAssistant("DEL").isEmpty())
        assertEquals(listOf("y"), memoryDAO.getMemoriesOfAssistant("KEEP").map { it.content })
        assertEquals("kept assistant's vector survives", setOf(keep), vectorDAO.ids())
    }

    @Test
    fun `cascade with no memories is a harmless no-op`() = runBlocking {
        val memoryDAO = FakeMemoryDAO()
        val vectorDAO = FakeMemoryVectorDAO()
        val keep = memoryDAO.seed(MemoryEntity(assistantId = "KEEP", content = "y"))
        vectorDAO.upsert(vector(keep))

        deleteAssistantMemoryArtifacts(memoryDAO, vectorDAO, "EMPTY")

        assertEquals(setOf(keep), vectorDAO.ids())
        assertEquals(1, memoryDAO.getMemoriesOfAssistant("KEEP").size)
    }
}
