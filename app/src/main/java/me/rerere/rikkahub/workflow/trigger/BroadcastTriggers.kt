package me.rerere.rikkahub.workflow.trigger

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phase 12 — six trigger families that all share the same shape: register a runtime
 * BroadcastReceiver when at least one workflow uses the family, unregister when the last
 * one is disabled. Receivers run on the registered handler thread; we hop to [scope] to
 * dispatch fires so callbacks don't block the receiver dispatcher.
 *
 * Battery and screen are handled in dedicated files because of transition-tracking and
 * runtime-only registration nuances respectively.
 */
internal abstract class BaseBroadcastTriggerFamily(
    protected val context: Context,
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var matchingSnapshot: List<WorkflowDefinition> = emptyList()

    abstract val intentFilter: IntentFilter
    abstract fun matchEvent(intent: Intent, workflows: List<WorkflowDefinition>): List<Pair<String, TriggerSpec>>

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        matchingSnapshot = matching
        if (matching.isEmpty()) {
            unregisterReceiver()
            return
        }
        ensureReceiverRegistered(callback)
    }

    private fun ensureReceiverRegistered(callback: TriggerFireCallback) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val event = intent ?: return
                val matches = runCatching { matchEvent(event, matchingSnapshot) }.getOrElse {
                    Log.w(TAG, "$name match failed", it); emptyList()
                }
                if (matches.isEmpty()) return
                scope.launch(Dispatchers.IO) {
                    for ((wfId, spec) in matches) {
                        runCatching { callback.onFire(wfId, spec) }.onFailure {
                            Log.w(TAG, "$name fire failed for wf=$wfId", it)
                        }
                    }
                }
            }
        }
        try {
            // RECEIVER_NOT_EXPORTED for Android 14+ — these are app-internal signals.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_NOT_EXPORTED else 0
            context.registerReceiver(r, intentFilter, flags)
            receiver = r
            Log.d(TAG, "$name: receiver registered (${matchingSnapshot.size} workflow(s))")
        } catch (t: Throwable) {
            Log.w(TAG, "$name: registerReceiver failed", t)
        }
    }

    private fun unregisterReceiver() {
        val r = receiver ?: return
        runCatching { context.unregisterReceiver(r) }
            .onFailure { Log.w(TAG, "$name: unregisterReceiver failed", it) }
        receiver = null
        Log.d(TAG, "$name: receiver unregistered")
    }

    override suspend fun shutdown() = unregisterReceiver()

    companion object {
        private const val TAG = "WorkflowTrigger"
    }
}

// -- WiFi connect / disconnect ---------------------------------------------------------

internal class WifiTriggerFamily(context: Context, scope: CoroutineScope)
    : BaseBroadcastTriggerFamily(context, scope) {

    override val name = "wifi"
    override val intentFilter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.WifiConnected || spec is TriggerSpec.WifiDisconnected

    /**
     * SSID remembered from the last connect event. By the time the disconnect broadcast
     * arrives the WifiManager no longer reports an SSID, so an ssid-filtered
     * wifi_disconnected trigger can only be matched against this remembered value.
     */
    @Volatile private var lastConnectedSsid: String? = null

    @Suppress("DEPRECATION") // NetworkInfo + connectionInfo are the only path that gives SSID here.
    override fun matchEvent(intent: Intent, workflows: List<WorkflowDefinition>): List<Pair<String, TriggerSpec>> {
        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
        val isConnected = networkInfo?.isConnected == true
        val ssid = if (isConnected) currentSsid() else null
        if (isConnected && ssid != null) lastConnectedSsid = ssid
        val matches = mutableListOf<Pair<String, TriggerSpec>>()
        for (wf in workflows) {
            val t = wf.trigger
            when {
                isConnected && t is TriggerSpec.WifiConnected ->
                    if (t.ssid.isNullOrBlank() || (ssid != null && ssid.equals(t.ssid, ignoreCase = true))) {
                        matches += wf.id to t
                    }
                !isConnected && t is TriggerSpec.WifiDisconnected ->
                    if (t.ssid.isNullOrBlank()
                        || lastConnectedSsid?.equals(t.ssid, ignoreCase = true) == true
                    ) {
                        matches += wf.id to t
                    }
            }
        }
        // One disconnect per connection: clear the remembered SSID so a spurious repeat
        // of the disconnect broadcast doesn't re-fire ssid-filtered workflows.
        if (!isConnected) lastConnectedSsid = null
        return matches
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf {
            it.isNotBlank() && it != "<unknown ssid>"
        }
    } catch (e: Throwable) {
        Log.w("WorkflowTrigger", "wifi: currentSsid lookup failed", e)
        null
    }
}

