package me.rerere.rikkahub.workflow.trigger

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.java.KoinJavaComponent

/**
 * Phase 12 — geofence triggers via Google Play Services GeofencingClient.
 *
 * One PendingIntent per app, routing through [GeofenceTriggerReceiver] which calls back
 * into this family via [GeofenceTriggerDispatcher]. Each geofence is keyed by workflow id,
 * so add/remove are workflow-scoped.
 *
 * Permissions required at fire-time:
 *  - ACCESS_FINE_LOCATION (Android 6+)
 *  - ACCESS_BACKGROUND_LOCATION (Android 10+ — needed for triggers when the app isn't open)
 *
 * If permissions or Play Services are missing, [sync] logs and skips registration.
 * The registry surfaces this in the workflow row's status as "Unavailable on this device"
 * (per spec — handled at WorkflowEngine.fire time).
 */
internal class GeofenceTriggerFamily(
    private val context: Context,
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "geofence"

    @Volatile private var fireCallback: TriggerFireCallback? = null
    @Volatile private var registered: Map<String, TriggerSpec> = emptyMap()  // workflowId -> spec

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.GeofenceEnter || spec is TriggerSpec.GeofenceExit

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        fireCallback = callback
        GeofenceTriggerDispatcher.bind(this)

        // Permission gate.
        if (!hasGeofencePermissions()) {
            Log.w(TAG, "geofence: missing FINE_LOCATION/BACKGROUND_LOCATION; skipping registration")
            // Tear down any leftover registrations so we don't run in a half-state.
            removeAll()
            return
        }

        val client = runCatching { LocationServices.getGeofencingClient(context) }.getOrNull()
        if (client == null) {
            Log.w(TAG, "geofence: Play Services unavailable; skipping registration")
            return
        }

        val targetMap: Map<String, TriggerSpec> = matching.mapNotNull { wf ->
            val spec = wf.trigger
            if (spec !is TriggerSpec.GeofenceEnter && spec !is TriggerSpec.GeofenceExit) null
            else wf.id to spec
        }.toMap()

        val toRemove = registered.keys - targetMap.keys
        val toAdd = targetMap.filter { (id, spec) -> registered[id] != spec }

        if (toRemove.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching { Tasks.await(client.removeGeofences(toRemove.toList())) }
                    .onFailure { Log.w(TAG, "geofence: removeGeofences failed", it) }
            }
        }

        if (toAdd.isNotEmpty()) {
            val list = toAdd.mapNotNull { (id, spec) ->
                when (spec) {
                    is TriggerSpec.GeofenceEnter -> Geofence.Builder()
                        .setRequestId(id)
                        .setCircularRegion(spec.lat, spec.lng, spec.radiusM.toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                        .build()
                    is TriggerSpec.GeofenceExit -> Geofence.Builder()
                        .setRequestId(id)
                        .setCircularRegion(spec.lat, spec.lng, spec.radiusM.toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                        .build()
                    else -> null
                }
            }
            if (list.isNotEmpty()) {
                val req = GeofencingRequest.Builder()
                    .setInitialTrigger(0)  // don't fire on register
                    .addGeofences(list)
                    .build()
                @Suppress("MissingPermission")  // permission was checked above
                withContext(Dispatchers.IO) {
                    runCatching { Tasks.await(client.addGeofences(req, geofencingPendingIntent())) }
                        .onFailure { Log.w(TAG, "geofence: addGeofences failed", it) }
                }
            }
        }
        registered = targetMap
        Log.d(TAG, "geofence: synced (${targetMap.size} active, +${toAdd.size}, -${toRemove.size})")
    }

    override suspend fun shutdown() {
        removeAll()
        fireCallback = null
    }

    private fun removeAll() {
        if (registered.isEmpty()) return
        val client = runCatching { LocationServices.getGeofencingClient(context) }.getOrNull() ?: return
        runCatching { Tasks.await(client.removeGeofences(registered.keys.toList())) }
            .onFailure { Log.w(TAG, "geofence: removeAll failed", it) }
        registered = emptyMap()
    }

    /** Called by [GeofenceTriggerReceiver]'s coroutine when an event arrives. */
    fun onEvent(workflowIds: List<String>, transition: Int) {
        val snap = registered
        scope.launch(Dispatchers.IO) {
            for (id in workflowIds) {
                // Cold-process drop: the geofencing event can wake the process (and this
                // receiver) before [TriggerRegistry.start] has resynced, so `registered` is
                // empty and the in-memory callback may not be wired. The geofence requestId
                // is the workflow id, so fall back to a direct repository read keyed on it.
                // The engine callback re-checks enabled / cooldown / conditions.
                val spec = snap[id]
                val inMemoryCb = fireCallback
                val cb: TriggerFireCallback
                val fireSpec: TriggerSpec
                if (spec != null && inMemoryCb != null) {
                    cb = inMemoryCb
                    fireSpec = spec
                } else {
                    val loaded = GeofenceTriggerHelper.repositoryLookup(id) ?: continue
                    if (!loaded.entity.enabled) continue
                    val storedSpec = loaded.definition.trigger
                    if (storedSpec !is TriggerSpec.GeofenceEnter && storedSpec !is TriggerSpec.GeofenceExit) continue
                    cb = loaded.fire
                    fireSpec = storedSpec
                }
                val matches = (transition == Geofence.GEOFENCE_TRANSITION_ENTER && fireSpec is TriggerSpec.GeofenceEnter)
                    || (transition == Geofence.GEOFENCE_TRANSITION_EXIT && fireSpec is TriggerSpec.GeofenceExit)
                if (matches) {
                    runCatching { cb.onFire(id, fireSpec) }.onFailure {
                        Log.w(TAG, "geofence: fire callback failed for wf=$id", it)
                    }
                }
            }
        }
    }

    private fun hasGeofencePermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val bg = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!bg) return false
        }
        return true
    }

    private fun geofencingPendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceTriggerReceiver::class.java).apply {
            action = GeofenceTriggerReceiver.ACTION
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    companion object { private const val TAG = "WorkflowTrigger" }
}

