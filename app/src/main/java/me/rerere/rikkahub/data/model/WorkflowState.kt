package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Workflow 阶段枚举
 */
@Serializable
enum class WorkflowPhase {
    PLAN,      // 规划阶段：只允许只读操作
    EXECUTE,   // 执行阶段：允许所有操作，自动批准
    REVIEW     // 审查阶段：只允许只读操作
}

/**
 * TODO 项状态
 */
/**
 * 记忆块（Compact 后的上下文摘要）
 * @deprecated 已不再使用，保留字段用于向后兼容
 */
@Serializable
data class MemoryChunk(
    val id: String = Uuid.random().toString(),
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    val text: String  // 压缩后的上下文摘要
)

/**
 * Workflow 状态
 * 存储在 Conversation 中，用于编码工作流管理
 */
@Serializable
data class WorkflowState(
    val phase: WorkflowPhase = WorkflowPhase.PLAN,
    val todos: List<TodoItem> = emptyList(),
    @Deprecated("已不再使用，保留字段用于向后兼容")
    val compactMemory: MemoryChunk? = null,
    @Deprecated("已不再使用，保留字段用于向后兼容")
    val keepRecentMessages: Int = 10  // 保留的最近消息数
)
