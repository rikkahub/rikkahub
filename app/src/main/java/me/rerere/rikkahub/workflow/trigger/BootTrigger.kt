package me.rerere.rikkahub.workflow.trigger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.java.KoinJavaComponent

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
        val cb = callback
        val snap = matching
        scope.launch(Dispatchers.IO) {
            // Cold-boot race: BOOT_COMPLETED can wake the receiver before
            // [TriggerRegistry.start]'s repo flow has emitted, so `matching` is empty and
            // `callback` may not be wired yet. Without a fallback the boot-triggered
            // workflows silently never fire. Mirror the time/cron path: read enabled
            // BootCompleted workflows straight from the repository and fire each through
            // the engine callback (which re-checks enabled / cooldown / conditions).
            if (cb != null && snap.isNotEmpty()) {
                for (wf in snap) {
                    if (wf.trigger !is TriggerSpec.BootCompleted) continue
                    runCatching { cb.onFire(wf.id, wf.trigger) }.onFailure {
                        Log.w(TAG, "boot fire failed for wf=${wf.id}", it)
                    }
                }
                return@launch
            }
            val (repoFire, defs) = BootTriggerHelper.repositoryLookup() ?: return@launch
            for (wf in defs) {
                if (wf.trigger !is TriggerSpec.BootCompleted) continue
                runCatching { repoFire.onFire(wf.id, wf.trigger) }.onFailure {
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

/**
 * Cold-boot fallback. Mirrors [me.rerere.rikkahub.workflow.trigger.TimeCronWorkerHelper]:
 * resolves the repository + engine callback via Koin so the family can stay free of Koin
 * lookups. Returns the engine fire callback plus the currently-enabled workflow definitions
 * (boot family filters to BootCompleted at the call site). Null if Koin isn't ready.
 */
internal object BootTriggerHelper {
    suspend fun repositoryLookup(): Pair<TriggerFireCallback, List<WorkflowDefinition>>? = runCatching {
        val repo = KoinJavaComponent.getKoin().get<WorkflowRepository>()
        val engine = KoinJavaComponent.getKoin().get<WorkflowEngine>()
        engine.triggerCallback to repo.listEnabled().map { it.definition }
    }.getOrNull()
}
