package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

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
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
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

    fun setJob(job: Job?) {
        // Atomic swap so two concurrent setJob callers can't race-write a stale job.
        // The previous code (cancel() then assign) had a window where two writers could
        // each read the prior value, A cancels old, B reads old (already cancelled,
        // no-op), A writes newA, B writes newB → A's job is untracked but still running;
        // getJob() returns B; stopGeneration only cancels B; A leaks until completion.
        val previous = _generationJob.getAndUpdate { job }
        previous?.cancel()
        // Identity-checked completion handler: only null the StateFlow if the value is
        // STILL the same job we just set. Without this an out-of-order setJob(B) →
        // A.invokeOnCompletion → clobber-B race could null out the live job.
        job?.invokeOnCompletion {
            _generationJob.compareAndSet(job, null)
            if (refCount.get() <= 0) {
                scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

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
        // Use getAndUpdate (same as setJob) so cleanup() is consistent with the atomic
        // swap used elsewhere. Direct .value = null would bypass the CAS and could
        // theoretically race with a concurrent setJob that's running post-removal
        // (e.g., a coroutine that had already acquired a session reference before
        // dropSession removed it from the map). In practice the risk is tiny because
        // cleanup() is only called after removal, but correctness still matters.
        val job = _generationJob.getAndUpdate { null }
        job?.cancel()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
