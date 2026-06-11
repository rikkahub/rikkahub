package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.estimateTokens
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.runtime.knowledge.KnowledgeContextRenderer
import me.rerere.ai.runtime.knowledge.KnowledgeScope
import me.rerere.ai.runtime.knowledge.KnowledgeSource
import me.rerere.ai.runtime.contract.RecalledMemory
import me.rerere.ai.runtime.memory.memoryAgeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the MEMORY emitter [buildMemoryPrompt] (issue #141 Phase 2). Mirrors the Phase 1
 * pure-seam style ([me.rerere.rikkahub.data.ai.transformers.KnowledgeContextTransformerTest]): no
 * Android Context / Koin / disk, just the pure function over [RecalledMemory].
 *
 * Phase 2 converts `buildMemoryPrompt` from a single `**Memories**` JSON-array String dump of ALL
 * rows into a MEMORY emitter that returns ONE [me.rerere.ai.runtime.knowledge.KnowledgeContextBlock]
 * per recalled memory, so the assembler can budget memory against the system-prompt surface and the
 * renderer can source-label each block (`<memory>`). These tests lock the emitter contract:
 *  - one block per recalled memory (not one combined blob),
 *  - each block still carries id + content + age (so memory_tool can edit/delete by id and staleness
 *    still shows — the capability the old prompt provided must survive the reframe),
 *  - emptyList for no memories (the memory-off guard; the caller only invokes this under enableMemory),
 *  - estimatedTokens counts the RENDERED `<memory>` block (budget correctness, matching how Phase 1
 *    counts ragBlock/attachment), and
 *  - the caller-passed scope is propagated onto every block.
 *
 * [nowMs] is injected so the age label is deterministic.
 */
class MemoryPromptEmitterTest {

    private val nowMs = 1_700_000_000_000L

    private fun memory(id: Int, content: String, ageDays: Long) = RecalledMemory(
        id = id,
        content = content,
        createdAt = nowMs - ageDays * 86_400_000L,
        updatedAt = nowMs - ageDays * 86_400_000L,
    )

    private fun tokensOfRendered(content: String): Int =
        estimateTokens(
            listOf(
                UIMessagePart.Text(
                    KnowledgeContextRenderer.render(
                        me.rerere.ai.runtime.knowledge.KnowledgeContextBlock(
                            source = KnowledgeSource.MEMORY,
                            scope = KnowledgeScope.ASSISTANT,
                            title = null,
                            content = content,
                            priority = 0,
                            estimatedTokens = 0,
                        )
                    )
                )
            )
        )

    @Test
    fun `emits one block per recalled memory, each tagged MEMORY with the caller scope`() {
        val memories = listOf(
            memory(1, "user prefers metric units", ageDays = 0),
            memory(2, "user lives in Berlin", ageDays = 3),
            memory(3, "user is allergic to peanuts", ageDays = 10),
        )

        val blocks = buildMemoryPrompt(memories, KnowledgeScope.ASSISTANT, nowMs)

        assertEquals("one block per recalled memory", memories.size, blocks.size)
        assertTrue("every block is sourced MEMORY", blocks.all { it.source == KnowledgeSource.MEMORY })
        assertTrue("every block carries the caller scope", blocks.all { it.scope == KnowledgeScope.ASSISTANT })
    }

    @Test
    fun `each block carries the memory id, content and age so capability is preserved`() {
        val mem = memory(42, "user prefers dark mode", ageDays = 2)

        val block = buildMemoryPrompt(listOf(mem), KnowledgeScope.ASSISTANT, nowMs).single()

        // The model-facing text the renderer will emit must still contain id + content + age.
        val rendered = KnowledgeContextRenderer.render(block)
        assertTrue("id preserved (memory_tool edits/deletes by id)", rendered.contains("\"id\": 42"))
        assertTrue("content preserved", rendered.contains("user prefers dark mode"))
        // ageDays = 2 -> deterministic "2 days ago" via the injected nowMs.
        assertEquals("2 days ago", memoryAgeLabel(mem.updatedAt, nowMs))
        assertTrue("age preserved (staleness still shows)", rendered.contains("2 days ago"))
    }

    @Test
    fun `no memories yields an empty list (the memory-off guard)`() {
        assertEquals(emptyList<Any>(), buildMemoryPrompt(emptyList(), KnowledgeScope.ASSISTANT, nowMs))
        assertEquals(emptyList<Any>(), buildMemoryPrompt(emptyList(), KnowledgeScope.GLOBAL, nowMs))
    }

    @Test
    fun `estimatedTokens is positive and counts the rendered memory block`() {
        val mem = memory(7, "user works night shifts and sleeps during the day", ageDays = 1)

        val block = buildMemoryPrompt(listOf(mem), KnowledgeScope.ASSISTANT, nowMs).single()

        assertTrue("non-empty memory must have a positive token estimate", block.estimatedTokens > 0)
        // The estimate must count the <memory> wrapper the renderer adds, like ragBlock/attachment in
        // Phase 1 — otherwise the budget would undercount what is actually injected.
        assertEquals(tokensOfRendered(block.content), block.estimatedTokens)
    }

    @Test
    fun `scope propagates - GLOBAL when useGlobalMemory else ASSISTANT`() {
        val memories = listOf(memory(1, "a", 0), memory(2, "b", 1))

        val global = buildMemoryPrompt(memories, KnowledgeScope.GLOBAL, nowMs)
        val assistant = buildMemoryPrompt(memories, KnowledgeScope.ASSISTANT, nowMs)

        assertTrue(global.all { it.scope == KnowledgeScope.GLOBAL })
        assertTrue(assistant.all { it.scope == KnowledgeScope.ASSISTANT })
    }
}
