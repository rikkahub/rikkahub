package me.rerere.rikkahub.web.a2a

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

class A2aTaskRegistry(
    private val now: () -> Long = System::currentTimeMillis,
    private val terminalRetentionMs: Long = 30 * 60 * 1000L,
    private val maxTasks: Int = 1000,
) {
    val tasks = ConcurrentHashMap<String, A2aTaskEntry>()
    val activeByContext = ConcurrentHashMap<Uuid, String>()
    val seenMessageIds = ConcurrentHashMap<Uuid, MutableSet<String>>()

    private val taskEvents = ConcurrentHashMap<String, MutableSharedFlow<A2aStreamEvent>>()
    private val seenMessageTask = ConcurrentHashMap<Uuid, ConcurrentHashMap<String, String>>()
    private val collectorJobs = ConcurrentHashMap<String, Job>()

    fun admit(contextId: Uuid, assistantId: Uuid, sourceMessageId: String): A2aAdmission {
        evictExpiredAndOverflow()

        val contextMessageTasks = seenMessageTask.getOrPut(contextId) { ConcurrentHashMap() }
        val duplicateTaskId = contextMessageTasks[sourceMessageId]
        if (duplicateTaskId != null) {
            val duplicated = tasks[duplicateTaskId]
            if (duplicated != null) {
                return A2aAdmission.Duplicate(duplicated)
            }

            contextMessageTasks.remove(sourceMessageId)
            seenMessageIds[contextId]?.remove(sourceMessageId)
        }

        val taskId = Uuid.random().toString()
        val nowMs = now()
        val entry = A2aTaskEntry(
            taskId = taskId,
            contextId = contextId,
            assistantId = assistantId,
            status = MutableStateFlow(
                A2aTaskStatus(
                    state = A2aTaskState.SUBMITTED,
                    timestamp = nowMs,
                )
            ),
            state = A2aTaskState.SUBMITTED,
            job = null,
            lastSentTextByNode = emptyMap(),
            cancelRequested = false,
            createdAt = nowMs,
            terminalAt = null,
            terminalStatusDelivered = true,
        )

        tasks[taskId] = entry
        taskEvents[taskId] = MutableSharedFlow(extraBufferCapacity = 8)

        val existing = activeByContext.putIfAbsent(contextId, taskId)
        if (existing != null) {
            tasks.remove(taskId, entry)
            taskEvents.remove(taskId)
            return A2aAdmission.Conflict(existing)
        }

        seenMessageIds.getOrPut(contextId) { ConcurrentHashMap.newKeySet() }.add(sourceMessageId)
        contextMessageTasks[sourceMessageId] = taskId

        return A2aAdmission.Accepted(entry)
    }

    fun attachJob(taskId: String, job: Job): A2aAttachResult {
        val entry = tasks[taskId] ?: throw IllegalStateException("task not found: $taskId")
        synchronized(entry) {
            if (
                entry.state == A2aTaskState.COMPLETED ||
                entry.state == A2aTaskState.CANCELED ||
                entry.state == A2aTaskState.FAILED ||
                entry.cancelRequested
            ) {
                return A2aAttachResult.Rejected(entry)
            }
            entry.job = job
            return A2aAttachResult.Accepted(entry)
        }
    }

    private data class A2aCancelPlan(val entry: A2aTaskEntry, val jobToStop: Job?)

    fun startCollector(taskId: String, start: () -> Job): Job =
        collectorJobs.computeIfAbsent(taskId) { start() }

    fun finishCollector(taskId: String, job: Job) {
        collectorJobs.remove(taskId, job)
    }

    fun cancelCollector(taskId: String) {
        collectorJobs.remove(taskId)?.cancel()
    }

    fun get(taskId: String): A2aTaskEntry? = tasks[taskId]

    fun events(taskId: String): SharedFlow<A2aStreamEvent> = taskEvents
        .getOrPut(taskId) { MutableSharedFlow(extraBufferCapacity = 8) }
        .asSharedFlow()

    fun status(taskId: String): StateFlow<A2aTaskStatus>? = tasks[taskId]?.status

    suspend fun emit(taskId: String, event: A2aStreamEvent) {
        taskEvents[taskId]?.emit(event)
    }

    fun updateLastSentText(taskId: String, nodeId: Uuid, text: String): String? {
        val entry = tasks[taskId] ?: return null
        val previous = entry.lastSentTextByNode[nodeId]
        entry.lastSentTextByNode = if (nodeId == A2A_FINAL_ARTIFACT_NODE_ID) {
            mapOf(nodeId to text)
        } else {
            entry.lastSentTextByNode + (nodeId to text)
        }
        return previous
    }

    fun requestCancel(taskId: String): A2aTaskEntry? = requestCancelPlan(taskId)?.entry

    suspend fun requestCancelWithStop(
        taskId: String,
        stopGeneration: suspend (Uuid, Job) -> Unit,
    ): A2aTaskEntry? {
        val request = requestCancelPlan(taskId) ?: return null
        request.jobToStop?.let { stopGeneration(request.entry.contextId, it) }
        return request.entry
    }

    private fun requestCancelPlan(taskId: String): A2aCancelPlan? {
        val entry = tasks[taskId] ?: return null
        val jobToStop = synchronized(entry) {
            entry.cancelRequested = true
            if (entry.state == A2aTaskState.COMPLETED || entry.state == A2aTaskState.CANCELED || entry.state == A2aTaskState.FAILED) {
                null
            } else {
                entry.job
            }
        }
        return A2aCancelPlan(entry, jobToStop)
    }

    fun transition(
        taskId: String,
        state: A2aTaskState,
        terminal: Boolean = false,
        statusConversation: Conversation? = null,
        markTerminalDelivered: Boolean = true,
    ): A2aTaskEntry? {
        val entry = tasks[taskId] ?: return null
        synchronized(entry) {
            if (entry.state == A2aTaskState.COMPLETED || entry.state == A2aTaskState.CANCELED || entry.state == A2aTaskState.FAILED) {
                return null
            }
            val nowMs = now()
            entry.state = state
            entry.status.value = A2aTaskStatus(
                state = state,
                message = statusConversation?.latestA2aTextMessage(),
                input = statusConversation?.pendingA2aInputRequests() ?: emptyList(),
                timestamp = nowMs,
            )
            if (terminal) {
                entry.terminalAt = nowMs
                entry.terminalStatusDelivered = markTerminalDelivered
                if (activeByContext[entry.contextId] == taskId) {
                    activeByContext.remove(entry.contextId, taskId)
                }
            }
            return entry
        }
    }

    fun markTerminalStatusDelivered(taskId: String) {
        tasks[taskId]?.let { entry ->
            entry.terminalStatusDelivered = true
        }
    }

    fun evictExpiredAndOverflow(excludeTaskId: String? = null) {
        val nowMs = now()
        val retentionMs = terminalRetentionMs
        val evictionTargets = tasks.values
            .filter {
                val terminalAt = it.terminalAt
                terminalAt != null &&
                    it.terminalStatusDelivered &&
                    terminalAt + retentionMs <= nowMs
            }
            .toSet()
        evictionTargets.forEach { evictTask(it.taskId) }

        if (tasks.size <= maxTasks) return

        val terminalTasks = tasks.values
            .filter { it.terminalAt != null && it.terminalStatusDelivered }
            .sortedBy { it.createdAt }

        var toEvict = maxOf(0, tasks.size - maxTasks)
        for (entry in terminalTasks) {
            if (entry.taskId == excludeTaskId) continue
            if (toEvict <= 0) break
            evictTask(entry.taskId)
            toEvict--
        }
    }

    fun rollbackAdmission(taskId: String) {
        val entry = evictTask(taskId) ?: return
        activeByContext.remove(entry.contextId, taskId)
    }

    private fun evictTask(taskId: String): A2aTaskEntry? {
        val entry = tasks.remove(taskId) ?: return null
        collectorJobs.remove(taskId)?.cancel()
        val contextMessageTasks = seenMessageTask[entry.contextId] ?: return entry
        contextMessageTasks.entries
            .filter { it.value == taskId }
            .forEach { (messageId, _) ->
                contextMessageTasks.remove(messageId)
                seenMessageIds[entry.contextId]?.remove(messageId)
            }
        if (contextMessageTasks.isEmpty()) {
            seenMessageTask.remove(entry.contextId, contextMessageTasks)
            seenMessageIds.remove(entry.contextId)
        }
        taskEvents.remove(taskId)
        return entry
    }

}

data class A2aTaskEntry(
    val taskId: String,
    val contextId: Uuid,
    val assistantId: Uuid,
    val status: MutableStateFlow<A2aTaskStatus>,
    @Volatile var state: A2aTaskState,
    @Volatile var job: Job?,
    @Volatile var lastSentTextByNode: Map<Uuid, String>,
    @Volatile var cancelRequested: Boolean,
    val createdAt: Long,
    @Volatile var terminalAt: Long?,
    @Volatile var terminalStatusDelivered: Boolean,
)

sealed interface A2aAttachResult {
    val entry: A2aTaskEntry

    data class Accepted(override val entry: A2aTaskEntry) : A2aAttachResult
    data class Rejected(override val entry: A2aTaskEntry) : A2aAttachResult
}

sealed interface A2aAdmission {
    data class Accepted(val entry: A2aTaskEntry) : A2aAdmission
    data class Duplicate(val existing: A2aTaskEntry) : A2aAdmission
    data class Conflict(val activeTaskId: String) : A2aAdmission
}
