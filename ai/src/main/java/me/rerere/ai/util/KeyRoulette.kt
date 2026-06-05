package me.rerere.ai.util

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天
private const val TAG = "LruKeyRoulette"

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

// 文件结构: Map<providerId, Map<apiKey, lastUsedTimestamp>>
private typealias LruCache = Map<String, Map<String, Long>>

/**
 * 仅携带原异常类型名的脱敏异常，用于上报缓存解析失败。
 * 故意不链接原 cause、不复制其 message——原 JsonDecodingException 的 message
 * 会回显被解析的缓存正文（含明文密钥），透传任何引用都会经 Log.w 泄露到 logcat。
 */
internal class RedactedCacheException(causeType: String) :
    Exception("cache parse failed [$causeType]")

/**
 * 纯 JVM 可测的 LRU 持久化 + 选择逻辑，直接操作一个文件目录，不依赖 android.content.Context。
 *
 * 错误经 [onError] 上报（默认写 logcat），调用方可注入记录器用于测试。
 * 注意：上报的 message 绝不能包含 key 内容或缓存正文（都含密钥），只允许操作名 + 异常。
 */
internal class LruKeyRouletteStore(
    private val cacheDir: File,
    private val onError: (String, Throwable) -> Unit = { message, throwable ->
        Log.w(TAG, message, throwable)
    },
) {
    fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()

            // 先对加载的缓存做统一过期清理：丢弃所有 provider 中已过期的条目，
            // 并移除清理后为空的 provider。必须在重新写入当前 provider 的新时间戳之前完成，
            // 否则刚使用的 key（now）会被错误地算作过期。
            val allCache = loadCache()
                .mapValues { (_, cache) ->
                    cache.filterValues { lastUsed -> now - lastUsed < EXPIRE_DURATION_MS }
                }
                .filterValues { it.isNotEmpty() }
                .toMutableMap()

            // 取本 provider 的记录，过滤掉不在当前 key 列表中的条目（过期已在上面统一清理）
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, _) -> k in keyList }
                .toMutableMap()

            // 优先选从未使用的 key，否则选最久未使用的
            val selected = keyList.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache

            saveCache(allCache)
            return selected
        }
    }

    private fun loadCache(): LruCache {
        val file = File(cacheDir, LRU_CACHE_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            Json.decodeFromString(file.readText())
        } catch (e: Exception) {
            // 损坏/不可读的缓存绝不能让密钥选择崩溃：降级为空缓存（最多重启轮询，无数据丢失）。
            // 但要让失败可见——message 只含操作名，不含文件正文/密钥。
            //
            // 不要把解析异常对象本身透传给 onError：kotlinx-serialization 的
            // JsonDecodingException 会在 message 里嵌入被解析输入的片段（"JSON input: ..."），
            // 而这里被解析的正是缓存正文 {providerId:{apiKey:ts}}，含明文密钥。
            // 默认 onError 会 Log.w(TAG, msg, throwable) 把整个异常写入 logcat，
            // 从而泄露密钥。只上报异常类型名，绝不携带其 message/cause/堆栈。
            onError(
                "failed to load key roulette cache, falling back to empty",
                RedactedCacheException(e::class.java.name),
            )
            emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        val target = File(cacheDir, LRU_CACHE_FILE)
        val tmp = File(cacheDir, "$LRU_CACHE_FILE.tmp")
        try {
            // 写临时文件再原子重命名，避免崩溃留下半截 JSON 把缓存写坏
            tmp.writeText(Json.encodeToString(cache))
            if (!tmp.renameTo(target)) {
                onError("failed to rename temp key roulette cache into place", IllegalStateException("renameTo returned false"))
                tmp.delete()
            }
        } catch (e: Exception) {
            onError("failed to save key roulette cache", e)
            tmp.delete()
        }
    }
}

private class LruKeyRoulette(private val context: Context) : KeyRoulette {
    // 延迟解析 cacheDir：原实现仅在 next() 内部访问 context.cacheDir，
    // 构造时不触碰它。保持这一点，避免在从未调用 next() 的场景下（如测试里
    // 用 ContextWrapper(null) 构造 provider）过早解引用 null 的 cacheDir。
    private val store by lazy { LruKeyRouletteStore(context.cacheDir) }

    override fun next(keys: String, providerId: String): String = store.next(keys, providerId)
}
