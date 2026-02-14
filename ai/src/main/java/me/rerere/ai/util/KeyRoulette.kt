package me.rerere.ai.util

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface KeyCursorStore {
    fun get(scopeKey: String): Int
    fun put(scopeKey: String, nextIndex: Int)
}

interface KeyRoulette {
    fun next(keys: String): String
    fun next(scope: String, keys: String): String

    companion object {
        fun default(cursorStore: KeyCursorStore? = null): KeyRoulette = DefaultKeyRoulette(cursorStore)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex()
private const val DEFAULT_SCOPE = "default"

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette(
    private val cursorStore: KeyCursorStore? = null
) : KeyRoulette {
    private val memoryCursor = ConcurrentHashMap<String, Int>()

    override fun next(keys: String): String = next(DEFAULT_SCOPE, keys)

    @Synchronized
    override fun next(scope: String, keys: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        val scopeKey = buildScopeKey(scope, keyList)
        val rawIndex = memoryCursor[scopeKey] ?: cursorStore?.get(scopeKey) ?: 0
        val index = Math.floorMod(rawIndex, keyList.size)
        val nextIndex = (index + 1) % keyList.size

        memoryCursor[scopeKey] = nextIndex
        cursorStore?.put(scopeKey, nextIndex)
        return keyList[index]
    }

    private fun buildScopeKey(scope: String, keyList: List<String>): String {
        val normalized = keyList.joinToString(separator = "\n")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
        return "$scope|$hash"
    }
}
