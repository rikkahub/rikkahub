package me.rerere.rikkahub.workflow.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phase 12 — battery transition triggers. ACTION_BATTERY_CHANGED fires very frequently
 * (every percent change). We track the last-seen level in memory and only fire on the
 * actual transition crossing the threshold, NOT on every broadcast at or below.
 *
 * On first registration, prevLevel is null — treat the FIRST tick as just the seed.
 * Don't fire BatteryBelow on a startup level of 19% if the threshold is 20% — only
 * after the next tick if it stays/drops.
 */
internal class BatteryTriggerFamily(
    private val context: Context,
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "battery"

    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var matching: List<WorkflowDefinition> = emptyList()
    @Volatile private var prevLevel: Int? = null

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.BatteryBelow || spec is TriggerSpec.BatteryAbove

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        this.matching = matching
        if (matching.isEmpty()) {
            unregister()
            return
        }
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 } ?: return
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val pct = (level * 100 / scale).coerceIn(0, 100)
                val prev = prevLevel
                prevLevel = pct
                if (prev == null) return  // seed; no transition to compare against

                val fires = mutableListOf<Pair<String, TriggerSpec>>()
                for (wf in this@BatteryTriggerFamily.matching) {
                    when (val t = wf.trigger) {
                        is TriggerSpec.BatteryBelow -> {
                            if (prev >= t.thresholdPercent && pct < t.thresholdPercent) {
                                fires += wf.id to t
                            }
                        }
                        is TriggerSpec.BatteryAbove -> {
                            if (prev < t.thresholdPercent && pct >= t.thresholdPercent) {
                                fires += wf.id to t
                            }
                        }
                        else -> Unit
                    }
                }
                if (fires.isEmpty()) return
                scope.launch(Dispatchers.IO) {
                    for ((wfId, spec) in fires) {
                        runCatching { callback.onFire(wfId, spec) }.onFailure {
                            Log.w(TAG, "battery fire failed for wf=$wfId", it)
                        }
                    }
                }
            }
        }
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_NOT_EXPORTED else 0
            // ACTION_BATTERY_CHANGED is sticky — the registerReceiver call returns the
            // current Intent, which we pass through to seed prevLevel without firing.
            val sticky = context.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), flags)
            receiver = r
            sticky?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                if (level >= 0) prevLevel = (level * 100 / scale).coerceIn(0, 100)
            }
            Log.d(TAG, "battery: receiver registered, seed=${prevLevel}%, ${matching.size} wf(s)")
        } catch (t: Throwable) {
            Log.w(TAG, "battery: registerReceiver failed", t)
        }
    }

    private fun unregister() {
        val r = receiver ?: return
        runCatching { context.unregisterReceiver(r) }
            .onFailure { Log.w(TAG, "battery: unregisterReceiver failed", it) }
        receiver = null
        prevLevel = null
        Log.d(TAG, "battery: receiver unregistered")
    }

    override suspend fun shutdown() = unregister()

    companion object { private const val TAG = "WorkflowTrigger" }
}
