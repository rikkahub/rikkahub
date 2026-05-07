package me.rerere.rikkahub.workflow.trigger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phase 12 — boot-completed family. The actual receiver is the existing
 * [me.rerere.rikkahub.service.CronBootReceiver] in the manifest, which already handles
 * BOOT_COMPLETED, MY_PACKAGE_REPLACED, and QUICKBOOT_POWERON. We hook into it via
 * [WorkflowBootDispatcher.onBoot] (called from the receiver's coroutine) — see the
 * receiver file for the wiring.
 *
 * This family doesn't own a receiver. Its [sync] just records the matching workflows so
 * [onBoot] can dispatch the next time the receiver fires. Nothing to register/unregister.
 */
internal class BootTriggerFamily(
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "boot"

    @Volatile private var matching: List<WorkflowDefinition> = emptyList()
    @Volatile private var callback: TriggerFireCallback? = null

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.BootCompleted

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        this.matching = matching
        this.callback = callback
    }

    /** Called by [WorkflowBootDispatcher.onBoot] when the manifest boot receiver fires. */
    fun onBoot() {
        val cb = callback ?: return
        val snap = matching
        if (snap.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (wf in snap) {
                if (wf.trigger !is TriggerSpec.BootCompleted) continue
                runCatching { cb.onFire(wf.id, wf.trigger) }.onFailure {
                    Log.w(TAG, "boot fire failed for wf=${wf.id}", it)
                }
            }
        }
    }

    override suspend fun shutdown() {
        matching = emptyList()
        callback = null
    }

    companion object { private const val TAG = "WorkflowTrigger" }
}

/**
 * Bridge from the existing CronBootReceiver to the BootTriggerFamily. The cron boot
 * receiver already calls into [me.rerere.rikkahub.service.CronJobScheduler] on boot for
 * its own purposes — we add a single side-call that asks the workflow registry to fire
 * any boot-triggered workflows. No second manifest receiver.
 */
object WorkflowBootDispatcher {
    @Volatile private var family: BootTriggerFamily? = null

    internal fun bind(f: BootTriggerFamily) { family = f }

    fun onBoot() { family?.onBoot() }
}
