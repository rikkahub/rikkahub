package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API Key 状态
 */
@Serializable
enum class ApiKeyStatus {
    /** 正常可用 */
    @SerialName("active")
    ACTIVE,

    /** 已禁用（用户手动） */
    @SerialName("disabled")
    DISABLED,

    /** 错误（连续失败后自动标记） */
    @SerialName("error")
    ERROR,

    /** 被限流 */
    @SerialName("rate_limited")
    RATE_LIMITED
}

/**
 * API Key 使用统计数据
 */
@Serializable
data class ApiKeyUsage(
    val totalRequests: Int = 0,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastUsed: Long? = null,
)

/**
 * API Key 负载均衡策略
 */
@Serializable
enum class LoadBalanceStrategy {
    /** 轮询（默认） */
    @SerialName("round_robin")
    ROUND_ROBIN,

    /** 优先级（数字越小优先级越高） */
    @SerialName("priority")
    PRIORITY,

    /** 最少使用 */
    @SerialName("least_used")
    LEAST_USED,

    /** 随机 */
    @SerialName("random")
    RANDOM
}

/**
 * Key 管理配置
 */
@Serializable
data class KeyManagementConfig(
    /** 负载均衡策略 */
    val strategy: LoadBalanceStrategy = LoadBalanceStrategy.ROUND_ROBIN,
    /** 最大连续失败次数后标记为 error（默认 3） */
    val maxFailuresBeforeDisable: Int = 3,
    /** 失败后恢复冷却时间（分钟） */
    val failureRecoveryTimeMinutes: Int = 5,
    /** 轮询起始索引（仅对 round_robin 有效） */
    val roundRobinIndex: Int = 0,
)

/**
 * 单个 API Key 配置
 */
@Serializable
data class ApiKeyConfig(
    val id: String,
    /** API Key 值 */
    val key: String,
    /** 可选名称/备注 */
    val name: String? = null,
    /** 是否启用 */
    val isEnabled: Boolean = true,
    /** 优先级 1-10，越小优先级越高（默认 5） */
    val priority: Int = 5,
    /** 每分钟最大请求数限制 */
    val maxRequestsPerMinute: Int? = null,
    /** 使用统计 */
    val usage: ApiKeyUsage = ApiKeyUsage(),
    /** 当前状态 */
    val status: ApiKeyStatus = ApiKeyStatus.ACTIVE,
    /** 最后错误信息 */
    val lastError: String? = null,
    /** 创建时间戳 */
    val createdAt: Long,
    /** 更新时间戳 */
    val updatedAt: Long,
) {
    companion object {
        private var counter = 0L

        /**
         * 生成唯一 ID
         */
        fun generateId(): String {
            counter++
            return "key_${System.currentTimeMillis()}_${counter}"
        }

        /**
         * 快速创建新 Key
         */
        fun create(key: String, name: String? = null, priority: Int = 5): ApiKeyConfig {
            val now = System.currentTimeMillis()
            return ApiKeyConfig(
                id = generateId(),
                key = key,
                name = name,
                priority = priority,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
