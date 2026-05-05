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

/**
 * Process-scoped registry of conversation ids that should run with auto-approval.
 *
 * Used by the cron worker: scheduled jobs are pre-authorised at SCHEDULE time (the
 * `schedule_job` tool prompts the user with an explicit "this job will run without
 * per-tool approval" warning) and run AUTOMATICALLY at FIRE time. Without this, every
 * cron tick that calls a side-effecting tool (termux_run_command, ssh_exec, etc.)
 * would flip to Pending and stall forever — there's no UI surface to grant approval
 * when the user is asleep / away.
 *
 * The HARDLINE floor still applies. `HardlineCommandGuard.checkTool` runs BEFORE the
 * auto-approval lookup in `GenerationHandler`, so a cron job's `rm -rf /` is still
 * blocked at the floor, headless mode or not.
 *
 * Persistence: in addition to the in-memory set, active IDs are written to SharedPreferences
 * so that if the process is killed between mark() and unmark(), the IDs survive. On first
 * call to init(context), the prefs are read back into the in-memory set. The startup sweep
 * in RikkaHubApp clears any orphan conversations left by killed workers.
 */
@OptIn(ExperimentalUuidApi::class)
object HeadlessConversations {

    private val ids: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()
    @Volatile private var prefs: SharedPreferences? = null

    /**
     * Must be called once during app startup (before any worker fires) to restore
     * any IDs that were persisted before a process kill. Safe to call multiple times.
     */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val stored = p.getString(PREFS_KEY, null) ?: return
        stored.split(',').forEach { raw ->
            if (raw.isNotBlank()) {
                runCatching { ids.add(Uuid.parse(raw.trim())) }
                    .onFailure { Log.w(TAG, "init: could not parse stored UUID '$raw'") }
            }
        }
        Log.d(TAG, "init: restored ${ids.size} active IDs from prefs")
    }

    fun mark(conversationId: Uuid) {
        ids.add(conversationId)
        persistIds()
    }

    fun unmark(conversationId: Uuid) {
        ids.remove(conversationId)
        persistIds()
    }

    fun isHeadless(conversationId: Uuid): Boolean = conversationId in ids

    /**
     * Returns a snapshot of all currently-active IDs.
     * Used by the startup sweep to detect orphans from killed workers.
     */
    fun activeIds(): Set<Uuid> = ids.toSet()

    /**
     * Clears both the in-memory set and the persisted prefs.
     * Called after the startup sweep finishes cleaning up orphans.
     */
    fun clearAll() {
        ids.clear()
        persistIds()
    }

    private fun persistIds() {
        val p = prefs ?: return
        val joined = ids.joinToString(",") { it.toString() }
        p.edit().putString(PREFS_KEY, joined).apply()
    }
}
