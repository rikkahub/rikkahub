package me.rerere.ai.runtime.board

import kotlin.uuid.Uuid

/**
 * One normalized dependency edge: [blockerId] must finish before [blockedId] may proceed.
 * Mirrors the `:app` `WorkItemDependencyEntity` row shape (edges are persisted normalized,
 * never as serialized arrays).
 */
data class BoardDependencyEdge(
    val blockerId: Uuid,
    val blockedId: Uuid,
)

/** Outcome of attempting to insert one dependency edge. */
sealed interface BoardEdgeInsertResult {
    /** The edge keeps the graph acyclic; [graph] is the new value (the receiver is unchanged). */
    data class Inserted(val graph: BoardGraph) : BoardEdgeInsertResult

    /**
     * The edge would close a cycle and is rejected. [cyclePath] is the EXISTING path from the
     * edge's blocked item back to its blocker (`blockedId .. blockerId`; a single id for a
     * self-edge) — callers surface it so the user/agent sees which chain blocks the insert.
     */
    data class CycleRejected(val cyclePath: List<Uuid>) : BoardEdgeInsertResult
}

/**
 * Immutable board dependency graph whose acyclicity is a TYPE invariant (NoDependencyCycles):
 * the only values are [EMPTY] and the results of [insert], and insert rejects exactly the edges
 * whose reverse path already exists — so every reachable [BoardGraph] is a DAG by construction.
 * Rebuilding from persisted edges = folding [insert] from [EMPTY]; a rejection there means the
 * persisted rows are corrupt, never that this type allowed a cycle.
 */
class BoardGraph private constructor(
    private val outgoing: Map<Uuid, Set<Uuid>>,
) {
    /** The edge set, in the same normalized shape persistence uses. */
    val edges: Set<BoardDependencyEdge>
        get() = outgoing.flatMapTo(mutableSetOf()) { (blocker, blockedIds) ->
            blockedIds.map { BoardDependencyEdge(blockerId = blocker, blockedId = it) }
        }

    /**
     * Insert `blocker blocks blocked`. Rejected iff a path `blocked -> blocker` already exists
     * (self-edges are the zero-length case). Re-inserting an existing edge is idempotent: an
     * existing `blocker -> blocked` edge never implies the reverse path, so it re-inserts into
     * an equal graph.
     */
    fun insert(edge: BoardDependencyEdge): BoardEdgeInsertResult {
        val pathBack = pathBetween(start = edge.blockedId, target = edge.blockerId)
        if (pathBack != null) return BoardEdgeInsertResult.CycleRejected(pathBack)
        val updated = outgoing + (edge.blockerId to outgoing[edge.blockerId].orEmpty() + edge.blockedId)
        return BoardEdgeInsertResult.Inserted(BoardGraph(updated))
    }

    /** BFS path `start -> target` along blocker->blocked edges, or null when unreachable. */
    private fun pathBetween(start: Uuid, target: Uuid): List<Uuid>? {
        if (start == target) return listOf(start)
        val parent = HashMap<Uuid, Uuid>()
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (next in outgoing[node].orEmpty()) {
                if (!seen.add(next)) continue
                parent[next] = node
                if (next == target) {
                    val path = ArrayDeque<Uuid>()
                    var cursor: Uuid? = next
                    while (cursor != null) {
                        path.addFirst(cursor)
                        cursor = parent[cursor]
                    }
                    return path.toList()
                }
                queue.add(next)
            }
        }
        return null
    }

    companion object {
        /** The empty (and only root) graph value. */
        val EMPTY = BoardGraph(emptyMap())
    }
}
