package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "SessionRegistry"

/**
 * Owns the per-conversation [ConversationSession] map and its lifecycle (#360 P2), extracted from
 * ChatService so create / get / remove + ref-counting + idle eviction + the cleanup sweep are
 * JVM-unit-testable with real [ConversationSession]s — WITHOUT constructing the full ChatService.
 * ChatService keeps the higher-level orchestration (it delegates getOrCreateSession/removeSession here)
 * and still owns the kill-switch policy ([revokeActiveAutomation]), which sweeps [snapshot].
 *
 * The session-construction inputs are INJECTED so the registry names no settings store / foreground
 * controller: [newConversation] supplies the initial Conversation (the caller resolves the current
 * assistant), and [onGenerationStart]/[onGenerationStop] forward the foreground-service lifecycle.
 */
class SessionRegistry(
    private val scope: CoroutineScope,
    private val newConversation: (Uuid) -> Conversation,
    private val onGenerationStart: () -> Unit,
    private val onGenerationStop: () -> Unit,
) {
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()

    // Bumped on every create/remove so a cold combine over the live session set re-subscribes (the
    // getConversationJobs flow keys on this). Exposed read-only.
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    /**
     * Get the existing session or atomically create one bound to this registry's idle eviction. The
     * created session's `onIdle` calls back into [remove], so an idle session self-evicts.
     */
    fun getOrCreate(conversationId: Uuid): ConversationSession =
        sessions.computeIfAbsent(conversationId) { id ->
            ConversationSession(
                id = id,
                initial = newConversation(id),
                scope = scope,
                onIdle = { remove(it) },
                onGenerationStart = onGenerationStart,
                onGenerationStop = onGenerationStop,
            ).also {
                _version.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }

    /** The session for [conversationId], or null if none is live. */
    fun get(conversationId: Uuid): ConversationSession? = sessions[conversationId]

    /**
     * Evict [conversationId]'s session. A still-in-use session ([ConversationSession.isInUse]) is kept
     * unless [force]. The identity-guarded `remove(key, value)` + cleanup runs only for the exact mapped
     * instance, so a concurrent replacement is never torn down by a stale eviction.
     */
    fun remove(conversationId: Uuid, force: Boolean = false) {
        val session = sessions[conversationId] ?: return
        if (!force && session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _version.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    /** A live view of all sessions (the kill-switch sweep + the jobs flow read this). */
    fun snapshot(): Collection<ConversationSession> = sessions.values

    /** Tear down every session and clear the map (ChatService.cleanup). */
    fun cleanupAll() {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }
}
