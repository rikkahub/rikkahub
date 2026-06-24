package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Key 选择结果
 * @param key 选中的 Key（null 表示没有可用 key）
 * @param reason 选择原因，如 "no_keys", "no_available_keys",
 *               "strategy_random", "strategy_round_robin",
 *               "strategy_least_used", "strategy_priority_first"
 */
data class KeySelectionResult(
    val key: ApiKeyConfig? = null,
    val reason: String = "",
)

interface KeyRoulette {
    /** 旧版兼容：从空格/逗号分隔的 key 字符串中随机选一个 */
    fun next(keys: String, providerId: String = ""): String

    /**
     * 根据负载均衡策略从结构化 keys 中选择一个
     * 返回 KeySelectionResult，包含选中的 key 和选择原因
     */
    fun selectKey(
        apiKeys: List<ApiKeyConfig>,
        keyManagement: KeyManagementConfig,
        providerId: String,
    ): KeySelectionResult

    /** 报告调用结果（成功/失败），更新健康度统计 */
    fun reportResult(providerId: String, keyId: String, success: Boolean, error: String? = null)

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /** LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)

        /** 结构化 Key 版本（含多策略 + 健康度追踪 + 持久化） */
        fun structured(context: Context): KeyRoulette = StructuredKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex()

private fun splitKey(key: String): List<String> {
    return key.split(SPLIT_KEY_REGEX).map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

// ===================== 旧版随机策略 =====================

private class DefaultKeyRoulette : KeyRoulette {
    override fun selectKey(
        apiKeys: List<ApiKeyConfig>,
        keyManagement: KeyManagementConfig,
        providerId: String,
    ): KeySelectionResult {
        val active = apiKeys.activeKeys()
        if (active.isEmpty()) {
            val fallback = apiKeys.firstOrNull { it.status != ApiKeyStatus.DISABLED } ?: apiKeys.firstOrNull()
            return if (fallback != null) {
                KeySelectionResult(fallback, "fallback_no_active")
            } else {
                KeySelectionResult(null, "no_keys")
            }
        }
        return KeySelectionResult(active.random(), "strategy_random")
    }

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) keyList.random() else keys
    }

    override fun reportResult(providerId: String, keyId: String, success: Boolean, error: String?) {}
}

// ===================== LRU 轮询（旧版持久化） =====================

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L
private object LruFileLock
private typealias LruCache = Map<String, Map<String, Long>>

private class LruKeyRoulette(private val context: Context) : KeyRoulette {
    override fun selectKey(
        apiKeys: List<ApiKeyConfig>,
        keyManagement: KeyManagementConfig,
        providerId: String,
    ): KeySelectionResult {
        val active = apiKeys.activeKeys()
        if (active.isEmpty()) {
            val fb = apiKeys.firstOrNull { it.status != ApiKeyStatus.DISABLED } ?: apiKeys.firstOrNull()
            return KeySelectionResult(fb, if (fb != null) "fallback_no_active" else "no_keys")
        }
        return KeySelectionResult(active.random(), "strategy_random")
    }

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()
            val selected = keyList.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key
            providerCache[selected] = now
            allCache[providerId] = providerCache
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }
            saveCache(allCache)
            return selected
        }
    }

    override fun reportResult(providerId: String, keyId: String, success: Boolean, error: String?) {}

    private fun loadCache(): LruCache = try {
        val file = File(context.cacheDir, LRU_CACHE_FILE)
        if (!file.exists()) emptyMap() else Json.decodeFromString(file.readText())
    } catch (_: Exception) { emptyMap() }

    private fun saveCache(cache: LruCache) {
        try { File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache)) }
        catch (_: Exception) {}
    }
}

// ===================== 结构化 Key 引擎（推荐） =====================

private const val STRUCTURED_TRACKER_FILE = "structured_key_tracker.json"

/**
 * 结构化 Key 轮换引擎 —— 推荐使用
 *
 * API 设计借鉴 RikkaHubX：selectKey() / reportResult() 分离，返回原因码
 * 健康度追踪 + 持久化借鉴上游 StructuredKeyRoulette
 *
 * 支持策略：
 *  - RANDOM:        随机选择
 *  - ROUND_ROBIN:   轮询（内存计数器，重启重置）
 *  - LEAST_USED:    最少使用（持久化统计）
 *  - PRIORITY_FIRST: 优先级排序（按列表顺序）
 *
 * 健康度自动管理：
 *  - 连续失败超过 maxFailures 即标记为 ERROR
 *  - 冷却期后自动恢复为 ACTIVE
 *  - 用量统计持久化到磁盘，app 重启不丢
 */
private data class TrackerData(
    val failureTracker: Map<String, Map<String, Int>> = emptyMap(),
    val usageCounts: Map<String, Map<String, Long>> = emptyMap(),
)

private class StructuredKeyRoulette(private val context: Context) : KeyRoulette {
    private val roundRobinCounters = mutableMapOf<String, AtomicInteger>()
    private val counterLock = Any()

    private val failureTracker = mutableMapOf<String, MutableMap<String, Int>>()
    private val usageCounts = mutableMapOf<String, MutableMap<String, Long>>()
    private val trackerLock = Any()

    init { loadFromDisk() }

