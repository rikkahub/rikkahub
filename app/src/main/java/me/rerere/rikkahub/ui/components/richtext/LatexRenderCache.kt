package me.rerere.rikkahub.ui.components.richtext

import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 图表渲染结果的 LRU 内存缓存（存 SVG 字符串，体积远小于 Bitmap）。
 */
class LatexRenderCache(
    private val maxEntries: Int = 128
) {
    private val lock = ReentrantLock()

    private val map = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > maxEntries
        }
    }

    fun get(key: String): String? = lock.withLock { map[key] }

    fun put(key: String, svg: String) {
        lock.withLock {
            map[key] = svg
        }
    }

    fun clear() {
        lock.withLock { map.clear() }
    }

    fun onLowMemory() {
        lock.withLock { map.clear() }
    }
}
