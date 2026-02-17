package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 子代理进度管理器（全局单例）
 *
 * 由于Tool.execute是同步的，无法直接传递流式进度，
 * 因此使用此管理器作为中间层，后台收集进度并通过 StateFlow 保留最新状态
 */
object SubAgentProgressManager {
    private const val TAG = "SubAgentProgressManager"

    // 存储进行中的子代理任务
    private val activeJobs = ConcurrentHashMap<String, SubAgentJob>()

    // 存储最新进度状态（供UI订阅）
    private val progressStates = ConcurrentHashMap<String, SubAgentProgressState>()

    // StateFlow 保留所有进度状态（自动向新订阅者发送最新状态）
    private val _progressStatesFlow = MutableStateFlow<Map<String, SubAgentProgressState>>(emptyMap())
    val progressStatesFlow: StateFlow<Map<String, SubAgentProgressState>> = _progressStatesFlow.asStateFlow()

    // 用于通知UI更新的流（事件通知）
    private val _progressUpdates = MutableSharedFlow<Pair<String, SubAgentProgressState>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val progressUpdates: SharedFlow<Pair<String, SubAgentProgressState>> = _progressUpdates.asSharedFlow()

    // 定期清理已完成的任务（延迟清理）
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SubAgentCleanup").apply { isDaemon = true }
    }

    init {
        // 每30秒清理一次已完成的任务（保留5分钟）
        cleanupExecutor.scheduleAtFixedRate(
            {
                // 在协程作用域中执行清理
                CoroutineScope(Dispatchers.IO).launch {
                    cleanupExpiredJobs()
                }
            },
            30L,  // 初始延迟30秒
            30L,  // 每30秒执行一次
            TimeUnit.SECONDS
        )
    }

    /**
     * 清理已完成的过期任务（保留5分钟）
     */
    private fun cleanupExpiredJobs() {
        val expireTime = System.currentTimeMillis() - 5 * 60 * 1000  // 5分钟前

        // 找出已完成的过期任务
        val expiredJobs = activeJobs.filter { (_, job) ->
            job.completedAt > 0 && job.completedAt < expireTime
        }

        if (expiredJobs.isNotEmpty()) {
            Log.d(TAG, "Cleaning up ${expiredJobs.size} expired sub-agent jobs")
            expiredJobs.forEach { (toolCallId, _) ->
                activeJobs.remove(toolCallId)
                progressStates.remove(toolCallId)
            }
            // 更新 StateFlow（已在协程作用域中）
            _progressStatesFlow.tryEmit(progressStates.toMap())
        }
    }
    /**
     * 启动子代理任务并开始收集进度
     *
     * @param toolCallId 工具调用的唯一ID
     * @param arguments 工具调用参数（用于UI匹配）
     * @param flow 子代理执行的进度流
     * @return 包含初始状态的ToolCall metadata
     */
    fun startSubAgent(
        toolCallId: String,
        arguments: JsonObject,
        flow: Flow<SubAgentProgress>
    ): JsonObject {
        // 创建初始状态
        val initialState = SubAgentProgressState(
            toolCallId = toolCallId,
            status = "running",
            stepIndex = 0,
            totalSteps = 50,
            currentMessage = "初始化子代理...",
            toolCalls = emptyList(),
            textChunks = emptyList(),
            result = null,
            error = null,
            initialArguments = arguments  // 保存arguments用于UI匹配
        )

        // 存储初始状态并更新 StateFlow
        progressStates[toolCallId] = initialState
        _progressStatesFlow.tryEmit(progressStates.toMap())

        // 启动协程收集进度
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                flow.collect { progress ->
                    val currentState = progressStates[toolCallId] ?: initialState
                    val newState = when (progress) {
                        is SubAgentProgress.StepUpdate -> {
                            currentState.copy(
                                stepIndex = progress.stepIndex,
                                totalSteps = progress.totalSteps,
                                currentMessage = progress.currentMessage,
                                toolCalls = progress.toolCalls,
                                totalToolCalls = progress.totalToolCalls
                            )
                        }
                        is SubAgentProgress.ToolCallUpdate -> {
                            val updatedToolCalls = currentState.toolCalls + progress.toolName
                            currentState.copy(
                                toolCalls = updatedToolCalls.distinct()
                            )
                        }
                        is SubAgentProgress.TextChunk -> {
                            val chunks = currentState.textChunks + progress.content
                            currentState.copy(
                                textChunks = chunks,
                                currentMessage = progress.content.take(100)
                            )
                        }
                        is SubAgentProgress.Complete -> {
                            currentState.copy(
                                status = "completed",
                                result = progress.result
                            )
                        }
                        is SubAgentProgress.Error -> {
                            currentState.copy(
                                status = "error",
                                error = progress.error
                            )
                        }
                    }

                    progressStates[toolCallId] = newState
                    _progressUpdates.emit(toolCallId to newState)
                    _progressStatesFlow.emit(progressStates.toMap())  // 更新 StateFlow
                }
            } catch (e: Exception) {
                val errorState = (progressStates[toolCallId] ?: initialState).copy(
                    status = "error",
                    error = e.message ?: "Unknown error"
                )
                progressStates[toolCallId] = errorState
                _progressUpdates.emit(toolCallId to errorState)
                _progressStatesFlow.emit(progressStates.toMap())
            }
        }

        // 保存job以便后续取消
        activeJobs[toolCallId] = SubAgentJob(
            job = job,
            toolCallId = toolCallId,
            completedAt = 0  // 0 表示未完成
        )

        // 返回初始metadata
        return initialState.toMetadata()
    }
    /**
     * 获取指定任务的当前进度状态
     */
    fun getProgressState(toolCallId: String): SubAgentProgressState? {
        return progressStates[toolCallId]
    }

    /**
     * 检查任务是否仍在运行
     */
    fun isRunning(toolCallId: String): Boolean {
        val job = activeJobs[toolCallId]
        return job?.job?.isActive == true
    }

    /**
     * 等待任务完成并获取最终结果
     *
     * @param toolCallId 工具调用ID
     * @param timeoutMs 超时时间（毫秒）
     * @return 最终结果，如果超时则返回null
     */
    suspend fun awaitResult(toolCallId: String, timeoutMs: Long = 900000): SubAgentResult? {
        return withTimeoutOrNull(timeoutMs) {
            // 等待直到状态变为completed或error
            progressUpdates
                .filter { it.first == toolCallId }
                .first { (_, state) ->
                    state.status == "completed" || state.status == "error"
                }
                .second
                .result
        }
    }

    /**
     * 获取最终执行结果（同步阻塞，用于Tool.execute返回最终结果）
     *
     * @param toolCallId 工具调用ID
     * @param timeoutMs 超时时间（毫秒），默认15分钟
     * @return 执行结果
     */
    fun getFinalResult(toolCallId: String, timeoutMs: Long = 900000): SubAgentResult? {
        return runBlocking {
            awaitResult(toolCallId, timeoutMs)
        }
    }

    /**
     * 标记任务为已完成（不立即删除状态，保留给UI读取）
     * 实际清理由定期任务执行
     */
    fun markCompleted(toolCallId: String) {
        val job = activeJobs[toolCallId]
        if (job != null) {
            activeJobs[toolCallId] = job.copy(completedAt = System.currentTimeMillis())
            Log.d(TAG, "Marked sub-agent job as completed: $toolCallId")
        }
    }

    /**
     * 立即清理指定任务（强制删除，通常不需要手动调用）
     */
    fun forceCleanup(toolCallId: String) {
        activeJobs[toolCallId]?.job?.cancel()
        activeJobs.remove(toolCallId)
        progressStates.remove(toolCallId)
        _progressStatesFlow.tryEmit(progressStates.toMap())
        Log.d(TAG, "Force cleaned up sub-agent job: $toolCallId")
    }

    /**
     * 清理所有任务
     */
    fun cleanupAll() {
        activeJobs.values.forEach { it.job.cancel() }
        activeJobs.clear()
        progressStates.clear()
        _progressStatesFlow.tryEmit(emptyMap())
    }
}

