package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.automation.cap.CapabilityGuard
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
    // Generation-job lifecycle hooks. This session is the single owner of the generation job, so it
    // is the correct place to signal start/stop. Plain callbacks keep the session pure/JVM-constructible;
    // ChatService supplies implementations that drive GenerationActivityTracker + the foreground service.
    private val onGenerationStart: () -> Unit = {},
    private val onGenerationStop: () -> Unit = {},
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

    // 自动压缩熔断器（design #193 R5）。一旦某会话已不可逆地超出上限，token 触发器会在每一轮都触发压缩，
    // 而每次压缩本身又是一次注定失败的模型调用——已知的昂贵失败模式（可累积数千次连续无效调用）。
    // 连续非取消失败累加；成功清零；用户取消（CancellationException）不计入（CB3）。跨轮存活、随会话重置，
    // 故内聚在 session 上。仅在 sendMessage 协程内单线程读写，@Volatile 足以保证跨调度器可见性。
    @Volatile
    var consecutiveAutoCompactFailures: Int = 0

    // Per-generation UI-automation capability guard (#187 v1). Minted in ChatService once per
    // generation when the assistant has automation enabled, closed over by the ui_observe tool, and
    // revoked by either kill-switch (floating STOP overlay or the in-app generation Stop). Lives on
    // the session so the kill-switch can reach the currently-active grant; cleared when generation
    // ends. Only touched from the sendMessage coroutine + the kill-switch thread, so @Volatile.
    @Volatile
    var activeAutomationGuard: CapabilityGuard? = null

    /** Kill-switch (design I9): revoke the active automation grant — future authorize ⇒ DENY. */
    fun revokeAutomation() {
        activeAutomationGuard?.revoke()
    }

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
        // Cancelling the previous job fires its invokeOnCompletion -> onGenerationStop, so the
        // start/stop signals stay balanced even when a new generation replaces an in-flight one.
        _generationJob.value?.cancel()
        _generationJob.value = job
        if (job != null) {
            onGenerationStart()
            job.invokeOnCompletion {
                _generationJob.value = null
                onGenerationStop()
                if (refCount.get() <= 0) {
                    scheduleIdleCheck()
                }
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
        _generationJob.value?.cancel()
        _generationJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
