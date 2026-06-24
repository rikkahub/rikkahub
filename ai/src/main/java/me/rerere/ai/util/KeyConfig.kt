package me.rerere.ai.util

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * 单个 API Key 的完整配置
 */
@Serializable
data class ApiKeyConfig(
    val id: String = createKeyId(),
    val key: String = "",
    val name: String = "",
    val status: ApiKeyStatus = ApiKeyStatus.ACTIVE,
    val usage: KeyUsage = KeyUsage(),
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class KeyUsage(
    val totalCalls: Long = 0,
    val successCalls: Long = 0,
    val failedCalls: Long = 0,
    val lastUsedAt: Long? = null,
    val rateLimitedCount: Int = 0,
)

@Serializable
enum class ApiKeyStatus {
    ACTIVE,
    DISABLED,
    ERROR,
    RATE_LIMITED,
    EXHAUSTED,
}

@Serializable
enum class LoadBalanceStrategy {
    RANDOM,
    ROUND_ROBIN,
    LEAST_USED,
    PRIORITY_FIRST,
}

@Serializable
data class KeyManagementConfig(
    val strategy: LoadBalanceStrategy = LoadBalanceStrategy.ROUND_ROBIN,
    val maxFailures: Int = 3,
    val recoveryTime: Long = 30 * 60 * 1000L,
    val rateLimitCoolDown: Long = 60 * 1000L,
    val autoTestOnRecovery: Boolean = true,
)
/**
 * 生成唯一的 Key ID 字符串
 */
private var keyIdCounter = 0L
fun createKeyId(): String {
    val ts = System.currentTimeMillis().toString(36)
    val r = Random.nextLong(0x7fffffff).toString(36)
    keyIdCounter = (keyIdCounter + 1) and 0x7fffffff
    val c = keyIdCounter.toString(36)
    return "key_${ts}_${r}_$c"
}

/**
 * 将旧格式的 apiKey 字符串（空格/逗号分隔）转为结构化列表
 */
fun parseLegacyApiKeys(raw: String): List<ApiKeyConfig> {
    val keys = raw.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (keys.isEmpty()) return emptyList()
    return keys.mapIndexed { index, key ->
        ApiKeyConfig(
            key = key,
            name = if (keys.size == 1) "Default" else "Key ${index + 1}",
            createdAt = System.currentTimeMillis(),
        )
    }
}

/**
 * 将结构化 keys 列表序列化为旧格式字符串（去重后空格拼接）
 */
fun serializeToLegacyApiKey(keys: List<ApiKeyConfig>): String {
    return keys.map { it.key }.distinct().joinToString(" ")
}

/**
 * 获取当前有效的 Key（ACTIVE 状态）
 */
fun List<ApiKeyConfig>.activeKeys(): List<ApiKeyConfig> =
    filter { it.status == ApiKeyStatus.ACTIVE && it.key.isNotBlank() }