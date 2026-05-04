package me.rerere.rikkahub.data.ai.tools

import kotlin.uuid.Uuid

/**
 * In-memory "Allow for this chat" allow-list. Holds (conversationId, toolName) entries
 * granted by the user clicking "Allow for this chat" on a tool-approval prompt. Resets on:
 *   - /new in Telegram (handleResetCommand calls clearChat)
 *   - Starting a fresh in-app chat (ChatService can call clearChat on new conversation)
 *   - App process restart (this object is process-local)
 *
 * Persistent "Always Allow" grants live in [ToolApprovalPreferences] (DataStore-backed) and
 * are queried separately so the user can revoke them from Settings without losing per-chat
 * grants.
 */
object ToolApprovalAllowList {

    private val perChat: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun key(conversationId: Uuid, toolName: String) = "${conversationId}::${toolName}"

    /** True iff "Allow for this chat" has been granted for [toolName] in [conversationId]. */
    fun isAllowedForChat(conversationId: Uuid, toolName: String): Boolean =
        perChat.contains(key(conversationId, toolName))

    /** Record an "Allow for this chat" grant. */
    fun grantForChat(conversationId: Uuid, toolName: String) {
        perChat.add(key(conversationId, toolName))
    }

    /** Drop every per-chat grant for the given conversation. Used by /new and the fresh-
     *  chat reset path so a new conversation starts with a clean slate. */
    fun clearChat(conversationId: Uuid) {
        val prefix = "${conversationId}::"
        perChat.removeIf { it.startsWith(prefix) }
    }
}
