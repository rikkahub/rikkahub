package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceBackgroundCommand
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

private const val TAG = "BackgroundShellManager"

/** 每个会话同时运行的后台命令上限 */
private const val MAX_RUNNING_PER_CONVERSATION = 5

/** 一次读取返回的输出上限; 新输出更多时只返回末尾, 其余留在日志文件里 */
private const val MAX_OUTPUT_BYTES = 16 * 1024

/** 终止命令后等待退出状态写回的时间, 正常情况下 kill 返回时进程已经退出 */
private const val KILL_EXIT_WAIT_MS = 5_000L

enum class BackgroundShellStatus {
    RUNNING,
    EXITED,
    KILLED,
}

data class BackgroundShellTask(
    val id: String,
    val conversationId: Uuid,
    val workspaceId: String,
    val command: String,
    /** 日志在执行环境内的路径 */
    val logPath: String,
    val startedAt: Long,
    val status: BackgroundShellStatus = BackgroundShellStatus.RUNNING,
    val exitCode: Int? = null,
    val endedAt: Long? = null,
    /** 已经自行结束但模型还没读过结果; 期间每一步请求都会提醒模型, 见 BackgroundShellReminderTransformer */
    val unreported: Boolean = false,
)

/** 一次读取的结果, [output] 为上次读取之后的新输出, 超出上限时跳过了中间的 [skippedBytes] 字节 */
class BackgroundShellOutput(
    val task: BackgroundShellTask,
    val output: String,
    val skippedBytes: Long,
)

/**
 * AI 在工作区里启动的后台命令, 按会话归属: 模型只能读取和终止自己会话启动的命令。
 *
 * 任务只保存在内存里, 进程随应用进程一起结束, 应用重启后不再存在。
 * 有命令在运行时由 [BackgroundShellForegroundService] 让进程留在前台。
 */
