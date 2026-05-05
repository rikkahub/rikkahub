package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.toolApprovalDataStore by preferencesDataStore(name = "tool_approval")

/**
 * Persistent tool-approval prefs. Two pieces:
 *
 * 1. **"Always Allow" allow-list** — per-tool grants that survive app restart until the
 *    user revokes them from Settings → Tool approvals. The HARDLINE floor still applies
 *    even with Always Allow granted.
 *
 * 2. **"I AM STUPID" global auto-approve** — single boolean that, when true, treats every
 *    tool as pre-approved across every conversation and every surface (in-app, Telegram,
 *    cron). HARDLINE STILL APPLIES — there is no override for that. This is the user's
 *    explicit "I trust the agent fully, stop asking me" escape hatch. Surfaced as a
 *    bright-red toggle behind a confirm dialog because it's a live foot-gun.
 *
 * The companion in-memory "Allow for this chat" allow-list lives in
 * [me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList] and resets on /new or app
 * process restart.
 */
class ToolApprovalPreferences(private val context: Context) {
    private val store = context.toolApprovalDataStore
    private val K_ALWAYS_ALLOW = stringSetPreferencesKey("always_allow_tool_names")
    private val K_GLOBAL_YOLO = booleanPreferencesKey("global_auto_approve_yolo")

    val alwaysAllowFlow: Flow<Set<String>> = store.data.map {
        it[K_ALWAYS_ALLOW].orEmpty()
    }

    /** Live flow of the "I AM STUPID" global auto-approve flag. Default false. */
    val globalYoloFlow: Flow<Boolean> = store.data.map {
        it[K_GLOBAL_YOLO] ?: false
    }

    suspend fun current(): Set<String> = alwaysAllowFlow.first()

    /** Snapshot read of the YOLO flag. Used by the per-call auto-approval check. */
    suspend fun currentYolo(): Boolean = globalYoloFlow.first()

    suspend fun setYolo(enabled: Boolean) {
        store.edit { it[K_GLOBAL_YOLO] = enabled }
    }

    suspend fun grantAlways(toolName: String) {
        store.edit { it[K_ALWAYS_ALLOW] = (it[K_ALWAYS_ALLOW].orEmpty()) + toolName }
    }

    suspend fun revoke(toolName: String) {
        store.edit { it[K_ALWAYS_ALLOW] = (it[K_ALWAYS_ALLOW].orEmpty()) - toolName }
    }

    suspend fun revokeAll() {
        store.edit { it.remove(K_ALWAYS_ALLOW) }
    }
}