// -- Bluetooth connect / disconnect ----------------------------------------------------

internal class BluetoothTriggerFamily(context: Context, scope: CoroutineScope)
    : BaseBroadcastTriggerFamily(context, scope) {

    override val name = "bluetooth"
    override val intentFilter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
    }

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.BluetoothDeviceConnected || spec is TriggerSpec.BluetoothDeviceDisconnected

    override fun matchEvent(intent: Intent, workflows: List<WorkflowDefinition>): List<Pair<String, TriggerSpec>> {
        val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
        val mac = try { device?.address } catch (_: SecurityException) { null }
        val isConnect = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        val matches = mutableListOf<Pair<String, TriggerSpec>>()
        for (wf in workflows) {
            val t = wf.trigger
            when {
                isConnect && t is TriggerSpec.BluetoothDeviceConnected ->
                    if (t.deviceAddress.isNullOrBlank() || mac.equals(t.deviceAddress, ignoreCase = true)) {
                        matches += wf.id to t
                    }
                !isConnect && t is TriggerSpec.BluetoothDeviceDisconnected ->
                    if (t.deviceAddress.isNullOrBlank() || mac.equals(t.deviceAddress, ignoreCase = true)) {
                        matches += wf.id to t
                    }
            }
        }
        return matches
    }
}

// -- Headphones plug / unplug ----------------------------------------------------------

internal class HeadphoneTriggerFamily(context: Context, scope: CoroutineScope)
    : BaseBroadcastTriggerFamily(context, scope) {

    override val name = "headphones"
    override val intentFilter = IntentFilter(Intent.ACTION_HEADSET_PLUG)

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.HeadphonesPlugged || spec is TriggerSpec.HeadphonesUnplugged

    override fun matchEvent(intent: Intent, workflows: List<WorkflowDefinition>): List<Pair<String, TriggerSpec>> {
        val state = intent.getIntExtra("state", -1)
        val plugged = state == 1
        val matches = mutableListOf<Pair<String, TriggerSpec>>()
        for (wf in workflows) {
            val t = wf.trigger
            if (plugged && t is TriggerSpec.HeadphonesPlugged) matches += wf.id to t
            if (!plugged && t is TriggerSpec.HeadphonesUnplugged) matches += wf.id to t
        }
        return matches
    }
}

// -- Power connect / disconnect --------------------------------------------------------

internal class PowerTriggerFamily(context: Context, scope: CoroutineScope)
    : BaseBroadcastTriggerFamily(context, scope) {

    override val name = "power"
    override val intentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.PowerConnected || spec is TriggerSpec.PowerDisconnected

    override fun matchEvent(intent: Intent, workflows: List<WorkflowDefinition>): List<Pair<String, TriggerSpec>> {
        val isConnect = intent.action == Intent.ACTION_POWER_CONNECTED
        val matches = mutableListOf<Pair<String, TriggerSpec>>()
        for (wf in workflows) {
            val t = wf.trigger
            if (isConnect && t is TriggerSpec.PowerConnected) matches += wf.id to t
            if (!isConnect && t is TriggerSpec.PowerDisconnected) matches += wf.id to t
        }
        return matches
    }
}

// -- Screen on / off (runtime-only registration; manifest doesn't deliver these) -------

internal class ScreenTriggerFamily(context: Context, scope: CoroutineScope)
    : BaseBroadcastTriggerFamily(context, scope) {

    override val name = "screen"
    override val intentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.ScreenOn || spec is TriggerSpec.ScreenOff

    override fun matchEvent(intent: Intent, workflows: List<WorkflowDefinition>): List<Pair<String, TriggerSpec>> {
        val isOn = intent.action == Intent.ACTION_SCREEN_ON
        val matches = mutableListOf<Pair<String, TriggerSpec>>()
        for (wf in workflows) {
            val t = wf.trigger
            if (isOn && t is TriggerSpec.ScreenOn) matches += wf.id to t
            if (!isOn && t is TriggerSpec.ScreenOff) matches += wf.id to t
        }
        return matches
    }
}
