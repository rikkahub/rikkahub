package me.rerere.rikkahub.workflow.trigger

import android.Manifest
import android.app.PendingIntent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

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
        val cb = fireCallback ?: return
        val snap = registered
        scope.launch(Dispatchers.IO) {
            for (id in workflowIds) {
                val spec = snap[id] ?: continue
                val matches = (transition == Geofence.GEOFENCE_TRANSITION_ENTER && spec is TriggerSpec.GeofenceEnter)
                    || (transition == Geofence.GEOFENCE_TRANSITION_EXIT && spec is TriggerSpec.GeofenceExit)
                if (matches) {
                    runCatching { cb.onFire(id, spec) }.onFailure {
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
    @Volatile private var family: GeofenceTriggerFamily? = null
    internal fun bind(f: GeofenceTriggerFamily) { family = f }
    fun onEvent(workflowIds: List<String>, transition: Int) {
        family?.onEvent(workflowIds, transition)
    }
}
