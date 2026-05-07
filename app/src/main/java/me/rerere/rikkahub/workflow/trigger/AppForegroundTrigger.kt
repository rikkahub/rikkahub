package me.rerere.rikkahub.workflow.trigger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phase 12 — `app_launched` / `app_closed` triggers, fed by the existing accessibility
 * service's foreground-app event stream.
 *
 * The accessibility service calls [AppForegroundDispatcher.onForegroundChange] on every
 * `TYPE_WINDOW_STATE_CHANGED` event with the new package name. This family de-dupes
 * (only fire on actual transition between packages), then matches against the matching
 * workflows.
 *
 * Battery hygiene: the accessibility service itself stays running for screen-automation
 * tools, so this family adds no resource cost when no workflows use it. The family just
 * stops dispatching when [matching] is empty.
 */
internal class AppForegroundTriggerFamily(
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "app_foreground"

    @Volatile private var matching: List<WorkflowDefinition> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null
    @Volatile private var lastForegroundPackage: String? = null

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.AppLaunched || spec is TriggerSpec.AppClosed

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        this.matching = matching
        this.fireCallback = callback
        AppForegroundDispatcher.bind(this)
    }

    override suspend fun shutdown() {
        matching = emptyList()
        fireCallback = null
        lastForegroundPackage = null
    }

    /** Called by [AppForegroundDispatcher.onForegroundChange] from accessibility service. */
    fun onForegroundChange(newPackage: String?) {
        if (newPackage.isNullOrBlank()) return
        val prev = lastForegroundPackage
        lastForegroundPackage = newPackage
        if (prev == newPackage) return  // no transition
        val cb = fireCallback ?: return
        val snap = matching
        if (snap.isEmpty()) return

        val fires = mutableListOf<Pair<String, TriggerSpec>>()
        for (wf in snap) {
            when (val t = wf.trigger) {
                is TriggerSpec.AppLaunched ->
                    if (t.packageName == newPackage) fires += wf.id to t
                is TriggerSpec.AppClosed ->
                    if (prev != null && t.packageName == prev) fires += wf.id to t
                else -> Unit
            }
        }
        if (fires.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for ((wfId, spec) in fires) {
                runCatching { cb.onFire(wfId, spec) }.onFailure {
                    Log.w(TAG, "app_foreground: fire failed for wf=$wfId", it)
                }
            }
        }
    }

    companion object { private const val TAG = "WorkflowTrigger" }
}

/** Bridge from [me.rerere.rikkahub.service.RikkaAccessibilityService] to the family. */
object AppForegroundDispatcher {
    @Volatile private var family: AppForegroundTriggerFamily? = null
    internal fun bind(f: AppForegroundTriggerFamily) { family = f }
    /** Called from accessibility service's TYPE_WINDOW_STATE_CHANGED handler. */
    fun onForegroundChange(packageName: String?) {
        AppForegroundLastKnown.value = packageName
        family?.onForegroundChange(packageName)
    }
}

/** Last-known foreground package, exposed for ContextProvider's foreground_app_* conditions. */
object AppForegroundLastKnown {
    @Volatile var value: String? = null
}
