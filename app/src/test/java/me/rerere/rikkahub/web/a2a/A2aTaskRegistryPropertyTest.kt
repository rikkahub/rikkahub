package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class A2aTaskRegistryPropertyTest {

    @Test
    fun `putIfAbsent enforces one active task per context`() {
        val registry = A2aTaskRegistry()
        val contextId = Uuid.random()
        val assistantId = Uuid.random()

        val first = registry.admit(contextId, assistantId, "m-1")
        val second = registry.admit(contextId, assistantId, "m-2")

        assertTrue(first is A2aAdmission.Accepted)
        assertTrue(second is A2aAdmission.Conflict)
    }

    @Test
    fun `accepted admission publishes task record before active context lookup can resolve it`() {
        val registry = A2aTaskRegistry()
        val contextId = Uuid.random()
        val assistantId = Uuid.random()

        val admission = registry.admit(contextId, assistantId, "m-1") as A2aAdmission.Accepted
        val activeTaskId = registry.activeByContext[contextId]

        assertEquals(admission.entry.taskId, activeTaskId)
        assertEquals(admission.entry.taskId, registry.get(activeTaskId!!)?.taskId)
    }

    @Test
    fun `conflict with transient missing active task does not create a ghost task`() {
        val registry = A2aTaskRegistry()
        val contextId = Uuid.random()

        registry.activeByContext[contextId] = "initializing-task"
        val admission = registry.admit(contextId, Uuid.random(), "m-1")

        assertEquals(A2aAdmission.Conflict("initializing-task"), admission)
        assertEquals(emptyList<A2aTaskEntry>(), registry.tasks.values.toList())
    }

    @Test
    fun `terminal transition clears active by context and is absorbing`() {
        val registry = A2aTaskRegistry()
        val contextId = Uuid.random()
        val assistantId = Uuid.random()
        val accepted = registry.admit(contextId, assistantId, "m-1") as A2aAdmission.Accepted

        val first = registry.transition(accepted.entry.taskId, A2aTaskState.COMPLETED, terminal = true)
        assertNotNull(first)
        assertEquals(A2aTaskState.COMPLETED, first!!.state)
        assertEquals(null, registry.activeByContext[contextId])

        val second = registry.transition(accepted.entry.taskId, A2aTaskState.FAILED, terminal = true)
        assertNull(second)
        assertEquals(A2aTaskState.COMPLETED, registry.get(accepted.entry.taskId)!!.state)
    }

    @Test
    fun `terminal tasks are evicted by retention timeout`() {
        var now = 0L
        val registry = A2aTaskRegistry(
            now = { now++ },
            terminalRetentionMs = 10,
        )

        val accepted = registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted
        registry.transition(accepted.entry.taskId, A2aTaskState.COMPLETED, terminal = true)

        now = 20
        registry.evictExpiredAndOverflow()

        assertNull(registry.get(accepted.entry.taskId))
    }

    @Test
    fun `admission sweeps expired terminal tasks on production path`() {
        var now = 0L
        val registry = A2aTaskRegistry(
            now = { now++ },
            terminalRetentionMs = 10,
        )

        val expired = registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted
        registry.transition(expired.entry.taskId, A2aTaskState.COMPLETED, terminal = true)
        now = 20

        val fresh = registry.admit(Uuid.random(), Uuid.random(), "m-2")

        assertTrue(fresh is A2aAdmission.Accepted)
        assertNull(registry.get(expired.entry.taskId))
    }

    @Test
    fun `task registry enforces max size cap`() {
        val registry = A2aTaskRegistry(
            now = { 0L + (increment++).toLong() },
            maxTasks = 2,
        )

        val created = (0 until 4).map { index ->
            val contextId = Uuid.random()
            val admission = registry.admit(contextId, Uuid.random(), "m-$index") as A2aAdmission.Accepted
            registry.transition(admission.entry.taskId, A2aTaskState.COMPLETED, terminal = true)
            admission.entry.taskId
        }

        registry.evictExpiredAndOverflow()

        assertTrue(registry.tasks.size <= 2)
        assertEquals(2, created.toSet().count { registry.tasks.containsKey(it) })
    }

    @Test
    fun `eviction removes only evicted message ids for sibling tasks in the same context`() {
        var now = 0L
        val registry = A2aTaskRegistry(
            now = { now++ },
            terminalRetentionMs = 10,
        )
        val contextId = Uuid.random()
        val assistantId = Uuid.random()

        val first = registry.admit(contextId, assistantId, "m-1") as A2aAdmission.Accepted
        registry.transition(first.entry.taskId, A2aTaskState.COMPLETED, terminal = true)
        val second = registry.admit(contextId, assistantId, "m-2") as A2aAdmission.Accepted

        now = 20
        registry.evictExpiredAndOverflow()

        assertNull(registry.get(first.entry.taskId))
        assertNotNull(registry.get(second.entry.taskId))
        assertEquals(setOf("m-2"), registry.seenMessageIds[contextId]?.toSet())
        assertTrue(registry.admit(contextId, assistantId, "m-2") is A2aAdmission.Duplicate)
    }

    private var increment = 1
}
