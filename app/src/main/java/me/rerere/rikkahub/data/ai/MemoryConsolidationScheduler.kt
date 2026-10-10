package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** 一个对话里还没整理进记忆库 [memoryId] 的轮数 */
data class PendingMemoryConversation(
    val conversationId: Uuid,
    val memoryId: String,
    val turns: Int,
)

/** 一次整理的结果 */
data class MemoryConsolidationRun(
    val memoryId: String,
    val finishedAt: Long,
    /** 这次整理了哪些对话的新轮次 */
    val conversationIds: Set<Uuid>,
    /** 是否顺带清理了整个记忆库 */
    val fullReview: Boolean,
    /** 整理过程中的工具调用，按先后顺序 */
    val steps: List<UIMessagePart.Tool> = emptyList(),
    val changedPaths: List<String> = emptyList(),
    val deletedPaths: List<String> = emptyList(),
    val error: String? = null,
) {
    val hasChanges: Boolean get() = changedPaths.isNotEmpty() || deletedPaths.isNotEmpty()
}

data class MemoryConsolidationStatus(
    val pendingTurns: Int = 0,
    val running: Boolean = false,
    val lastRun: MemoryConsolidationRun? = null,
)

/** 调度器依赖的整理能力，由 [MemoryConsolidator] 实现 */
interface MemoryConsolidationWorker {
    /** [conversation] 里还没整理的轮次；助手没开记忆或没有新的轮次时返回 null */
    suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation?

    /** 可能有对话没整理完的记忆库。只按更新时间粗筛，不保证真有新的轮次 */
    suspend fun memoryIdsWithUpdates(): List<String>

    /** 从数据库里找出记忆库 [memoryId] 下还有轮次没整理的对话，最多返回一次整理能处理的数量 */
    suspend fun findPending(memoryId: String): List<PendingMemoryConversation>

    /** 把 [conversation] 现有的内容都算作整理过，只有之后新增的轮次才会被整理 */
    suspend fun markConsolidated(conversation: Conversation)

