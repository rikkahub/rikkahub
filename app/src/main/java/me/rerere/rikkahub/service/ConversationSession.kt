package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

data class GenerationLease internal constructor(
    val epoch: Long,
    val job: Job,
)

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val generationLock = Any()
    private var generationEpoch = 0L
    private var currentGeneration: CurrentGeneration? = null
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value != null
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /** Installs a new lazy generation boundary. The caller starts [job] only after this returns. */
    fun install(job: Job): GenerationLease {
        require(!job.isActive && !job.isCompleted) { "Generation job must be new and lazy" }
        return installBoundary(job)
    }

    /** Compatibility bridge for callers that have not yet migrated to lazy [install]. */
    fun setJob(job: Job?) {
        if (job != null) {
            installBoundary(job)
            return
        }
        val previous = detachCurrentGeneration()
        previous?.cancel()
        if (refCount.get() <= 0) scheduleIdleCheck()
    }

    fun getJob(): Job? = synchronized(generationLock) {
        currentGeneration?.lease?.job
    }

    fun bindRun(lease: GenerationLease, runId: String): Boolean {
        if (runId.isBlank()) return false
        return synchronized(generationLock) {
            val current = currentGeneration ?: return@synchronized false
            if (!current.matches(lease)) return@synchronized false
            when (current.runId) {
                null -> {
                    currentGeneration = current.copy(runId = runId)
                    true
                }
                runId -> true
                else -> false
            }
        }
    }

    fun isCurrent(lease: GenerationLease, runId: String? = null): Boolean = synchronized(generationLock) {
        val current = currentGeneration ?: return@synchronized false
        current.matches(lease) && (runId == null || current.runId == runId)
    }

    fun jobForRun(runId: String): Job? = synchronized(generationLock) {
        currentGeneration?.takeIf { it.runId == runId }?.lease?.job
    }

    /** Compatibility bridge for the current ChatService call sites. */
    fun bindRun(runId: String, job: Job): Boolean {
        val lease = synchronized(generationLock) {
            currentGeneration?.lease?.takeIf { it.job === job }
        } ?: return false
        return bindRun(lease, runId)
    }

    fun getJobForRun(runId: String): Job? = jobForRun(runId)

    private fun installBoundary(job: Job): GenerationLease {
        cancelIdleCheck()
        val previous: Job?
        val lease: GenerationLease
        synchronized(generationLock) {
            check(generationEpoch < Long.MAX_VALUE) { "Generation epoch exhausted" }
            lease = GenerationLease(++generationEpoch, job)
            previous = currentGeneration?.lease?.job
            currentGeneration = CurrentGeneration(lease)
            _generationJob.value = job
        }
        job.invokeOnCompletion { onGenerationCompleted(lease) }
        if (previous !== job) previous?.cancel()
        return lease
    }

    private fun onGenerationCompleted(lease: GenerationLease) {
        val cleared = synchronized(generationLock) {
            val current = currentGeneration
            if (current == null || !current.matches(lease)) {
                false
            } else {
                currentGeneration = null
                _generationJob.value = null
                true
            }
        }
        if (cleared && refCount.get() <= 0) scheduleIdleCheck()
    }

    private fun detachCurrentGeneration(): Job? = synchronized(generationLock) {
        val job = currentGeneration?.lease?.job
        currentGeneration = null
        _generationJob.value = null
        job
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        val generationToCancel = detachCurrentGeneration()
        val idleToCancel = idleCheckJob
        idleCheckJob = null
        generationToCancel?.cancel()
        idleToCancel?.cancel()
    }

    private data class CurrentGeneration(
        val lease: GenerationLease,
        val runId: String? = null,
    ) {
        fun matches(other: GenerationLease): Boolean =
            lease.epoch == other.epoch && lease.job === other.job
    }
}
