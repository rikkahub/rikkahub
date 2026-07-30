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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class GenerationLease internal constructor(
    val epoch: Long,
    val job: Job,
    val replacedRunId: String? = null,
)

class ConversationSessionHandle internal constructor(
    val conversation: StateFlow<Conversation>,
    val generationJob: StateFlow<Job?>,
    val processingStatus: StateFlow<String?>,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

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
    private val lifecycleLock = Any()
    private var closing = false

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val generationLock = Any()
    private var generationEpoch = 0L
    private var currentGeneration: CurrentGeneration? = null
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value != null
    val isInUse: Boolean get() = synchronized(lifecycleLock) { refCount.get() > 0 || isGenerating }

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    init {
        // Background loads may never receive an explicit acquire/release pair.
        scheduleIdleCheck()
    }

    fun acquire(): Int = checkNotNull(tryAcquire()) { "Conversation session is closing: $id" }

    fun tryAcquire(): Int? = synchronized(lifecycleLock) {
        if (closing) return@synchronized null
        refCount.incrementAndGet().also {
            cancelIdleCheckLocked()
            Log.d(TAG, "acquire $id (refs=$it)")
        }
    }

    fun release(): Int = synchronized(lifecycleLock) {
        check(refCount.get() > 0) { "Conversation session reference underflow: $id" }
        refCount.decrementAndGet().also {
            Log.d(TAG, "release $id (refs=$it)")
            if (it == 0) scheduleIdleCheckLocked()
        }
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
        requireLazyGeneration(job)
        return installBoundary(job)
    }

    /** Returns an optimistic token without exposing the current generation lease. */
    fun epochToken(): Long = synchronized(generationLock) {
        generationEpoch
    }

    /** Installs [job] only when no generation boundary has been installed since [expectedEpoch]. */
    fun installIfEpoch(job: Job, expectedEpoch: Long): GenerationLease? {
        require(expectedEpoch >= 0) { "Expected generation epoch must be non-negative" }
        requireLazyGeneration(job)
        val installation = synchronized(generationLock) {
            if (generationEpoch != expectedEpoch) {
                null
            } else {
                // Recheck while holding the boundary lock so validation and installation are one operation.
                requireLazyGeneration(job)
                installBoundaryLocked(job)
            }
        } ?: return null
        completeInstallation(installation)
        return installation.lease
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

    /** Requests prompt cancellation without advancing the epoch; installation remains transaction-serialized. */
    fun cancelCurrentJob(): Job? = synchronized(generationLock) {
        currentGeneration?.lease?.job
    }?.also { it.cancel() }

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

    /** Executes a non-suspending side effect atomically with respect to generation replacement. */
    fun <T> runIfCurrent(lease: GenerationLease, runId: String? = null, block: () -> T): T? =
        synchronized(generationLock) {
            val current = currentGeneration ?: return@synchronized null
            if (!current.matches(lease) || runId != null && current.runId != runId) {
                return@synchronized null
            }
            block()
        }

    fun jobForRun(runId: String): Job? = synchronized(generationLock) {
        currentGeneration?.takeIf { it.runId == runId }?.lease?.job
    }

    fun leaseForRun(runId: String): GenerationLease? = synchronized(generationLock) {
        currentGeneration?.takeIf { it.runId == runId }?.lease
    }

    fun currentRunId(): String? = synchronized(generationLock) {
        currentGeneration?.runId
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
        val installation = synchronized(generationLock) {
            installBoundaryLocked(job)
        }
        completeInstallation(installation)
        return installation.lease
    }

    private fun installBoundaryLocked(job: Job): Installation {
        check(generationEpoch < Long.MAX_VALUE) { "Generation epoch exhausted" }
        val previousGeneration = currentGeneration
        val lease = GenerationLease(
            epoch = ++generationEpoch,
            job = job,
            replacedRunId = previousGeneration?.runId ?: previousGeneration?.lease?.replacedRunId,
        )
        val previous = previousGeneration?.lease?.job
        currentGeneration = CurrentGeneration(lease)
        _generationJob.value = job
        processingStatus.value = null
        return Installation(lease, previous)
    }

    private fun completeInstallation(installation: Installation) {
        cancelIdleCheck()
        installation.lease.job.invokeOnCompletion { onGenerationCompleted(installation.lease) }
        if (installation.previous !== installation.lease.job) installation.previous?.cancel()
    }

    private fun requireLazyGeneration(job: Job) {
        require(!job.isActive && !job.isCompleted) { "Generation job must be new and lazy" }
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
        check(generationEpoch < Long.MAX_VALUE) { "Generation epoch exhausted" }
        generationEpoch++
        val job = currentGeneration?.lease?.job
        currentGeneration = null
        _generationJob.value = null
        job
    }

    private fun scheduleIdleCheck() {
        synchronized(lifecycleLock) { scheduleIdleCheckLocked() }
    }

    private fun scheduleIdleCheckLocked() {
        if (closing) return
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        synchronized(lifecycleLock) { cancelIdleCheckLocked() }
    }

    private fun cancelIdleCheckLocked() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    /** Atomically excludes new references and removes this exact idle session from its owner map. */
    fun tryCloseIfIdle(removeFromOwner: () -> Boolean): Boolean = synchronized(lifecycleLock) {
        if (closing || refCount.get() > 0 || isGenerating) return@synchronized false
        closing = true
        if (!removeFromOwner()) {
            closing = false
            return@synchronized false
        }
        cancelIdleCheckLocked()
        true
    }

    fun cleanup() {
        synchronized(lifecycleLock) {
            closing = true
            cancelIdleCheckLocked()
        }
        val generationToCancel = detachCurrentGeneration()
        generationToCancel?.cancel()
    }

    private data class CurrentGeneration(
        val lease: GenerationLease,
        val runId: String? = null,
    ) {
        fun matches(other: GenerationLease): Boolean =
            lease.epoch == other.epoch && lease.job === other.job
    }

    private data class Installation(
        val lease: GenerationLease,
        val previous: Job?,
    )
}