/** App-wide bridge so the manifest receiver can find the family at fire time. */
object GeofenceTriggerDispatcher {
    private const val TAG = "WorkflowTrigger"

    /**
     * Bounded window the cold-start path holds the broadcast lease before releasing it. A
     * geofence workflow runs inline ([WorkflowEngine.fire] executes the whole graph) and can
     * far outlast a BroadcastReceiver's allowed execution time, so we give the fire a protected
     * head-start and then release regardless — the fire keeps running best-effort on the app
     * scope. Kept conservatively under the foreground-broadcast timeout. Fully-durable cold
     * firing (surviving process death) would need a WorkManager worker like TimeCron uses.
     */
    private const val COLD_FIRE_LEASE_MS = 8_000L

    @Volatile private var family: GeofenceTriggerFamily? = null
    internal fun bind(f: GeofenceTriggerFamily) { family = f }

    /**
     * Dispatch a geofence transition. When the trigger family is already bound (warm process)
     * it owns the dispatch — including its own stale-`registered`-map repository fallback.
     *
     * When the family is NOT bound, the process was just woken cold by the Play Services
     * geofence broadcast before [TriggerRegistry.start]'s debounced resync ran [GeofenceTriggerFamily.sync],
     * so `family` is null and the event would otherwise be dropped. Handle it here: keep the
     * process alive with the receiver's [BroadcastReceiver.PendingResult] while a Koin-resolved
     * [AppScope] coroutine loads each workflow directly from the repository (requestId == workflow
     * id) and fires it. The engine callback re-checks enabled / cooldown / conditions.
     */
    fun onEvent(
        workflowIds: List<String>,
        transition: Int,
        pendingResult: BroadcastReceiver.PendingResult? = null,
    ) {
        val f = family
        if (f != null) {
            f.onEvent(workflowIds, transition)
            // Warm process: the family launched into the long-lived app scope, so the process
            // stays alive on its own; release the broadcast lease now.
            pendingResult?.finish()
            return
        }
        val scope = runCatching { KoinJavaComponent.getKoin().get<AppScope>() }.getOrNull()
        if (scope == null) {
            Log.w(TAG, "geofence: cold-start dispatch but AppScope unavailable; dropping ${workflowIds.size} event(s)")
            pendingResult?.finish()
            return
        }
        scope.launch(Dispatchers.IO) {
            // Fire each workflow as an INDEPENDENT child so releasing the broadcast lease below
            // (on timeout) cannot cancel an in-flight workflow — the children outlive the join.
            val fires = workflowIds.map { id ->
                launch {
                    runCatching { GeofenceTriggerHelper.lookupAndFire(id, transition) }
                        .onFailure { Log.w(TAG, "geofence: cold-start dispatch failed for wf=$id", it) }
                }
            }
            try {
                // Hold the lease only for a bounded head-start, then release whether or not the
                // fires finished; never hold it across an arbitrarily long workflow.
                withTimeoutOrNull(COLD_FIRE_LEASE_MS) { fires.joinAll() }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}

/**
 * Cold-process fallback. Mirrors [me.rerere.rikkahub.workflow.trigger.TimeCronWorkerHelper]:
 * resolves the repository + engine callback via Koin so the family can stay free of Koin
 * lookups. Keyed on the geofence requestId, which is the workflow id. Null if the workflow
 * row is gone or Koin isn't ready.
 */
internal object GeofenceTriggerHelper {
    data class Loaded(
        val entity: me.rerere.rikkahub.workflow.db.WorkflowEntity,
        val definition: WorkflowDefinition,
        val fire: TriggerFireCallback,
    )

    suspend fun repositoryLookup(workflowId: String): Loaded? = runCatching {
        val repo = KoinJavaComponent.getKoin().get<WorkflowRepository>()
        val loaded = repo.getById(workflowId) ?: return@runCatching null
        val engine = KoinJavaComponent.getKoin().get<WorkflowEngine>()
        Loaded(loaded.entity, loaded.definition, engine.triggerCallback)
    }.getOrNull()

    /**
     * Load a single workflow by id (== geofence requestId) straight from the repository and
     * fire it if its stored trigger matches [transition]. Used by the dispatcher's cold-start
     * path (no bound family). The engine callback re-checks enabled / cooldown / conditions,
     * but we also gate on the entity's enabled flag and the stored spec direction here.
     */
    suspend fun lookupAndFire(workflowId: String, transition: Int) {
        val loaded = repositoryLookup(workflowId) ?: return
        if (!loaded.entity.enabled) return
        val spec = loaded.definition.trigger
        val matches = (transition == Geofence.GEOFENCE_TRANSITION_ENTER && spec is TriggerSpec.GeofenceEnter)
            || (transition == Geofence.GEOFENCE_TRANSITION_EXIT && spec is TriggerSpec.GeofenceExit)
        if (matches) loaded.fire.onFire(workflowId, spec)
    }
}
