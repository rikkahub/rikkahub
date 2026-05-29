package me.rerere.ai.util

import me.rerere.ai.provider.ApiKeyConfig
import me.rerere.ai.provider.ApiKeyStatus
import me.rerere.ai.provider.KeyManagementConfig
import me.rerere.ai.provider.LoadBalanceStrategy
import me.rerere.ai.provider.ProviderSetting
import kotlin.random.Random

/**
 * Key 选择结果
 */
data class KeySelectionResult(
    val key: ApiKeyConfig?,
    val reason: String,
)

/**
 * API Key 管理器
 *
 * 参考 Kelivo 的 ApiKeyManager 实现，支持多种负载均衡策略：
 * - ROUND_ROBIN: 轮询选择
 * - PRIORITY: 按优先级选择
 * - LEAST_USED: 选择使用次数最少的
 * - RANDOM: 随机选择
 *
 * 同时跟踪 Key 的使用状态，自动标记失败的 Key。
 */
class KeyRoulette {
    private val roundRobinIndexMap = mutableMapOf<String, Int>()
    private val keyUsageMap = mutableMapOf<String, Int>()

    /**
     * 从 ProviderSetting 中选择一个 Key
     */
    fun selectForProvider(provider: ProviderSetting): KeySelectionResult {
        val keys = provider.getEffectiveApiKeys()
        if (keys.isEmpty()) {
            // 回退到旧的 apiKey 字段
            val singleKey = provider.getSingleApiKey()
            if (singleKey.isNotBlank()) {
                val config = ApiKeyConfig.create(singleKey)
                return KeySelectionResult(config, "single_key_fallback")
            }
            return KeySelectionResult(null, "no_keys")
        }

        // 过滤可用 Key
        val now = System.currentTimeMillis()
        val cooldownMs = provider.keyManagement.failureRecoveryTimeMinutes * 60 * 1000L

        val available = keys.filter { k ->
            when (k.status) {
                ApiKeyStatus.DISABLED -> false
                ApiKeyStatus.ERROR -> {
                    // 冷却期过后可再次使用
                    (now - k.updatedAt) >= cooldownMs
                }
                else -> true // ACTIVE, RATE_LIMITED 仍可用
            }
        }

        if (available.isEmpty()) {
            return KeySelectionResult(null, "no_available_keys")
        }

        val chosen = when (provider.keyManagement.strategy) {
            LoadBalanceStrategy.PRIORITY -> {
                available.minBy { it.priority }
            }

            LoadBalanceStrategy.LEAST_USED -> {
                available.minBy { it.usage.totalRequests }
            }

            LoadBalanceStrategy.RANDOM -> {
                available[Random.nextInt(available.size)]
            }

            LoadBalanceStrategy.ROUND_ROBIN -> {
                val sorted = available.sortedBy { it.id }
                val cur = roundRobinIndexMap[provider.id.toString()]
                    ?: provider.keyManagement.roundRobinIndex
                val idx = cur % sorted.size
                val chosen = sorted[idx]
                roundRobinIndexMap[provider.id.toString()] = (idx + 1) % sorted.size
                chosen
            }
        }

        return KeySelectionResult(chosen, "strategy_${provider.keyManagement.strategy.name}")
    }

    /**
     * 记录 Key 使用结果（成功/失败），更新状态
     */
    fun recordKeyResult(
        provider: ProviderSetting,
        key: ApiKeyConfig,
        success: Boolean,
        error: String? = null,
    ): ApiKeyConfig {
        val now = System.currentTimeMillis()
        val newConsecutiveFailures = if (success) 0 else (key.usage.consecutiveFailures + 1)

        val newStatus = when {
            success -> ApiKeyStatus.ACTIVE
            newConsecutiveFailures >= provider.keyManagement.maxFailuresBeforeDisable -> ApiKeyStatus.ERROR
            else -> key.status
        }

        val updated = key.copy(
            usage = key.usage.copy(
                totalRequests = key.usage.totalRequests + 1,
                successfulRequests = key.usage.successfulRequests + (if (success) 1 else 0),
                failedRequests = key.usage.failedRequests + (if (success) 0 else 1),
                consecutiveFailures = newConsecutiveFailures,
                lastUsed = now,
            ),
            status = newStatus,
            lastError = if (success) null else (error ?: key.lastError),
            updatedAt = now,
        )

        keyUsageMap[updated.id] = (keyUsageMap[updated.id] ?: 0) + 1
        return updated
    }

    /**
     * 向后兼容：从逗号/空格分隔的字符串中随机选一个 Key
     */
    fun next(keys: String): String {
        val result = selectLegacy(keys)
        return result ?: keys
    }

    private fun selectLegacy(keys: String): String? {
        val keyList = SPLIT_KEY_REGEX.split(keys)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else null
    }

    companion object {
        private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex()

        fun default(): KeyRoulette = KeyRoulette()
    }
}

/**
 * 从 ProviderSetting 中解析出要使用的 API Key
 * 优先使用多 Key 管理，回退到旧字段
 */
fun ProviderSetting.resolveApiKey(roulette: KeyRoulette): String {
    val result = roulette.selectForProvider(this)
    return result.key?.key ?: getSingleApiKey()
}

/**
 * 更新 ProviderSetting 中指定 Key 的使用状态
 */
fun ProviderSetting.updateKeyResult(
    roulette: KeyRoulette,
    keyId: String,
    success: Boolean,
    error: String? = null,
): ProviderSetting {
    val updatedKeys = apiKeys.map { config ->
        if (config.id == keyId) {
            roulette.recordKeyResult(this, config, success, error)
        } else config
    }
    return when (this) {
        is ProviderSetting.OpenAI -> copy(apiKeys = updatedKeys)
        is ProviderSetting.Google -> copy(apiKeys = updatedKeys)
        is ProviderSetting.Claude -> copy(apiKeys = updatedKeys)
    }
}
