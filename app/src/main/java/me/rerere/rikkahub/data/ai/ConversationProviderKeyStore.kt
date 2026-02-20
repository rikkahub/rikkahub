package me.rerere.rikkahub.data.ai

import android.content.Context
import java.security.MessageDigest
import kotlin.uuid.Uuid

class ConversationProviderKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences("conversation_provider_key_store", Context.MODE_PRIVATE)

    fun resolvePinnedKey(conversationId: Uuid, providerId: Uuid, rawKeys: String): String {
        val keyList = splitKeys(rawKeys)
        if (keyList.isEmpty()) return rawKeys
        if (keyList.size == 1) return keyList.first()

        val hash = hashKeys(keyList)
        val conversationKey = buildConversationKey(conversationId = conversationId, providerId = providerId, hash = hash)
        val globalKey = buildGlobalKey(providerId = providerId, hash = hash)

        val assignedIndex = if (preferences.contains(conversationKey)) {
            Math.floorMod(preferences.getInt(conversationKey, 0), keyList.size)
        } else {
            val index = Math.floorMod(preferences.getInt(globalKey, 0), keyList.size)
            preferences.edit()
                .putInt(conversationKey, index)
                .putInt(globalKey, (index + 1) % keyList.size)
                .apply()
            index
        }
        return keyList[assignedIndex]
    }

    fun advancePinnedKey(conversationId: Uuid, providerId: Uuid, rawKeys: String): String? {
        val keyList = splitKeys(rawKeys)
        if (keyList.size <= 1) return null

        val hash = hashKeys(keyList)
        val conversationKey = buildConversationKey(conversationId = conversationId, providerId = providerId, hash = hash)
        val current = if (preferences.contains(conversationKey)) {
            Math.floorMod(preferences.getInt(conversationKey, 0), keyList.size)
        } else {
            Math.floorMod(preferences.getInt(buildGlobalKey(providerId, hash), 0), keyList.size)
        }
        val next = (current + 1) % keyList.size
        preferences.edit().putInt(conversationKey, next).apply()
        return keyList[next]
    }

    private fun buildConversationKey(conversationId: Uuid, providerId: Uuid, hash: String): String {
        return "c:$conversationId:$providerId:$hash"
    }

    private fun buildGlobalKey(providerId: Uuid, hash: String): String {
        return "g:$providerId:$hash"
    }

    private fun hashKeys(keys: List<String>): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(keys.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun splitKeys(keys: String): List<String> {
        return keys
            .split(KEY_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private companion object {
        val KEY_SPLIT_REGEX = Regex("[\\s,]+")
    }
}
