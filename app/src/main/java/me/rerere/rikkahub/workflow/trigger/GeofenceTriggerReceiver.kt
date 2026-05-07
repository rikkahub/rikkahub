package me.rerere.rikkahub.workflow.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.GeofencingEvent

/**
 * Phase 12 — receives the geofence transition broadcast from Play Services and forwards
 * to [GeofenceTriggerDispatcher]. Declared in the manifest because Play Services delivers
 * via `PendingIntent.getBroadcast` outside of any active component.
 */
class GeofenceTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence event error code=${event.errorCode}")
            return
        }
        val transition = event.geofenceTransition
        val workflowIds = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (workflowIds.isEmpty()) return
        GeofenceTriggerDispatcher.onEvent(workflowIds, transition)
    }

    companion object {
        const val TAG = "WorkflowTrigger"
        const val ACTION = "me.rerere.rikkahub.workflow.GEOFENCE_TRANSITION"
    }
}
