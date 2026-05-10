package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "HeadlessConversations"
private const val PREFS_NAME = "headless_conversations"
private const val PREFS_KEY = "active_ids"
private const val PREFS_KEY_AUTO_APPROVE = "auto_approve_ids"

/**
 * Process-scoped registry of conversation ids that run "headless" — i.e. with no
 * in-app chat UI a user can tap into. Two related-but-distinct concepts live here:
 *
 *  1. **Browser-headless routing** (`isHeadless`) — should browser tools run via
 *     `HeadlessBrowserSessionPool` (no visible Activity) instead of launching the
 *     in-app `BrowserActivity`? Also gates the fast-path router skip in `ChatService`,
 *     and the sub-agent recursion guard. Set via `mark()` or `markBrowserHeadless()`.
 *
 *  2. **Auto-approval** (`shouldAutoApprove`) — should side-effecting tools
 *     (termux_run_command, ssh_exec, …) skip the user-approval gate? True only when
 *     the caller has NO approval channel at all (cron / sub-agent / workflow /
 *     skill-tester / external-automation). Set via `mark()` only.
 *
 * The Telegram bot is a deliberate split: it IS browser-headless (the user's phone
 * shouldn't get a popup whenever the LLM browses something), but it is NOT
 * auto-approval — the bot has an inline-keyboard approval flow in TelegramBotService
 * that prompts the user to approve each side-effecting tool. Without the split,
 * marking the bot conv as `mark()` (the original code) silently bypassed the
 * inline-keyboard flow because `isToolAutoApproved` was true via `isHeadless`,
 * and tools auto-fired without ever entering Pending state.
 *
 * The HARDLINE floor still applies to BOTH paths. `HardlineCommandGuard.checkTool`
 * runs BEFORE the auto-approval lookup in `GenerationHandler`, so a cron job's
 * `rm -rf /` is still blocked at the floor regardless of headless mode.
 *
 * Persistence: both sets are written to SharedPreferences so that if the process
 * is killed between mark() and unmark(), the IDs survive. On first call to
 * init(context), the prefs are read back. The startup sweep in RikkaHubApp clears
 * any orphan conversations left by killed workers.
 */
@OptIn(ExperimentalUuidApi::class)
object HeadlessConversations {

    private val ids: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()
    private val autoApproveIds: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()
    @Volatile private var prefs: SharedPreferences? = null

    /**
     * Must be called once during app startup (before any worker fires) to restore
     * any IDs that were persisted before a process kill. Safe to call multiple times.
     */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        p.getString(PREFS_KEY, null)?.split(',')?.forEach { raw ->
            if (raw.isNotBlank()) {
                runCatching { ids.add(Uuid.parse(raw.trim())) }
                    .onFailure { Log.w(TAG, "init: could not parse stored UUID '$raw'") }
            }
        }
        p.getString(PREFS_KEY_AUTO_APPROVE, null)?.split(',')?.forEach { raw ->
            if (raw.isNotBlank()) {
                runCatching { autoApproveIds.add(Uuid.parse(raw.trim())) }
                    .onFailure { Log.w(TAG, "init: could not parse stored auto-approve UUID '$raw'") }
            }
        }
        Log.d(TAG, "init: restored ${ids.size} headless IDs + ${autoApproveIds.size} auto-approve IDs from prefs")
    }

    /**
     * Mark FULLY headless: no in-app UI AND no other approval channel. Tools
     * auto-approve. Use for cron / sub-agent / workflow / skill-tester /
     * external-automation flows.
     */
    fun mark(conversationId: Uuid) {
        ids.add(conversationId)
        autoApproveIds.add(conversationId)
        persistIds()
    }

    /**
     * Mark BROWSER-headless only: the conversation has no in-app UI (so browser
     * tools should run pool-headless and the fast-path router should be skipped),
     * but the caller has its OWN approval channel and tools must still go through
     * the per-tool approval gate. Use for the Telegram bot, which prompts the
     * user via inline keyboards.
     */
    fun markBrowserHeadless(conversationId: Uuid) {
        ids.add(conversationId)
        // Deliberately NOT added to autoApproveIds.
        persistIds()
    }

    fun unmark(conversationId: Uuid) {
        ids.remove(conversationId)
        autoApproveIds.remove(conversationId)
        persistIds()
    }

    /**
     * True if this conversation is browser-headless (caller has no in-app chat UI).
     * Used by browser-tool routing, the fast-path router skip, and the sub-agent
     * recursion guard. Returns true for BOTH `mark()` and `markBrowserHeadless()` callers.
     */
    fun isHeadless(conversationId: Uuid): Boolean = conversationId in ids

    /**
     * True if side-effecting tools should auto-approve in this conversation.
     * Returns true ONLY for `mark()` callers (no approval channel at all).
     * Telegram bot conversations marked via `markBrowserHeadless()` return false
     * here so the inline-keyboard approval flow in TelegramBotService can fire.
     */
    fun shouldAutoApprove(conversationId: Uuid): Boolean = conversationId in autoApproveIds

    /**
     * Returns a snapshot of all currently-active IDs (both headless variants).
     * Used by the startup sweep to detect orphans from killed workers.
     */
    fun activeIds(): Set<Uuid> = ids.toSet()

    /**
     * Clears both in-memory sets and the persisted prefs.
     * Called after the startup sweep finishes cleaning up orphans.
     */
    fun clearAll() {
        ids.clear()
        autoApproveIds.clear()
        persistIds()
    }

    private fun persistIds() {
        val p = prefs ?: return
        p.edit()
            .putString(PREFS_KEY, ids.joinToString(",") { it.toString() })
            .putString(PREFS_KEY_AUTO_APPROVE, autoApproveIds.joinToString(",") { it.toString() })
            .apply()
    }
}