    /**
     * 整理 [conversationIds] 里新增的轮次，[fullReview] 时再把整个记忆库清理一遍。
     * 没有可做的事时返回 null，失败时抛出。
     */
    suspend fun consolidate(
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun?
}

/** 整理期间让进程不被系统冻结或回收；拿不到时返回的句柄什么都不做，整理照常进行 */
fun interface MemoryDreamKeepAlive {
    fun acquire(): AutoCloseable
}

/**
 * 决定什么时候整理记忆：对话空闲一段时间后、攒够轮数时、用户离开对话或切走应用时，或者用户手动触发。
 *
 * 整理以记忆库为单位，一次覆盖这个记忆库下所有有新内容的对话；同一个记忆库同一时间只跑一次。
 */
class MemoryConsolidationScheduler(
    private val scope: CoroutineScope,
    private val worker: MemoryConsolidationWorker,
    private val keepAlive: MemoryDreamKeepAlive = MemoryDreamKeepAlive { AutoCloseable {} },
    // 应用是否有界面在前台
    private val isAppVisible: () -> Boolean = { true },
    private val idleDelay: Duration = 2.minutes,
    private val maxPendingTurns: Int = 6,
) {
    private val lock = Any()

    // memoryId -> (conversationId -> 待整理轮数)
    private val pending = mutableMapOf<String, Map<Uuid, Int>>()
    private val timers = mutableMapOf<String, Job>()
    private val runLocks = mutableMapOf<String, Mutex>()

    // 上次整理失败、之后还没有新轮次的记忆库
    private val failed = mutableSetOf<String>()

    private val status = MutableStateFlow<Map<String, MemoryConsolidationStatus>>(emptyMap())

    fun statusOf(memoryId: String): Flow<MemoryConsolidationStatus> =
        status.map { it[memoryId] ?: MemoryConsolidationStatus() }.distinctUntilChanged()

    private val _automaticRuns = MutableSharedFlow<MemoryConsolidationRun>(extraBufferCapacity = 8)

    /** 自动触发的整理结果；手动触发的结果体现在 [statusOf] 里 */
    val automaticRuns: SharedFlow<MemoryConsolidationRun> = _automaticRuns.asSharedFlow()

    /** 一轮对话结束：空闲一段时间后整理，攒够轮数就不再等 */
    fun onTurnFinished(conversation: Conversation) {
        // 应用不在前台时，聊天生成的前台服务一释放进程就可能被冻结，空闲计时走不完。
        // 所以趁它还在先把保活占住，一直占到这次整理结束
        val hold = if (isAppVisible()) null else keepAlive.acquire()
        scope.launch {
            var handedOver = false
            try {
                val item = worker.pendingOf(conversation) ?: return@launch
                markPending(item)
                if (item.turns >= maxPendingTurns) {
                    runAutomatically(item.memoryId)
                } else {
                    restartTimer(item.memoryId, hold)
                    handedOver = true
                }
            } finally {
                if (!handedOver) hold?.close()
            }
        }
    }

    /** [fork] 是从另一个对话分叉出来的：复制过来的轮次由原对话负责整理，这里不再整理一遍 */
    suspend fun onConversationForked(fork: Conversation) {
        worker.markConsolidated(fork)
    }

    /** 应用被切到后台：之后空闲计时不一定还能走完，有待整理的就现在开始 */
    fun onAppHidden() {
        synchronized(lock) { pending.filterValues { it.isNotEmpty() }.keys.toList() }.forEach(::launchRun)
    }

    /** 用户离开了对话，不会马上再有新消息，不用等到空闲 */
    fun onConversationLeft(conversationId: Uuid) {
        val memoryId = synchronized(lock) {
            pending.entries.firstOrNull { conversationId in it.value }?.key
        } ?: return
        launchRun(memoryId)
    }

    /** 应用启动时补上进程被杀前没来得及整理的对话 */
    suspend fun resumePending() {
        // 每个记忆库各查各的：一起查再截断的话，对话少的记忆库会被对话多的挤掉
        worker.memoryIdsWithUpdates().forEach { memoryId ->
            if (mergePending(memoryId, worker.findPending(memoryId))) restartTimer(memoryId)
        }
    }

    /** 用户手动整理整个记忆库：先处理所有待整理的对话，再把记忆库清理一遍 */
    fun organize(memoryId: String) {
        // 查数据库要一会儿，先标成进行中，免得这期间连点几下排上好几次整理
        if (status.value[memoryId]?.running == true) return
        updateStatus(memoryId) { it.copy(running = true) }
        cancelTimer(memoryId)
        scope.launch {
            var started = false
            try {
                // 进程重启后内存里的待整理列表可能是空的，把数据库里的也算上
                mergePending(memoryId, worker.findPending(memoryId))
                started = true
                run(memoryId, fullReview = true)
            } finally {
                // run 自己会收尾，这里只管没走到它的情况
                if (!started) updateStatus(memoryId) { it.copy(running = false) }
            }
        }
    }

    private fun launchRun(memoryId: String) {
        scope.launch { runAutomatically(memoryId) }
    }

    private suspend fun runAutomatically(memoryId: String) {
        cancelTimer(memoryId)
        run(memoryId, fullReview = false)?.let { _automaticRuns.emit(it) }
    }

    // 整理这个记忆库下所有待整理的对话
    private suspend fun run(
        memoryId: String,
        fullReview: Boolean,
    ): MemoryConsolidationRun? = synchronized(lock) { runLocks.getOrPut(memoryId) { Mutex() } }.withLock {
        val ids = synchronized(lock) {
            // 失败后不自动重试，免得模型一直出错时每次切走应用、离开对话都再调用一遍；
            // 待整理的内容留着，等下一轮对话结束或用户手动触发
            if (!fullReview && memoryId in failed) return null
            pending[memoryId]?.keys.orEmpty()
        }
        if (ids.isEmpty() && !fullReview) return null

        updateStatus(memoryId) { it.copy(running = true) }
        var run: MemoryConsolidationRun? = null
        val hold = keepAlive.acquire()
        try {
            run = try {
                worker.consolidate(memoryId, ids, fullReview)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MemoryConsolidationRun(
                    memoryId = memoryId,
                    finishedAt = System.currentTimeMillis(),
                    conversationIds = ids,
                    fullReview = fullReview,
                    error = e.message ?: e.toString(),
                )
            }
            if (run?.error != null) {
                synchronized(lock) { failed += memoryId }
            } else {
                // 有对话可能因为超出长度预算被留到下一次，从数据库里再找一遍
                synchronized(lock) {
                    failed -= memoryId
                    pending[memoryId] = pending[memoryId].orEmpty() - ids
                }
                if (mergePending(memoryId, worker.findPending(memoryId))) restartTimer(memoryId)
            }
        } finally {
            hold.close()
            updateStatus(memoryId) { it.copy(running = false, lastRun = run ?: it.lastRun) }
        }
        run
    }

    // [hold] 交给定时器管：占到整理结束，定时器被取消或被新的顶掉时放开
    private fun restartTimer(memoryId: String, hold: AutoCloseable? = null) {
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(idleDelay)
            // 整理开始时会取消还在等的定时器，先把自己摘出来，免得被自己取消
            val self = currentCoroutineContext().job
            synchronized(lock) { if (timers[memoryId] === self) timers.remove(memoryId) }
            runAutomatically(memoryId)
        }
        if (hold != null) timer.invokeOnCompletion { hold.close() }
        synchronized(lock) { timers.put(memoryId, timer) }?.cancel()
        timer.start()
    }

    private fun cancelTimer(memoryId: String) {
        synchronized(lock) { timers.remove(memoryId) }?.cancel()
    }

    private fun markPending(item: PendingMemoryConversation) {
        synchronized(lock) { failed -= item.memoryId }
        mergePending(item.memoryId, listOf(item))
    }

    /**
     * 把 [items] 并进待整理列表，返回这个记忆库现在是否有待整理的对话。
     *
     * 不能整个换掉：[items] 是从数据库查出来的，查询期间结束的轮次不在里面，换掉会把它们刚记下的待整理弄丢。
     */
    private fun mergePending(memoryId: String, items: List<PendingMemoryConversation>): Boolean {
        val conversations = synchronized(lock) {
            (pending[memoryId].orEmpty() + items.associate { it.conversationId to it.turns })
                .also { pending[memoryId] = it }
        }
        updateStatus(memoryId) { it.copy(pendingTurns = conversations.values.sum()) }
        return conversations.isNotEmpty()
    }

    private fun updateStatus(memoryId: String, transform: (MemoryConsolidationStatus) -> MemoryConsolidationStatus) {
        status.update { it + (memoryId to transform(it[memoryId] ?: MemoryConsolidationStatus())) }
    }
}
