package me.rerere.rikkahub.data.ai.memory

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.runtime.contract.RecalledMemory
import me.rerere.ai.runtime.memory.MEMORY_MIN_SCORE
import me.rerere.ai.runtime.memory.MEMORY_RECALL_K
import me.rerere.ai.runtime.memory.memoryAgeDays
import me.rerere.ai.runtime.memory.memoryAgeLabel
import me.rerere.ai.runtime.memory.memoryContentHash
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.memory.EmbeddingMemoryRecaller.MemoryCandidate
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.rag.KnowledgeStoreFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based suite for Memory v2 relevance recall (issue #210 §8 — P1..P12).
 *
 * Drives the PURE cores ([EmbeddingMemoryRecaller.rankRecall], [me.rerere.ai.runtime.memory.rankByRecency],
 * [memoryAgeLabel], [memoryContentHash], [KnowledgeStoreFactory.embeddingSpaceLabel],
 * [resolveMemoryRecallScope]) and the [EmbeddingMemoryRecaller]/[RecencyMemoryRecaller] orchestration
 * with a MOCKED embedder — never standing up Room/Provider/network, so this runs under the
 * `testDebugUnitTest` gate.
 *
 * On the unfixed code (no memory package) this does not compile, so it exercises strictly the new
 * behaviour: relevance selection, the minScore floor, freshness-key invalidation (content/space/
 * dimension), the recency fallback, and the enableMemory gate the historic full-dump path lacked.
 */
class MemoryRecallPropertyTest {

    private val SPACE = "https://api.example.com#text-embedding-3-small"

    private fun memory(id: Int, content: String = "m$id", createdAt: Long = 0, updatedAt: Long = 0) =
        RecalledMemory(id = id, content = content, createdAt = createdAt, updatedAt = updatedAt)

    /** A fresh, in-space candidate whose stored vector is [vector] (usable when dimensions match). */
    private fun usableCandidate(memory: RecalledMemory, vector: Vector) = MemoryCandidate(
        memory = memory,
        storedVector = vector,
        storedContentHash = memoryContentHash(memory.content),
        storedEmbeddingSpace = SPACE,
    )

    /** A candidate with no stored vector (un-embedded ⇒ recency-only). */
    private fun unembeddedCandidate(memory: RecalledMemory) = MemoryCandidate(
        memory = memory,
        storedVector = null,
        storedContentHash = null,
        storedEmbeddingSpace = null,
    )

    private fun freshHash(candidates: List<MemoryCandidate>): (Int) -> String {
        val byId = candidates.associate { it.memory.id to memoryContentHash(it.memory.content) }
        return { id -> byId.getValue(id) }
    }

    /** Records embed calls so "0 embedder calls" / "embedder not invoked" can be asserted. */
    private class RecordingEmbedder(private val vector: Vector = Vector(listOf(1.0, 0.0))) : Embedder {
        var calls = 0
            private set

        override suspend fun embed(text: String): Vector {
            calls++
            return vector
        }

        override fun diff(embedding1: Vector, embedding2: Vector): Double =
            1.0 - embedding1.cosineSimilarity(embedding2)
    }

    private class ThrowingEmbedder : Embedder {
        override suspend fun embed(text: String): Vector = throw java.io.IOException("boom")
        override fun diff(embedding1: Vector, embedding2: Vector): Double = 0.0
    }

    // ---- Generators -------------------------------------------------------------------------

    private val arbContent: Arb<String> = Arb.string(1..12)

    private fun arbMemory(idRange: IntRange): Arb<RecalledMemory> = arbitrary {
        memory(
            id = Arb.int(idRange).bind(),
            content = arbContent.bind(),
            updatedAt = Arb.long(0L..4_000_000_000_000L).bind(),
        )
    }

    /** A list of candidates with DISTINCT ids; each either un-embedded or has an in-space 2-D vector. */
    private val arbCandidates: Arb<List<MemoryCandidate>> = arbitrary {
        val n = Arb.int(0..8).bind()
        val ids = (1..n).toList() // distinct ids
        ids.map { id ->
            val mem = memory(id = id, content = arbContent.bind(), updatedAt = Arb.long(0L..4_000_000_000_000L).bind())
            if (Arb.int(0..1).bind() == 0) {
                unembeddedCandidate(mem)
            } else {
                val v = Vector(listOf(Arb.int(-5..5).bind().toDouble(), Arb.int(-5..5).bind().toDouble()))
                usableCandidate(mem, v)
            }
        }
    }

    private val query2D = Vector(listOf(1.0, 0.0))

    // ---- P1: recall ⊆ candidates ------------------------------------------------------------

    @Test
    fun `P1 - every recalled id is one of the candidates`(): Unit = runBlocking {
        checkAll(300, arbCandidates, Arb.int(1..6)) { candidates, k ->
            val ids = candidates.map { it.memory.id }.toSet()
            val result = EmbeddingMemoryRecaller.rankRecall(
                query = query2D, candidates = candidates, currentSpace = SPACE,
                currentContentHash = freshHash(candidates), k = k, minScore = MEMORY_MIN_SCORE,
            )
            assertTrue("result ids must be a subset of candidates", result.all { it.id in ids })
        }
    }

    // ---- P2: cap ----------------------------------------------------------------------------

    @Test
    fun `P2 - recall never returns more than k`(): Unit = runBlocking {
        checkAll(300, arbCandidates, Arb.int(1..6)) { candidates, k ->
            val result = EmbeddingMemoryRecaller.rankRecall(
                query = query2D, candidates = candidates, currentSpace = SPACE,
                currentContentHash = freshHash(candidates), k = k, minScore = MEMORY_MIN_SCORE,
            )
            assertTrue("size <= k", result.size <= k)
            // No duplicate ids either.
            assertEquals(result.size, result.map { it.id }.toSet().size)
        }
    }

    // ---- P3: empty store ⇒ [] AND 0 embedder calls ------------------------------------------

    @Test
    fun `P3 - empty store returns empty with zero embedder calls`(): Unit = runBlocking {
        // Pure core.
        assertEquals(
            emptyList<RecalledMemory>(),
            EmbeddingMemoryRecaller.rankRecall(
                query = query2D, candidates = emptyList(), currentSpace = SPACE,
                currentContentHash = { error("unused") }, k = 5, minScore = MEMORY_MIN_SCORE,
            )
        )
        // Orchestration: the embedder must never be touched for an empty store.
        val embedder = RecordingEmbedder()
        val recaller = EmbeddingMemoryRecaller(
            loadCandidates = { emptyList() },
            resolveContext = { EmbeddingMemoryRecaller.EmbeddingContext(embedder, SPACE) },
            fallback = RecencyMemoryRecaller(loadMemories = { emptyList() }),
        )
        assertEquals(emptyList<RecalledMemory>(), recaller.recall("hi", "a", 5))
        assertEquals(0, embedder.calls)
    }

    // ---- P4: no embedding model ⇒ recency top-k (updated_at DESC), size = min(k, n) ----------

    @Test
    fun `P4 - without an embedding model recall is recency top-k`(): Unit = runBlocking {
        checkAll(200, Arb.list(arbMemory(1..50), 0..10), Arb.int(1..6)) { rawMemories, k ->
            val memories = rawMemories.distinctBy { it.id }
            val embedder = RecordingEmbedder()
            val recaller = EmbeddingMemoryRecaller(
                loadCandidates = { memories.map { unembeddedCandidate(it) } },
                resolveContext = { null }, // no model configured
                fallback = RecencyMemoryRecaller(loadMemories = { memories }),
            )
            val result = recaller.recall("q", "a", k)
            val expected = memories
                .sortedWith(compareByDescending<RecalledMemory> { it.updatedAt }.thenByDescending { it.id })
                .take(k)
            assertEquals(expected, result)
            assertEquals(minOf(k, memories.size), result.size)
            assertEquals(0, embedder.calls) // recency path embeds nothing
        }
    }

    // ---- P5: every cosine-selected result is >= minScore (below-floor usable rows dropped) ---

    @Test
    fun `P5 - a usable vector scoring below minScore is never selected`() {
        // query=[1,0]. relevant=[1,0] (cosine 1.0 >= 0.5). irrelevant=[0,1] (cosine 0.0 < 0.5).
        val relevant = memory(1, content = "relevant", updatedAt = 100)
        val irrelevant = memory(2, content = "irrelevant", updatedAt = 999) // newer, but below floor
        val candidates = listOf(
            usableCandidate(relevant, Vector(listOf(1.0, 0.0))),
            usableCandidate(irrelevant, Vector(listOf(0.0, 1.0))),
        )
        val result = EmbeddingMemoryRecaller.rankRecall(
            query = query2D, candidates = candidates, currentSpace = SPACE,
            currentContentHash = freshHash(candidates), k = 5, minScore = MEMORY_MIN_SCORE,
        )
        // The below-floor memory must be absent even though it is newer — it is dropped, NOT demoted
        // to the recency tail (its vector IS usable, just irrelevant).
        assertEquals(listOf(relevant), result)
        assertFalse(result.contains(irrelevant))
    }

    // ---- P6: adding an irrelevant memory does not change the recall ---------------------------

    @Test
    fun `P6 - adding a low-similarity memory leaves the recall unchanged`() {
        val a = memory(1, content = "a", updatedAt = 10)
        val b = memory(2, content = "b", updatedAt = 20)
        val base = listOf(
            usableCandidate(a, Vector(listOf(1.0, 0.0))),  // cosine 1.0
            usableCandidate(b, Vector(listOf(0.9, 0.1))),  // high cosine
        )
        val lowSim = usableCandidate(memory(3, content = "noise", updatedAt = 9999), Vector(listOf(0.0, 1.0))) // cosine 0
        val withNoise = base + lowSim

        val r1 = EmbeddingMemoryRecaller.rankRecall(
            query = query2D, candidates = base, currentSpace = SPACE,
            currentContentHash = freshHash(base), k = 5, minScore = MEMORY_MIN_SCORE,
        )
        val r2 = EmbeddingMemoryRecaller.rankRecall(
            query = query2D, candidates = withNoise, currentSpace = SPACE,
            currentContentHash = freshHash(withNoise), k = 5, minScore = MEMORY_MIN_SCORE,
        )
        assertEquals(r1, r2)
    }

    // ---- P7: age string ----------------------------------------------------------------------

    @Test
    fun `P7 - age label renders today, yesterday, N days ago, and clamps the future`() {
        val now = 1_000L * 24 * 60 * 60 * 1000 // day 1000 in ms
        val day = 24L * 60 * 60 * 1000
        assertEquals("today", memoryAgeLabel(referenceMs = now, nowMs = now))
        assertEquals("today", memoryAgeLabel(referenceMs = now - day / 2, nowMs = now)) // <1 day
        assertEquals("yesterday", memoryAgeLabel(referenceMs = now - day, nowMs = now))
        assertEquals("3 days ago", memoryAgeLabel(referenceMs = now - 3 * day, nowMs = now))
        // Future reference (clock skew) clamps to "today", never a negative count.
        assertEquals("today", memoryAgeLabel(referenceMs = now + 5 * day, nowMs = now))
    }

    @Test
    fun `P7 - age days are always non-negative`(): Unit = runBlocking {
        checkAll(300, Arb.long(0L..4_000_000_000_000L), Arb.long(0L..4_000_000_000_000L)) { ref, now ->
            assertTrue(memoryAgeDays(referenceMs = ref, nowMs = now) >= 0L)
        }
    }

    // ---- P8: enableMemory=false ⇒ recaller never invoked --------------------------------------

    @Test
    fun `P8 - recall scope is null and the recaller is never invoked when memory is disabled`(): Unit = runBlocking {
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello"))))

        // Disabled ⇒ no scope ⇒ the ChatService site never calls recall.
        assertEquals(null, resolveMemoryRecallScope(Assistant(enableMemory = false), messages))

        // A recording recaller driven through the exact gate the ChatService site uses stays untouched.
        var recallInvocations = 0
        val recorder = object : MemoryRecaller {
            override suspend fun recall(query: String, assistantId: String, k: Int): List<RecalledMemory> {
                recallInvocations++
                return emptyList()
            }
        }
        resolveMemoryRecallScope(Assistant(enableMemory = false), messages)
            ?.let { recorder.recall(it.query, it.assistantId, MEMORY_RECALL_K) }
        assertEquals(0, recallInvocations)
    }

    @Test
    fun `P8 - enabled memory yields a scope and a query from the last user message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("first"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("reply"))),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("latest question"))),
        )
        val global = resolveMemoryRecallScope(Assistant(enableMemory = true, useGlobalMemory = true), messages)
        assertEquals("latest question", global?.query)
        assertEquals(me.rerere.rikkahub.data.repository.MemoryRepository.GLOBAL_MEMORY_ID, global?.assistantId)

        val perAssistant = Assistant(enableMemory = true, useGlobalMemory = false)
        val scoped = resolveMemoryRecallScope(perAssistant, messages)
        assertEquals(perAssistant.id.toString(), scoped?.assistantId)

        // Blank / no user message ⇒ no scope.
        assertEquals(null, resolveMemoryRecallScope(Assistant(enableMemory = true), emptyList()))
        assertEquals(
            null,
            resolveMemoryRecallScope(
                Assistant(enableMemory = true),
                listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("   ")))),
            )
        )
    }

    // ---- P9: freshness-key purity ------------------------------------------------------------

    @Test
    fun `P9 - content hash and embedding-space label are deterministic and injective enough`(): Unit = runBlocking {
        checkAll(300, arbContent, arbContent) { c1, c2 ->
            assertEquals(memoryContentHash(c1), memoryContentHash(c1)) // deterministic
            if (c1 != c2) {
                assertFalse("distinct content ⇒ distinct hash", memoryContentHash(c1) == memoryContentHash(c2))
            }
        }
        checkAll(200, Arb.string(1..10), Arb.string(1..10), Arb.string(1..10), Arb.string(1..10)) { b1, m1, b2, m2 ->
            val l1 = KnowledgeStoreFactory.embeddingSpaceLabel(b1, m1)
            assertEquals(l1, KnowledgeStoreFactory.embeddingSpaceLabel(b1, m1)) // deterministic
            if (b1 != b2 || m1 != m2) {
                assertFalse(
                    "distinct (baseUrl, modelId) ⇒ distinct space label",
                    l1 == KnowledgeStoreFactory.embeddingSpaceLabel(b2, m2),
                )
            }
        }
    }

    // ---- P10: space / dimension drift ⇒ dimension-guarded, never selected, falls to recency ---

    @Test
    fun `P10 - a wrong-dimension vector is guarded before cosine and falls to recency`() {
        val m = memory(1, content = "x", updatedAt = 5)
        // 3-D vector vs 2-D query: a naive cosine would crash. The guard treats it as un-embedded.
        val candidates = listOf(
            MemoryCandidate(
                memory = m,
                storedVector = Vector(listOf(1.0, 0.0, 0.0)),
                storedContentHash = memoryContentHash(m.content),
                storedEmbeddingSpace = SPACE,
            )
        )
        val result = EmbeddingMemoryRecaller.rankRecall(
            query = query2D, candidates = candidates, currentSpace = SPACE,
            currentContentHash = freshHash(candidates), k = 5, minScore = MEMORY_MIN_SCORE,
        )
        // Recalled via recency (un-embedded), never via cosine; no crash.
        assertEquals(listOf(m), result)
    }

    @Test
    fun `P10 - a vector embedded in a different space is never cosine-selected`() {
        val m = memory(1, content = "x", updatedAt = 5)
        val candidates = listOf(
            MemoryCandidate(
                memory = m,
                storedVector = Vector(listOf(1.0, 0.0)), // would score 1.0 if it were in-space
                storedContentHash = memoryContentHash(m.content),
                storedEmbeddingSpace = "https://other.example.com#different-model",
            )
        )
        // currentSpace differs ⇒ stale space ⇒ recency, not cosine.
        val result = EmbeddingMemoryRecaller.rankRecall(
            query = query2D, candidates = candidates, currentSpace = SPACE,
            currentContentHash = freshHash(candidates), k = 5, minScore = MEMORY_MIN_SCORE,
        )
        assertEquals(listOf(m), result)
    }

    // ---- P11: stale-vector invalidation (content edited after embedding) ----------------------

    @Test
    fun `P11 - a vector whose content hash no longer matches is never used to rank`() {
        val m = memory(1, content = "new content", updatedAt = 5)
        val candidates = listOf(
            MemoryCandidate(
                memory = m,
                storedVector = Vector(listOf(1.0, 0.0)), // perfect score if it were used
                storedContentHash = memoryContentHash("OLD content"), // hash of the pre-edit text
                storedEmbeddingSpace = SPACE,
            )
        )
        val result = EmbeddingMemoryRecaller.rankRecall(
            query = query2D, candidates = candidates, currentSpace = SPACE,
            currentContentHash = freshHash(candidates), k = 5, minScore = MEMORY_MIN_SCORE,
        )
        // Stale hash ⇒ treated as un-embedded ⇒ recency. The stale vector did NOT rank it by cosine.
        assertEquals(listOf(m), result)
    }

    // ---- P12: write-persist on embed failure --------------------------------------------------

    @Test
    fun `P12 - a throwing embedder yields no vector row but the memory still recalls via recency`(): Unit = runBlocking {
        // The embed step on write fails: buildMemoryVectorRow swallows it to null (content row, written
        // earlier by the repository, is the source of truth) — never propagates the failure.
        val row = buildMemoryVectorRow(
            embedder = ThrowingEmbedder(),
            memoryId = 7,
            content = "remember this",
            embeddingSpace = SPACE,
        )
        assertEquals(null, row)

        // With no vector row stored, recall still returns the memory via the recency fallback.
        val m = memory(7, content = "remember this", updatedAt = 123)
        val recaller = EmbeddingMemoryRecaller(
            loadCandidates = { listOf(unembeddedCandidate(m)) },
            resolveContext = { EmbeddingMemoryRecaller.EmbeddingContext(RecordingEmbedder(), SPACE) },
            fallback = RecencyMemoryRecaller(loadMemories = { listOf(m) }),
        )
        assertEquals(listOf(m), recaller.recall("q", "a", 5))
    }

    // ---- Extra: cosine tier precedes the recency tail -----------------------------------------

    @Test
    fun `cosine-selected memories precede the recency fill`() {
        val relevant = memory(1, content = "rel", updatedAt = 1) // old but relevant
        val recentUnembedded = memory(2, content = "recent", updatedAt = 9999) // newest, un-embedded
        val candidates = listOf(
            usableCandidate(relevant, Vector(listOf(1.0, 0.0))), // cosine 1.0
            unembeddedCandidate(recentUnembedded),
        )
        val result = EmbeddingMemoryRecaller.rankRecall(
            query = query2D, candidates = candidates, currentSpace = SPACE,
            currentContentHash = freshHash(candidates), k = 5, minScore = MEMORY_MIN_SCORE,
        )
        // Relevant (cosine) first, then the recency fill — even though the un-embedded one is newer.
        assertEquals(listOf(relevant, recentUnembedded), result)
    }
}