class BackgroundShellManager(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
) {
    private class Handle(val command: WorkspaceBackgroundCommand) {
        val exited = CompletableDeferred<Unit>()

        @Volatile
        var killing = false

        // 只在 readMutex 内读写
        var readOffset = 0L
    }

    private val handles = ConcurrentHashMap<String, Handle>()
    private val readMutex = Mutex()

    private val _tasks = MutableStateFlow<Map<String, BackgroundShellTask>>(emptyMap())
    val tasks: StateFlow<Map<String, BackgroundShellTask>> = _tasks.asStateFlow()

    fun tasksOf(conversationId: Uuid): List<BackgroundShellTask> =
        _tasks.value.values.filter { it.conversationId == conversationId }.sortedBy { it.startedAt }

    suspend fun start(
        conversationId: Uuid,
        workspaceId: String,
        command: String,
        cwd: String,
    ): BackgroundShellTask {
        val running = tasksOf(conversationId).count { it.status == BackgroundShellStatus.RUNNING }
        require(running < MAX_RUNNING_PER_CONVERSATION) {
            "$running background commands are already running. Wait for one to finish or stop one with workspace_shell_kill."
        }
        val id = newId()
        val backgroundCommand = workspaceRepository.startBackgroundCommand(workspaceId, command, cwd, id)
        val task = BackgroundShellTask(
            id = id,
            conversationId = conversationId,
            workspaceId = workspaceId,
            command = command,
            logPath = backgroundCommand.logPath,
            startedAt = System.currentTimeMillis(),
        )
        val handle = Handle(backgroundCommand)
        handles[id] = handle
        _tasks.update { it + (id to task) }
        BackgroundShellForegroundService.acquire(context, id, conversationId)
        // 每个命令一个守护线程等待退出, 不占用协程的 IO 线程池
        thread(name = "background-shell-$id", isDaemon = true) {
            val exitCode = try {
                backgroundCommand.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "waitFor failed: $id", e)
                -1
            }
            onExit(id, handle, exitCode)
        }
        Log.i(TAG, "start: $id, conversation=$conversationId, workspace=$workspaceId")
        return task
    }

    /** 读取上次之后的新输出; [waitMillis] 大于 0 时先等待命令结束, 超时后照常返回 */
    suspend fun readOutput(conversationId: Uuid, id: String, waitMillis: Long = 0): BackgroundShellOutput {
        val handle = requireHandle(conversationId, id)
        if (waitMillis > 0) {
            withTimeoutOrNull(waitMillis) { handle.exited.await() }
        }
        return readMutex.withLock {
            // 先取状态再读日志: 状态已是结束时, 读到的就是全部输出
            val finished = handle.exited.isCompleted
            val (output, skipped) = withContext(Dispatchers.IO) { readNewOutput(handle, finished) }
            if (finished) markReported(id)
            BackgroundShellOutput(requireTask(conversationId, id), output, skipped)
        }
    }

    suspend fun kill(conversationId: Uuid, id: String): BackgroundShellTask {
        val handle = requireHandle(conversationId, id)
        terminate(handle)
        withTimeoutOrNull(KILL_EXIT_WAIT_MS) { handle.exited.await() }
        // 模型自己终止的命令不再提醒
        markReported(id)
        return requireTask(conversationId, id)
    }

    /** 删除会话时终止它的所有后台命令 */
    suspend fun killConversation(conversationId: Uuid) = killAll { it.conversationId == conversationId }

    /** 删除工作区前终止在其中运行的后台命令, 避免进程继续写入将被删除的目录 */
    suspend fun killWorkspace(workspaceId: String) = killAll { it.workspaceId == workspaceId }

    private suspend fun killAll(predicate: (BackgroundShellTask) -> Boolean) {
        val ids = _tasks.value.values.filter(predicate).map { it.id }.toSet()
        if (ids.isEmpty()) return
        _tasks.update { it - ids }
        val removed = ids.mapNotNull(handles::remove)
        coroutineScope {
            removed.forEach { handle -> launch { terminate(handle) } }
        }
    }

    // 终止过程不受调用方取消影响, 否则进程可能只收到 SIGTERM 而留在后台
    private suspend fun terminate(handle: Handle) {
        if (handle.exited.isCompleted) return
        handle.killing = true
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { handle.command.kill() }.onFailure { Log.e(TAG, "kill failed", it) }
        }
    }

    private fun onExit(id: String, handle: Handle, exitCode: Int) {
        Log.i(TAG, "exit: $id, code=$exitCode, killed=${handle.killing}")
        _tasks.update { tasks ->
            val task = tasks[id] ?: return@update tasks
            tasks + (id to task.copy(
                status = if (handle.killing) BackgroundShellStatus.KILLED else BackgroundShellStatus.EXITED,
                exitCode = exitCode,
                endedAt = System.currentTimeMillis(),
                unreported = !handle.killing,
            ))
        }
        handle.exited.complete(Unit)
        BackgroundShellForegroundService.release(context, id)
    }

    private fun readNewOutput(handle: Handle, finished: Boolean): Pair<String, Long> {
        val size = handle.command.outputSize()
        // 日志被删掉或截断时从头读
        val offset = handle.readOffset.takeIf { it <= size } ?: 0L
        val skipped = (size - offset - MAX_OUTPUT_BYTES).coerceAtLeast(0)
        val bytes = handle.command.readOutput(offset + skipped, (size - offset - skipped).toInt())
        // 运行中的命令可能刚写了半个字符, 留到下次读取; 已结束的不会再有后续字节
        val length = if (finished) bytes.size else bytes.completeUtf8Length()
        handle.readOffset = offset + skipped + length
        return bytes.decodeToString(0, length) to skipped
    }

    private fun markReported(id: String) {
        _tasks.update { tasks ->
            val task = tasks[id]?.takeIf { it.unreported } ?: return@update tasks
            tasks + (id to task.copy(unreported = false))
        }
    }

    private fun requireTask(conversationId: Uuid, id: String): BackgroundShellTask =
        _tasks.value[id]?.takeIf { it.conversationId == conversationId }
            ?: throw IllegalArgumentException(
                "Unknown background command: $id. Background commands do not survive app restarts or conversation deletion."
            )

    private fun requireHandle(conversationId: Uuid, id: String): Handle {
        requireTask(conversationId, id)
        return handles[id] ?: error("Unknown background command: $id")
    }

    private fun newId(): String {
        while (true) {
            val id = Uuid.random().toHexString().take(8)
            if (!handles.containsKey(id)) return id
        }
    }
}

/** 末尾不完整的 UTF-8 字符之前的长度 */
private fun ByteArray.completeUtf8Length(): Int {
    var index = size - 1
    var continuation = 0
    while (index >= 0 && continuation < 3 && (this[index].toInt() and 0xC0) == 0x80) {
        index--
        continuation++
    }
    if (index < 0) return size
    val lead = this[index].toInt() and 0xFF
    val expected = when {
        lead shr 5 == 0x6 -> 2
        lead shr 4 == 0xE -> 3
        lead shr 3 == 0x1E -> 4
        else -> 1
    }
    return if (continuation + 1 < expected) index else size
}
