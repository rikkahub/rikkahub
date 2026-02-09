package me.rerere.rikkahub.data.container

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台进程管理器
 *
 * 负责管理容器中启动的后台进程的生命周期
 *
 * @property context 应用上下文
 * @property prootManager PRoot容器管理器
 */
@Singleton
class BackgroundProcessManager @Inject constructor(
    private val context: Context
) {
    // 运行时获取PRootManager，避免循环依赖
    private val prootManager: PRootManager
        get() = org.koin.core.context.GlobalContext.get().get()
    companion object {
        private const val TAG = "BackgroundProcessManager"
        // 日志文件大小限制（10MB），internal 以便 PRootManager 访问
        internal const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB
        private const val PROCESS_CHECK_INTERVAL_MS = 5000L    // 进程检查间隔
        private const val MAX_RUNNING_PROCESSES_PER_SANDBOX = 10 // 每个沙箱最多运行10个进程
    }

    // 后台进程映射（内存中）
    private val processes = ConcurrentHashMap<String, BackgroundProcessInfo>()

    // 进程状态流（用于UI观察）
    private val _processStates = MutableStateFlow<List<BackgroundProcessInfo>>(emptyList())
    val processStates: StateFlow<List<BackgroundProcessInfo>> = _processStates.asStateFlow()

    // 应用作用域（用于启动监控协程）
    private val appScope = CoroutineScope(Dispatchers.IO)

    // 监控协程Job
    private var monitoringJob: Job? = null

    init {
        startProcessMonitoring()
    }

    /**
     * 启动后台进程
     *
     * @param sandboxId 沙箱ID
     * @param command 要执行的命令
     * @param tag 可选的用户标签
     * @return ProcessExecutionResult
     */
    suspend fun startBackgroundProcess(
        sandboxId: String,
        command: String,
        tag: String? = null
    ): ProcessExecutionResult = withContext(Dispatchers.IO) {
        try {
            // 检查沙箱进程数限制
            val runningCount = processes.values
                .count { it.sandboxId == sandboxId && it.status == ProcessStatus.RUNNING }

            if (runningCount >= MAX_RUNNING_PROCESSES_PER_SANDBOX) {
                return@withContext ProcessExecutionResult(
                    success = false,
                    processId = "",
                    status = ProcessStatus.FAILED,
                    message = "Too many running processes (max $MAX_RUNNING_PROCESSES_PER_SANDBOX). Please stop some processes first."
                )
            }

            // 生成进程ID
            val processId = generateProcessId()
            val timestamp = System.currentTimeMillis()

            // 创建日志文件
            val logsDir = File(context.filesDir, "sandboxes/$sandboxId/logs").apply { mkdirs() }
            val stdoutFile = File(logsDir, "$processId.stdout.log")
            val stderrFile = File(logsDir, "$processId.stderr.log")

            // 创建进程信息
            val processInfo = BackgroundProcessInfo(
                processId = processId,
                sandboxId = sandboxId,
                command = command,
                status = ProcessStatus.STARTING,
                pid = null,
                stdoutPath = stdoutFile.absolutePath,
                stderrPath = stderrFile.absolutePath,
                createdAt = timestamp,
                startedAt = null,
                exitedAt = null,
                exitCode = null,
                tag = tag
            )

            // 启动进程
            val result = prootManager.execInBackground(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", command),
                processId = processId,
                stdoutFile = stdoutFile,
                stderrFile = stderrFile
            )

            if (result.exitCode == 0) {
                // 进程启动成功
                val startedAt = System.currentTimeMillis()
                // 从 stdout 中解析 PID（格式：Process started with PID: xxx）
                val pid = extractPidFromOutput(result.stdout)

                val updatedInfo = processInfo.copy(
                    status = ProcessStatus.RUNNING,
                    pid = pid,
                    startedAt = startedAt
                )

                // 注意：此时进程对象已经在PRootManager中管理，我们这里只存储信息
                processes[processId] = updatedInfo

                // 更新状态流
                _processStates.value = processes.values.map { it }

                Log.i(TAG, "Started background process: $processId, command: $command")

                ProcessExecutionResult(
                    success = true,
                    processId = processId,
                    status = ProcessStatus.RUNNING,
                    message = "Process started successfully",
                    stdoutFile = stdoutFile.absolutePath,
                    stderrFile = stderrFile.absolutePath,
                    pid = pid
                )
            } else {
                // 进程启动失败
                val failedInfo = processInfo.copy(status = ProcessStatus.FAILED)
                processes[processId] = failedInfo
                _processStates.value = processes.values.map { it }

                ProcessExecutionResult(
                    success = false,
                    processId = processId,
                    status = ProcessStatus.FAILED,
                    message = "Failed to start process: ${result.stderr}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting background process", e)
            ProcessExecutionResult(
                success = false,
                processId = "",
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}"
            )
        }
    }

    /**
     * 获取进程信息
     */
    fun getProcess(processId: String): BackgroundProcessInfo? {
        return processes[processId]
    }

    /**
     * 获取沙箱的所有进程
     */
    fun getProcessesBySandbox(sandboxId: String): List<BackgroundProcessInfo> {
        return processes.values
            .filter { it.sandboxId == sandboxId }
    }

    /**
     * 获取所有进程
     */
    fun getAllProcesses(): List<BackgroundProcessInfo> {
        return processes.values.toList()
    }

    /**
     * 终止进程
     */
    suspend fun killProcess(processId: String): ProcessExecutionResult = withContext(Dispatchers.IO) {
        try {
            val managedProcess = processes[processId]
                ?: return@withContext ProcessExecutionResult(
                    success = false,
                    processId = processId,
                    status = ProcessStatus.FAILED,
                    message = "Process not found: $processId"
                )

            val result = prootManager.killBackgroundProcess(processId)

            if (result.exitCode == 0) {
                val updatedInfo = managedProcess.copy(
                    status = ProcessStatus.STOPPED,
                    exitedAt = System.currentTimeMillis()
                )
                processes[processId] = updatedInfo
                _processStates.value = processes.values.toList()

                ProcessExecutionResult(
                    success = true,
                    processId = processId,
                    status = ProcessStatus.STOPPED,
                    message = "Process stopped successfully"
                )
            } else {
                ProcessExecutionResult(
                    success = false,
                    processId = processId,
                    status = managedProcess.status,
                    message = result.stderr
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error killing process: $processId", e)
            ProcessExecutionResult(
                success = false,
                processId = processId,
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}"
            )
        }
    }

    /**
     * 读取进程日志
     */
    suspend fun readProcessLogs(
        processId: String,
        stream: String = "stdout",
        offset: Int = 0,
        limit: Int = 1000
    ): LogReadResult = withContext(Dispatchers.IO) {
        try {
            val processInfo = getProcess(processId)
                ?: return@withContext LogReadResult(
                    lines = emptyList(),
                    totalLines = 0,
                    hasMore = false,
                    error = "Process not found: $processId"
                )

            val logFile = when (stream) {
                "stdout" -> File(processInfo.stdoutPath)
                "stderr" -> File(processInfo.stderrPath)
                else -> return@withContext LogReadResult(
                    lines = emptyList(),
                    totalLines = 0,
                    hasMore = false,
                    error = "Invalid stream: $stream"
                )
            }

            if (!logFile.exists()) {
                return@withContext LogReadResult(
                    lines = emptyList(),
                    totalLines = 0,
                    hasMore = false
                )
            }

            // 读取所有行
            val allLines = logFile.readLines()
            val totalLines = allLines.size

            // 应用偏移和限制
            val lines = if (offset < allLines.size) {
                val end = minOf(offset + limit, allLines.size)
                allLines.subList(offset, end)
            } else {
                emptyList()
            }

            LogReadResult(
                lines = lines,
                totalLines = totalLines,
                hasMore = offset + limit < totalLines
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading logs for process: $processId", e)
            LogReadResult(
                lines = emptyList(),
                totalLines = 0,
                hasMore = false,
                error = "Error: ${e.message}"
            )
        }
    }

    /**
     * 清理已结束的进程记录
     */
    suspend fun cleanupOldProcesses(olderThan: Long = 24 * 60 * 60 * 1000L): Int = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val toRemove = processes.values
                .filter { it.exitedAt != null && (now - it.exitedAt!!) > olderThan }
                .map { it.processId }

            toRemove.forEach { processId ->
                val info = processes[processId]
                // 删除对应的日志文件
                info?.let {
                    try {
                        File(it.stdoutPath).delete()
                        File(it.stderrPath).delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete log files for process $processId", e)
                    }
                }
                processes.remove(processId)
            }
            _processStates.value = processes.values.toList()

            Log.i(TAG, "Cleaned up ${toRemove.size} old processes and their log files")
            toRemove.size
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old processes", e)
            0
        }
    }

    /**
     * 清理指定沙箱的所有进程
     */
    suspend fun cleanupSandboxProcesses(sandboxId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // 终止所有运行中的进程
            val sandboxProcesses = processes.values
                .filter { it.sandboxId == sandboxId && it.status == ProcessStatus.RUNNING }
                .map { it.processId }

            sandboxProcesses.forEach { processId ->
                killProcess(processId)
            }

            // 删除所有进程记录
            val toRemove = processes.filter { it.value.sandboxId == sandboxId }.keys.toList()
            toRemove.forEach { processes.remove(it) }
            _processStates.value = processes.values.toList()

            Result.success(toRemove.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停止所有运行中的进程
     */
    suspend fun stopAllProcesses(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val runningProcesses = processes.values
                .filter { it.status == ProcessStatus.RUNNING }
                .map { it.processId }

            runningProcesses.forEach { processId ->
                killProcess(processId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 标记所有运行中的进程为已停止（容器停止时调用）
     * 与 stopAllProcesses 不同，此方法不尝试 kill 进程（因为容器已杀死它们）
     */
    fun markAllProcessesStopped() {
        val updatedProcesses = processes.values.map { process ->
            if (process.status == ProcessStatus.RUNNING || process.status == ProcessStatus.STARTING) {
                process.copy(
                    status = ProcessStatus.STOPPED,
                    exitedAt = System.currentTimeMillis(),
                    exitCode = -1
                )
            } else {
                process
            }
        }

        updatedProcesses.forEach { process ->
            processes[process.processId] = process
        }

        _processStates.value = processes.values.toList()
        Log.d(TAG, "All processes marked as stopped")
    }

    /**
     * 清理所有进程状态（容器销毁时调用）
     */
    fun clearAllProcesses() {
        processes.clear()
        _processStates.value = emptyList()
        Log.d(TAG, "All process states cleared")
    }

    /**
     * 生成进程ID
     */
    private fun generateProcessId(): String {
        val uuid = UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis().toString(36)
        return "proc_${timestamp}_$uuid"
    }

    /**
     * 启动进程监控协程
     */
    private fun startProcessMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = appScope.launch {
            while (true) {
                try {
                    delay(PROCESS_CHECK_INTERVAL_MS)
                    updateProcessStates()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in process monitoring", e)
                }
            }
        }
        Log.i(TAG, "Process monitoring started")
    }

    /**
     * 更新进程状态
     * 定期检查运行中的进程是否还在存活，标记已死亡的进程
     */
    private suspend fun updateProcessStates() {
        val updatedProcesses = processes.values.map { process ->
            if (process.status == ProcessStatus.RUNNING && process.pid != null) {
                // 检查进程是否仍然存活
                if (!isProcessAlive(process.pid)) {
                    // 进程已死亡，标记为失败
                    process.copy(
                        status = ProcessStatus.FAILED,
                        exitedAt = System.currentTimeMillis(),
                        exitCode = -1
                    )
                } else {
                    process
                }
            } else {
                process
            }
        }

        // 更新内存中的进程状态
        updatedProcesses.forEach { process ->
            processes[process.processId] = process
        }

        _processStates.value = processes.values.toList()
    }

    /**
     * 检查进程是否存活
     * 通过检查 /proc/[pid] 目录是否存在来判断
     */
    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            File("/proc/$pid").exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从输出中提取PID
     * 输出格式："Process started with PID: 12345"
     */
    private fun extractPidFromOutput(output: String): Int? {
        return try {
            val pattern = Regex("PID:\\s*(\\d+)")
            val match = pattern.find(output)
            match?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract PID from output: $output", e)
            null
        }
    }
}
