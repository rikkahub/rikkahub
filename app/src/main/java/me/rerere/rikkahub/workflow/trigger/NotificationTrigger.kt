package me.rerere.rikkahub.workflow.trigger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phase 12 — `notification_received` trigger. Hooks into the existing
 * [me.rerere.rikkahub.service.RikkaNotificationListenerService] via
 * [NotificationTriggerDispatcher]. We don't register a second listener; the existing
 * one calls our dispatcher on every posted notification, and the family decides whether
 * any workflow matches.
 *
 * Filter logic (AND across non-null fields, all case-insensitive):
 *   - package_name (exact match)
 *   - title_contains (substring)
 *   - text_contains (substring)
 * Validator already rejected workflows with no filters (would fire on every notification).
 */
internal class NotificationTriggerFamily(
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "notification"

    @Volatile private var matching: List<WorkflowDefinition> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.NotificationReceived

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        this.matching = matching
        this.fireCallback = callback
        NotificationTriggerDispatcher.bind(this)
    }

    override suspend fun shutdown() {
        matching = emptyList()
        fireCallback = null
    }

    /**
     * Called by [NotificationTriggerDispatcher.onPosted] from the existing notification
     * listener service. All three fields are nullable / sanitised on the listener side.
     */
    fun onPosted(packageName: String?, title: String?, text: String?) {
        val cb = fireCallback ?: return
        val snap = matching
        if (snap.isEmpty()) return
        val fires = mutableListOf<Pair<String, TriggerSpec>>()
        for (wf in snap) {
            val t = wf.trigger as? TriggerSpec.NotificationReceived ?: continue
            if (matches(t, packageName, title, text)) fires += wf.id to t
        }
        if (fires.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for ((wfId, spec) in fires) {
                runCatching { cb.onFire(wfId, spec) }.onFailure {
                    Log.w(TAG, "notification: fire failed for wf=$wfId", it)
                }
            }
        }
    }

    private fun matches(t: TriggerSpec.NotificationReceived, pkg: String?, title: String?, text: String?): Boolean {
        if (!t.packageName.isNullOrBlank() && !t.packageName.equals(pkg, ignoreCase = false)) return false
        if (!t.titleContains.isNullOrBlank()) {
            if (title.isNullOrBlank()) return false
            if (!title.contains(t.titleContains, ignoreCase = true)) return false
        }
        if (!t.textContains.isNullOrBlank()) {
            if (text.isNullOrBlank()) return false
            if (!text.contains(t.textContains, ignoreCase = true)) return false
        }
        return true
    }

    companion object { private const val TAG = "WorkflowTrigger" }
}

/** Bridge from RikkaNotificationListenerService.onNotificationPosted to the family. */
object NotificationTriggerDispatcher {
    @Volatile private var family: NotificationTriggerFamily? = null
    internal fun bind(f: NotificationTriggerFamily) { family = f }
    fun onPosted(packageName: String?, title: String?, text: String?) {
        family?.onPosted(packageName, title, text)
    }
}