/**
 * 子代理任务包装
 */
private data class SubAgentJob(
    val job: Job,
    val toolCallId: String,
    val completedAt: Long = 0  // 完成时间戳，0表示未完成
)

/**
 * 子代理进度状态（可序列化为metadata）
 */
data class SubAgentProgressState(
    val toolCallId: String,
    val status: String, // "running", "completed", "error"
    val stepIndex: Int,
    val totalSteps: Int?,
    val currentMessage: String,
    val toolCalls: List<String>,
    val textChunks: List<String>,
    val result: SubAgentResult?,
    val error: String?,
    val initialArguments: JsonObject = JsonObject(emptyMap()),  // 用于UI匹配
    val totalToolCalls: Int = 0  // 累计工具调用次数
) {
    /**
     * 将状态转换为JSON metadata
     */
    fun toMetadata(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("subagent_progress"))
        put("toolCallId", JsonPrimitive(toolCallId))
        put("status", JsonPrimitive(status))
        put("stepIndex", JsonPrimitive(stepIndex))
        put("totalSteps", JsonPrimitive(totalSteps))
        put("currentMessage", JsonPrimitive(currentMessage))
        put("toolCalls", JsonArray(toolCalls.map { JsonPrimitive(it) }))
        put("textPreview", JsonPrimitive(textChunks.takeLast(5).joinToString("").take(200)))
        put("hasResult", JsonPrimitive(result != null))
        put("error", JsonPrimitive(error))
        put("totalToolCalls", JsonPrimitive(totalToolCalls))
    }

    /**
     * 获取完整文本内容
     */
    fun getFullText(): String = textChunks.joinToString("")
}

/**
 * 为UIMessagePart.ToolCall创建包含子代理进度的metadata
 */
fun createSubAgentToolCallMetadata(
    toolCallId: String,
    agentName: String,
    task: String,
    progressState: SubAgentProgressState? = null
): JsonObject = buildJsonObject {
    put("toolType", JsonPrimitive("spawn_subagent"))
    put("agentName", JsonPrimitive(agentName))
    put("task", JsonPrimitive(task))
    progressState?.let {
        put("progress", it.toMetadata())
    }
}
