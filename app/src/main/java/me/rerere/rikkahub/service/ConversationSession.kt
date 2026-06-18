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
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.ConcurrentHashMap
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

    // Per-run automation scope grant (#187 v2). The transient, user-authorized scope (allowed
    // packages / verbs / sinks / TTL / steps) from an in-chat grant, consumed by the lease derivation
    // to mint activeAutomationGuard. Lives beside the guard because it is the SAME transient lease
    // state on the SAME lifecycle: minted per generation, cleared when the lease tears down. Keeping
    // it on the session lets the kill-switch thread reach it; cleared in lock-step with the guard so a
    // stale prior-run grant can never leak into the next derivation. Same single-writer + kill-switch
    // access shape as the guard, so @Volatile.
    @Volatile
    var pendingAutomationGrant: AutomationGrant? = null

    // Subagent automation guards (Option B): a no-automation PARENT can spawn a subagent that mints
    // its OWN automation lease from the subagent assistant's own grant. That guard is NOT this
    // session's [activeAutomationGuard], but it MUST still be reached by the kill-switch — otherwise a
    // parent with no automation of its own would leave the session looking "inactive" and the
    // floating STOP could neither revoke the subagent's guard nor cancel its (child) coroutine. The
    // subagent run is a structural child of this session's generation job, so revoking the guard +
    // cancelling that job tears the subagent down. Thread-safe: the generation coroutine adds/removes
    // while the kill-switch thread iterates.
    private val subagentAutomationGuards: MutableSet<CapabilityGuard> = ConcurrentHashMap.newKeySet()

    fun addSubagentAutomationGuard(guard: CapabilityGuard) {
        subagentAutomationGuards.add(guard)
    }

    fun removeSubagentAutomationGuard(guard: CapabilityGuard) {
        subagentAutomationGuards.remove(guard)
    }

    /** True iff this session has ANY live automation guard — the main lease OR a subagent lease. */
    fun hasActiveAutomation(): Boolean =
        activeAutomationGuard != null || subagentAutomationGuards.isNotEmpty()

    /**
     * Kill-switch (design I9): revoke EVERY active automation grant on this session — the main lease
     * guard AND every live subagent lease guard — so a future authorize ⇒ DENY on all of them. The
     * caller also cancels the generation job, which tears down the in-flight captures (the subagent
     * runs as a structural child, so it is cancelled too).
     */
    fun revokeAutomation() {
        activeAutomationGuard?.revoke()
        subagentAutomationGuards.forEach { it.revoke() }
    }

    /**
     * Tears down the transient automation lease state. The per-generation guard is ALWAYS dropped —
     * it is minted fresh per `withAutomationLease` entry and never reused. The per-run grant, however,
     * is scoped to the whole TURN, which can span more than one lease entry: an ASK-guardrail approval
     * breaks the turn (a Pending tool waits for the user) and the lease tears down, then the
     * approval-resume re-enters the lease and must re-mint the SAME guard from the SAME grant.
     *
     * So [preserveGrant] decides the grant's fate (finding 3):
     *  - `true`  — the turn is still open (a Pending tool approval is outstanding); KEEP the grant so
     *    the resume re-mints the guard. Clearing it here is the bug: on resume no guard is minted, the
     *    `ui_*` tools are not assembled, and the approved call errors "Tool not found".
     *  - `false` — the turn truly ended; clear the grant too, so a per-run authorization can never
     *    leak onto a LATER, unrelated run (the #187 v2 transient-grant invariant).
     *
     * Idempotent: nulling already-null fields is a no-op.
     */
    fun clearAutomationLeaseState(preserveGrant: Boolean = false) {
        activeAutomationGuard = null
        if (!preserveGrant) pendingAutomationGrant = null
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
                // Identity-guarded: a superseded job can finish completing AFTER the replacement
                // is registered (cancel only starts the wind-down), and its late handler must not
                // clear the replacement — stopGeneration would read null and the new generation
                // would be unstoppable. compareAndSet clears only this job's own registration.
                _generationJob.compareAndSet(job, null)
                onGenerationStop()
                if (refCount.get() <= 0) {
                    scheduleIdleCheck()
                }
            }
        }
    }

    /**
     * Claim the generation slot for a BACKGROUND agent-event drain (#290), but ONLY if no generation
     * is currently live — a background drain must NEVER supersede a live user turn (the difference
     * from [setJob], which always cancels the previous job). [jobFactory] MUST build a LAZILY-started
     * job; on a won claim this registers it with the same start/stop + completion wiring as [setJob]
     * and starts it, returning true. Race-safe: if a concurrent [setJob]/claim took the slot between
     * the idle read and the compareAndSet, the claim fails, the lazy job is cancelled UNSTARTED (no
     * work, no event consumed), and the caller leaves its event buffered for the next genuine
     * turn-end. Makes the drain VISIBLE to idle-gating ([isGenerating]) and to stop/cancel, so a
     * concurrent enqueue cannot start a second drain (NO_DOUBLE_GENERATION).
     */
    fun tryClaimIdleGenerationSlot(jobFactory: () -> Job): Boolean {
        val current = _generationJob.value
        // Occupied if a job is registered AND not yet completed — this includes a job another claim
        // just registered but has not started yet (a LAZY job is NOT isActive until started, so an
        // isActive-only guard would let a second claim CAS over it and double-start the drain).
        if (current != null && !current.isCompleted) return false
        val job = jobFactory()
        if (!_generationJob.compareAndSet(current, job)) {
            job.cancel()
            return false
        }
        onGenerationStart()
        job.invokeOnCompletion {
            _generationJob.compareAndSet(job, null)
            onGenerationStop()
            if (refCount.get() <= 0) {
                scheduleIdleCheck()
            }
        }
        job.start()
        return true
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
        clearAutomationLeaseState()
    }
}
