package me.rerere.ai.runtime.board

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * NoDependencyCycles (SPEC.md M1): no sequence of edge inserts can ever produce a cycle in the
 * board dependency graph — [BoardGraph.insert] rejects exactly the edges whose reverse path
 * already exists (including self-edges). Verified against an independent reachability checker
 * written over the raw edge set.
 */
class BoardGraphPropertyTest {

    /** A small id pool so generated sequences actually collide and attempt cycles. */
    private val idPool: List<Uuid> = List(8) { Uuid.random() }

    private val arbItemId: Arb<Uuid> = Arb.int(0..7).map { idPool[it] }

    private val arbEdge: Arb<BoardDependencyEdge> = Arb.bind(arbItemId, arbItemId) { blocker, blocked ->
        BoardDependencyEdge(blockerId = blocker, blockedId = blocked)
    }

    /** Independent BFS reachability over a raw edge set (blocker -> blocked direction). */
    private fun reachable(edges: Set<BoardDependencyEdge>, from: Uuid, to: Uuid): Boolean {
        if (from == to) return true
        val adjacency = edges.groupBy({ it.blockerId }, { it.blockedId })
        val seen = hashSetOf(from)
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (next in adjacency[node].orEmpty()) {
                if (next == to) return true
                if (seen.add(next)) queue.add(next)
            }
        }
        return false
    }

    /** Independent cycle detector: an edge lies on a cycle iff its blocked reaches its blocker. */
    private fun hasCycle(edges: Set<BoardDependencyEdge>): Boolean =
        edges.any { reachable(edges, it.blockedId, it.blockerId) }

    private fun applyAll(inserts: List<BoardDependencyEdge>): BoardGraph =
        inserts.fold(BoardGraph.EMPTY) { graph, edge ->
            when (val result = graph.insert(edge)) {
                is BoardEdgeInsertResult.Inserted -> result.graph
                is BoardEdgeInsertResult.CycleRejected -> graph
            }
        }

    @Test
    fun `generated edge-insert sequences never yield a cycle`() {
        runBlocking {
            checkAll(1_000, Arb.list(arbEdge, 0..40)) { inserts ->
                assertFalse(hasCycle(applyAll(inserts).edges))
            }
        }
    }

    @Test
    fun `insert is rejected exactly when a path back already exists`() {
        runBlocking {
            checkAll(1_000, Arb.list(arbEdge, 0..40)) { inserts ->
                var graph = BoardGraph.EMPTY
                for (edge in inserts) {
                    val pathBackExists = reachable(graph.edges, edge.blockedId, edge.blockerId)
                    when (val result = graph.insert(edge)) {
                        is BoardEdgeInsertResult.Inserted -> {
                            assertFalse("accepted $edge despite existing path back", pathBackExists)
                            assertEquals(graph.edges + edge, result.graph.edges)
                            graph = result.graph
                        }

                        is BoardEdgeInsertResult.CycleRejected -> {
                            assertTrue("rejected $edge with no path back", pathBackExists)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a rejection carries the existing path that the new edge would close into a cycle`() {
        runBlocking {
            checkAll(1_000, Arb.list(arbEdge, 0..40)) { inserts ->
                var graph = BoardGraph.EMPTY
                for (edge in inserts) {
                    when (val result = graph.insert(edge)) {
                        is BoardEdgeInsertResult.Inserted -> graph = result.graph
                        is BoardEdgeInsertResult.CycleRejected -> {
                            val path = result.cyclePath
                            assertTrue(path.isNotEmpty())
                            assertEquals(edge.blockedId, path.first())
                            assertEquals(edge.blockerId, path.last())
                            path.zipWithNext().forEach { (from, to) ->
                                assertTrue(
                                    "cycle path step $from -> $to is not a graph edge",
                                    BoardDependencyEdge(blockerId = from, blockedId = to) in graph.edges,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `re-inserting an existing edge is idempotent`() {
        runBlocking {
            checkAll(500, Arb.list(arbEdge, 0..40)) { inserts ->
                val graph = applyAll(inserts)
                for (edge in graph.edges) {
                    when (val result = graph.insert(edge)) {
                        is BoardEdgeInsertResult.Inserted -> assertEquals(graph.edges, result.graph.edges)
                        is BoardEdgeInsertResult.CycleRejected ->
                            throw AssertionError("existing edge $edge re-insert rejected as cycle")
                    }
                }
            }
        }
    }

    @Test
    fun `self-blocking edges are always rejected`() {
        runBlocking {
            checkAll(500, Arb.list(arbEdge, 0..40), arbItemId) { inserts, id ->
                val graph = applyAll(inserts)
                val result = graph.insert(BoardDependencyEdge(blockerId = id, blockedId = id))
                assertTrue(result is BoardEdgeInsertResult.CycleRejected)
            }
        }
    }
}
