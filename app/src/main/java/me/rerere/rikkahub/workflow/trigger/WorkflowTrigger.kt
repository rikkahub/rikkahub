package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Callback the trigger family fires when its underlying signal arrives. The registry
 * routes this to [me.rerere.rikkahub.workflow.execution.WorkflowEngine.fire].
 *
 * [matchSpec] is the trigger spec that fired (used to discriminate between, e.g.,
 * `wifi_connected` and `wifi_disconnected` on the same receiver). The engine still
 * checks workflow-level conditions before executing actions.
 */
fun interface TriggerFireCallback {
    suspend fun onFire(workflowId: String, matchSpec: TriggerSpec)
}

/**
 * One trigger family. Lifecycle:
 *   1. registry calls [sync] with the current set of enabled workflows that use this family.
 *   2. family registers/unregisters its underlying receivers / hooks accordingly.
 *   3. on every signal arrival, family iterates its currently-known workflow list,
 *      decides which ones match (e.g. SSID equality), and fires [TriggerFireCallback].
 *
 * Families are stateful — they hold receiver registrations, last-known battery levels,
 * geofence client refs, etc. Construction happens once at app start; [sync] is safe to
 * call repeatedly (it diffs against the family's own state).
 */
interface WorkflowTriggerFamily {
    /** Stable identifier used in logs. */
    val name: String

    /** True if this family handles [spec]. Used by registry to bucket workflows. */
    fun handles(spec: TriggerSpec): Boolean

    /**
     * Reconcile the family's registration state with [matching]. [matching] is the
     * subset of currently-enabled workflows whose trigger this family handles.
     * Pass an empty list to fully unregister.
     */
    suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback)

    /** Tear down everything (called on app shutdown — best effort). */
    suspend fun shutdown()
}
