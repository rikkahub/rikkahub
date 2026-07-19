package me.rerere.rikkahub.voiceagent.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import me.rerere.rikkahub.voiceagent.RetirementBarrier

@SuppressLint("MissingPermission")
internal class AndroidDirectCaptureDeviceAdapter(
    private val hasConnectPermission: () -> Boolean,
    private val captureDevices: (AudioRecord) -> List<AndroidDirectCaptureDeviceCandidate>,
    private val clearCommunicationDevice: () -> Unit,
) : DirectCaptureDeviceCapability {
    constructor(context: Context, audioManager: AudioManager?) : this(
        hasConnectPermission = {
            audioManager != null && hasBluetoothConnectPermission(context)
        },
        captureDevices = { recorder ->
            audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty().map { device ->
                AndroidDirectCaptureDeviceCandidate(
                    routeDevice = device.toVoiceAudioRouteDevice(),
                    setPreferredDevice = { recorder.setPreferredDevice(device) },
                    setCommunicationDevice = {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            audioManager?.setCommunicationDevice(device) == true
                    },
                )
            }
        },
        clearCommunicationDevice = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager?.clearCommunicationDevice()
            }
        },
    )

    override fun configure(recorder: AudioRecord): DirectAudioResourceLease? {
        val hasPermission = runCatching(hasConnectPermission)
            .onFailure { logWarning("Direct capture route permission check failed", it) }
            .getOrDefault(false)
        if (!hasPermission) {
            logDebug("Direct Bluetooth route skipped: BLUETOOTH_CONNECT not granted")
            return null
        }

        val devices = runCatching {
            captureDevices(recorder)
        }.onFailure {
            logWarning("Direct capture route enumeration failed", it)
        }.getOrDefault(emptyList())
        val routeDevices = devices.map(AndroidDirectCaptureDeviceCandidate::routeDevice)
        val selectedRoute = selectPreferredCaptureRoute(routeDevices)
        logDebug(
            "Direct capture routes available=${routeDevices.joinToString { it.debugLabel() }} " +
                "selected=${selectedRoute?.debugLabel() ?: "default"}",
        )
        val selected = selectedRoute?.let { route ->
            devices.firstOrNull { it.routeDevice.id == route.id }
        }
            ?: return null

        val preferredAccepted = runCatching(selected.setPreferredDevice)
            .onFailure { logWarning("Direct preferred Bluetooth device failed", it) }
            .getOrDefault(false)
        val communicationAccepted = runCatching(selected.setCommunicationDevice)
            .onFailure { logWarning("Direct communication route failed", it) }
            .getOrDefault(false)
        logDebug(
            "Direct capture route=${selected.routeDevice.debugLabel()} " +
                "preferredAccepted=$preferredAccepted communicationAccepted=$communicationAccepted",
        )
        if (!communicationAccepted) return null

        val retirement = RetirementBarrier()
        return DirectAudioResourceLease {
            retirement.retire(clearCommunicationDevice)
        }
    }
}

internal class AndroidDirectCaptureDeviceCandidate(
    val routeDevice: VoiceAudioRouteDevice,
    val setPreferredDevice: () -> Boolean,
    val setCommunicationDevice: () -> Boolean,
)

private fun AudioDeviceInfo.toVoiceAudioRouteDevice(): VoiceAudioRouteDevice =
    VoiceAudioRouteDevice(
        id = id,
        type = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> VoiceAudioRouteDeviceType.BluetoothSco
            AudioDeviceInfo.TYPE_BLE_HEADSET -> VoiceAudioRouteDeviceType.BluetoothBleHeadset
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> VoiceAudioRouteDeviceType.BuiltInMic
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> VoiceAudioRouteDeviceType.WiredHeadset
            else -> VoiceAudioRouteDeviceType.Other
        },
        name = productName?.toString().orEmpty(),
    )

private fun VoiceAudioRouteDevice.debugLabel(): String =
    "$id:${type.name}:${name.ifBlank { "unnamed" }}"

private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
}

private fun logWarning(message: String, error: Throwable) {
    runCatching { Log.w(TAG, message, error) }
}

private const val TAG = "DirectCaptureDevice"