    override fun selectKey(
        apiKeys: List<ApiKeyConfig>,
        keyManagement: KeyManagementConfig,
        providerId: String,
    ): KeySelectionResult {
        val active = apiKeys.activeKeys()
        if (active.isEmpty()) {
            val fb = apiKeys.firstOrNull { it.status != ApiKeyStatus.DISABLED } ?: apiKeys.firstOrNull()
            return KeySelectionResult(fb, if (fb != null) "fallback_no_active" else "no_keys")
        }

        val maxFail = keyManagement.maxFailures
        val recoveryMs = keyManagement.recoveryTime
        val now = System.currentTimeMillis()

        val available = active.filter { key ->
            val fails = synchronized(trackerLock) {
                (failureTracker[providerId]?.get(key.id) ?: 0)
            }
            if (fails < maxFail) true
            else {
                val lastFailAt = key.lastErrorAt ?: return@filter false
                (now - lastFailAt) >= recoveryMs
            }
        }

        if (available.isEmpty()) {
            return KeySelectionResult(null, "no_available_keys")
        }

        val strategy = keyManagement.strategy
        val (selected, reason) = when (strategy) {
            LoadBalanceStrategy.RANDOM -> {
                available.random() to "strategy_random"
            }
            LoadBalanceStrategy.ROUND_ROBIN -> {
                val idx = roundRobinPickIndex(available, providerId)
                available[idx] to "strategy_round_robin"
            }
            LoadBalanceStrategy.LEAST_USED -> {
                val counts = synchronized(trackerLock) { usageCounts[providerId] ?: emptyMap() }
                val key = available.minByOrNull { counts[it.id] ?: 0L } ?: available.first()
                key to "strategy_least_used"
            }
            LoadBalanceStrategy.PRIORITY_FIRST -> {
                available.first() to "strategy_priority_first"
            }
        }

        synchronized(trackerLock) {
            val counts = usageCounts.getOrPut(providerId) { mutableMapOf() }
            counts[selected.id] = (counts[selected.id] ?: 0L) + 1
            saveToDisk()
        }

        return KeySelectionResult(selected, reason)
    }

    private fun roundRobinPickIndex(active: List<ApiKeyConfig>, providerId: String): Int {
        val counter = synchronized(counterLock) {
            roundRobinCounters.getOrPut(providerId) { AtomicInteger(0) }
        }
        return Math.floorMod(counter.getAndIncrement(), active.size)
    }

    override fun reportResult(providerId: String, keyId: String, success: Boolean, error: String?) {
        synchronized(trackerLock) {
            val tracker = failureTracker.getOrPut(providerId) { mutableMapOf() }
            if (success) {
                tracker.remove(keyId)
            } else {
                tracker[keyId] = (tracker[keyId] ?: 0) + 1
            }
            saveToDisk()
        }
    }

    override fun next(keys: String, providerId: String): String {
        return DefaultKeyRoulette().next(keys, providerId)
    }

    private fun trackerFile(): File = File(context.cacheDir, STRUCTURED_TRACKER_FILE)

    private fun loadFromDisk() {
        try {
            val file = trackerFile()
            if (!file.exists()) return
            val data = Json.decodeFromString<TrackerData>(file.readText())
            synchronized(trackerLock) {
                failureTracker.clear()
                failureTracker.putAll(data.failureTracker.mapValues { it.value.toMutableMap() })
                usageCounts.clear()
                usageCounts.putAll(data.usageCounts.mapValues { it.value.toMutableMap() })
            }
        } catch (_: Exception) {}
    }

    private fun saveToDisk() {
        try {
            val snapshot = synchronized(trackerLock) {
                TrackerData(
                    failureTracker = failureTracker.mapValues { it.value.toMap() },
                    usageCounts = usageCounts.mapValues { it.value.toMap() },
                )
            }
            trackerFile().writeText(Json.encodeToString(snapshot))
        } catch (_: Exception) {}
    }
}

// ===================== 工具函数 =====================

/**
 * 从 ProviderSetting 中选取一个有效 Key 并返回 ApiKeyConfig
 * 优先使用结构化 apiKeys，若为空则 fallback 到 apiKey 字符串
 */
fun KeyRoulette.resolveKey(ps: me.rerere.ai.provider.ProviderSetting): ApiKeyConfig {
    val effective = ps.getEffectiveApiKeys()
    if (effective.isEmpty()) {
        return ApiKeyConfig(key = ps.getLegacyApiKey(), name = "Default")
    }
    val result = selectKey(effective, ps.keyManagement, ps.id.toString())
    return result.key ?: ApiKeyConfig(key = ps.getLegacyApiKey(), name = "Fallback")
}

/**
 * 选取 Key → 执行 API 调用 → 报告结果的完整包装
 * 自动处理成功/失败的报告
 */
suspend fun <T> KeyRoulette.callWithKey(
    ps: me.rerere.ai.provider.ProviderSetting,
    block: suspend (apiKey: String, keyConfig: ApiKeyConfig) -> T,
): T {
    val keyConfig = resolveKey(ps)
    try {
        val result = block(keyConfig.key, keyConfig)
        reportResult(ps.id.toString(), keyConfig.id, true)
        return result
    } catch (e: Exception) {
        reportResult(ps.id.toString(), keyConfig.id, false, e.message)
        throw e
    }
}
