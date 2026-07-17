package me.rerere.rikkahub.voiceagent.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.voiceagent.RetirementBarrier

internal fun interface DirectAudioResourceLease {
    fun retire()
}

internal fun interface DirectAudioFocusCapability {
    fun acquire(onFocusChange: (Int) -> Unit): DirectAudioResourceLease?
}

internal fun interface DirectCommunicationModeCapability {
    fun acquire(): DirectAudioResourceLease?
}

internal fun interface DirectBluetoothCaptureCapability {
    fun acquire(): DirectAudioResourceLease?
}

internal fun interface DirectCaptureDeviceCapability {
    fun configure(recorder: AudioRecord): DirectAudioResourceLease?
}

internal data class DirectAudioRouteCapabilities(
    val focus: DirectAudioFocusCapability,
    val communicationMode: DirectCommunicationModeCapability,
    val bluetoothCapture: DirectBluetoothCaptureCapability,
    val captureDevice: DirectCaptureDeviceCapability,
    val close: () -> Unit,
)

internal fun systemDirectAudioRouteCapabilities(context: Context): DirectAudioRouteCapabilities {
    val audioManager = context.getSystemService(AudioManager::class.java)
    val bluetoothCapture = SystemDirectBluetoothCaptureCapability(context, audioManager)
    return DirectAudioRouteCapabilities(
        focus = SystemDirectAudioFocusCapability(audioManager),
        communicationMode = SystemDirectCommunicationModeCapability(audioManager),
        bluetoothCapture = bluetoothCapture,
        captureDevice = SystemDirectCaptureDeviceCapability(context, audioManager),
        close = bluetoothCapture::close,
    )
}

private class SystemDirectAudioFocusCapability(
    private val audioManager: AudioManager?,
) : DirectAudioFocusCapability {
    override fun acquire(onFocusChange: (Int) -> Unit): DirectAudioResourceLease? =
        acquireSystemAudioFocus(audioManager, onFocusChange)
}

private class SystemDirectCommunicationModeCapability(
    private val audioManager: AudioManager?,
) : DirectCommunicationModeCapability {
    override fun acquire(): DirectAudioResourceLease? =
        acquireSystemCommunicationMode(audioManager)
}

private fun acquireSystemAudioFocus(
    audioManager: AudioManager?,
    onFocusChange: (Int) -> Unit,
): DirectAudioResourceLease? {
    val manager = audioManager ?: return null
    val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(voiceAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(onFocusChange)
            .build()
    } else {
        null
    }
    val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.requestAudioFocus(requireNotNull(request))
    } else {
        @Suppress("DEPRECATION")
        manager.requestAudioFocus(
            null,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
        )
    }
    if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        if (VoiceAudioFocusPolicy.isRequestFailureFatal(result)) {
            throw IllegalStateException("Voice Agent audio focus request failed: $result")
        }
        logCapabilityWarning("Voice Agent direct audio focus request was not granted: $result")
        return null
    }
    return retirementLease {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.abandonAudioFocusRequest(requireNotNull(request))
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }
}

private fun acquireSystemCommunicationMode(audioManager: AudioManager?): DirectAudioResourceLease? {
    val manager = audioManager ?: return null
    val previousMode = manager.mode
    if (previousMode == AudioManager.MODE_IN_COMMUNICATION) return null
    manager.mode = AudioManager.MODE_IN_COMMUNICATION
    return retirementLease { manager.mode = previousMode }
}

private class SystemDirectBluetoothCaptureCapability(
    private val context: Context,
    private val audioManager: AudioManager?,
) : DirectBluetoothCaptureCapability {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false
    private var wantsVoiceRecognition = false
    private var profileProxyRequested = false
    private var headset: BluetoothHeadset? = null
    private var recognitionDevice: BluetoothDevice? = null
    private var startedSco = false
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HEADSET) return
            val connected = proxy as? BluetoothHeadset ?: return
            val state = synchronized(lock) {
                if (closed) {
                    ConnectedBluetoothState(shouldClose = true, shouldRequestVoiceRecognition = false)
                } else {
                    headset = connected
                    ConnectedBluetoothState(
                        shouldClose = false,
                        shouldRequestVoiceRecognition = wantsVoiceRecognition,
                    )
                }
            }
            if (state.shouldClose) {
                closeHeadsetProxy(connected)
                return
            }
            logCapabilityDebug("Direct Bluetooth headset profile connected")
            if (state.shouldRequestVoiceRecognition) {
                scope.launch { requestVoiceRecognition(connected) }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HEADSET) return
            synchronized(lock) {
                headset = null
                recognitionDevice = null
                profileProxyRequested = false
                wantsVoiceRecognition = false
            }
            logCapabilityDebug("Direct Bluetooth headset profile disconnected")
        }
    }

    override fun acquire(): DirectAudioResourceLease? {
        val manager = audioManager ?: return null
        if (!hasBluetoothConnectPermission(context)) {
            logCapabilityDebug("Direct Bluetooth SCO skipped: BLUETOOTH_CONNECT not granted")
            return null
        }
        synchronized(lock) {
            check(!closed) { "Direct Bluetooth capture capability is closed" }
            wantsVoiceRecognition = true
        }
        val connected = headsetProxyOrNull()
        if (connected == null) {
            logCapabilityDebug("Direct Bluetooth headset voice recognition skipped: profile unavailable")
        } else {
            requestVoiceRecognition(connected)
        }
        runCatching {
            @Suppress("DEPRECATION")
            manager.startBluetoothSco()
            synchronized(lock) { startedSco = true }
            @Suppress("DEPRECATION")
            manager.isBluetoothScoOn = true
            logCapabilityDebug("Direct requested Bluetooth SCO")
        }.onFailure { logCapabilityWarning("Direct Bluetooth SCO request failed", it) }
        return retirementLease(::clearRouting)
    }

    private fun clearRouting() {
        val owned = synchronized(lock) {
            wantsVoiceRecognition = false
            BluetoothCaptureResources(
                headset = headset,
                recognitionDevice = recognitionDevice.also { recognitionDevice = null },
                stoppedSco = startedSco.also { startedSco = false },
            )
        }
        val connected = owned.headset
        val recognized = owned.recognitionDevice
        if (connected != null && recognized != null) {
            runCatching { connected.stopVoiceRecognition(recognized) }
                .onFailure { logCapabilityWarning("Direct Bluetooth headset voice recognition stop failed", it) }
        }
        if (owned.stoppedSco) {
            runCatching {
                @Suppress("DEPRECATION")
                audioManager?.isBluetoothScoOn = false
            }.onFailure { logCapabilityWarning("Direct Bluetooth SCO disable failed", it) }
            runCatching {
                @Suppress("DEPRECATION")
                audioManager?.stopBluetoothSco()
            }.onFailure { logCapabilityWarning("Direct Bluetooth SCO stop failed", it) }
        }
    }

    private fun headsetProxyOrNull(): BluetoothHeadset? {
        synchronized(lock) {
            headset?.let { return it }
            if (closed) return null
            if (!profileProxyRequested) {
                profileProxyRequested = runCatching {
                    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    adapter?.getProfileProxy(context, listener, BluetoothProfile.HEADSET) == true
                }.onFailure {
                    logCapabilityWarning("Direct Bluetooth headset profile request failed", it)
                }.getOrDefault(false)
            }
        }
        repeat(BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS) {
            synchronized(lock) { headset.takeUnless { closed } }?.let { return it }
            Thread.sleep(BLUETOOTH_HEADSET_PROFILE_WAIT_MS)
        }
        return synchronized(lock) { headset.takeUnless { closed } }
    }

    @SuppressLint("MissingPermission")
    private fun requestVoiceRecognition(connected: BluetoothHeadset) {
        val shouldRequest = synchronized(lock) {
            !closed && wantsVoiceRecognition && recognitionDevice == null
        }
        if (!shouldRequest) return
        val device = runCatching { connected.connectedDevices.firstOrNull() }
            .onFailure { logCapabilityWarning("Direct Bluetooth headset device lookup failed", it) }
            .getOrNull()
        if (device == null) {
            logCapabilityDebug("Direct Bluetooth headset voice recognition skipped: no connected headset")
            return
        }
        val accepted = runCatching {
            synchronized(lock) {
                if (closed || !wantsVoiceRecognition || recognitionDevice != null) {
                    false
                } else {
                    connected.startVoiceRecognition(device).also { started ->
                        if (started) recognitionDevice = device
                    }
                }
            }
        }.onFailure {
            logCapabilityWarning("Direct Bluetooth headset voice recognition request failed", it)
        }.getOrDefault(false)
        logCapabilityDebug(
            "Direct Bluetooth headset voice recognition requested " +
                "device=${device.safeBluetoothLabel()} accepted=$accepted",
        )
    }

    private fun closeHeadsetProxy(connected: BluetoothHeadset) {
        runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.closeProfileProxy(BluetoothProfile.HEADSET, connected)
        }.onFailure { logCapabilityWarning("Direct Bluetooth headset profile close failed", it) }
    }

    fun close() {
        val connected = synchronized(lock) {
            if (closed) return
            closed = true
            wantsVoiceRecognition = false
            recognitionDevice = null
            startedSco = false
            headset.also {
                headset = null
                profileProxyRequested = false
            }
        }
        connected?.let(::closeHeadsetProxy)
        scope.cancel()
    }

    private companion object {
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS = 10
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_MS = 100L
    }
}

private data class ConnectedBluetoothState(
    val shouldClose: Boolean,
    val shouldRequestVoiceRecognition: Boolean,
)

private data class BluetoothCaptureResources(
    val headset: BluetoothHeadset?,
    val recognitionDevice: BluetoothDevice?,
    val stoppedSco: Boolean,
)

private class SystemDirectCaptureDeviceCapability(
    private val context: Context,
    private val audioManager: AudioManager?,
) : DirectCaptureDeviceCapability {
    @SuppressLint("MissingPermission")
    override fun configure(recorder: AudioRecord): DirectAudioResourceLease? {
        val manager = audioManager ?: return null
        if (!hasBluetoothConnectPermission(context)) {
            logCapabilityDebug("Direct Bluetooth route skipped: BLUETOOTH_CONNECT not granted")
            return null
        }
        val devices = runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
            .onFailure { logCapabilityWarning("Direct capture route enumeration failed", it) }
            .getOrDefault(emptyList())
        val routeDevices = devices.map(AudioDeviceInfo::toVoiceAudioRouteDevice)
        val selectedRoute = selectPreferredCaptureRoute(routeDevices)
        logCapabilityDebug(
            "Direct capture routes available=${routeDevices.joinToString { it.debugLabel() }} " +
                "selected=${selectedRoute?.debugLabel() ?: "default"}",
        )
        val selected = selectedRoute?.let { route -> devices.firstOrNull { it.id == route.id } } ?: return null
        val preferredAccepted = runCatching { recorder.setPreferredDevice(selected) }
            .onFailure { logCapabilityWarning("Direct preferred Bluetooth device failed", it) }
            .getOrDefault(false)
        val communicationAccepted = runCatching {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.setCommunicationDevice(selected)
        }.onFailure {
            logCapabilityWarning("Direct communication route failed", it)
        }.getOrDefault(false)
        logCapabilityDebug(
            "Direct capture route=${selectedRoute.debugLabel()} " +
                "preferredAccepted=$preferredAccepted communicationAccepted=$communicationAccepted",
        )
        if (!communicationAccepted) return null
        return retirementLease {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.clearCommunicationDevice()
        }
    }
}

private fun retirementLease(retire: () -> Unit): DirectAudioResourceLease {
    val retirement = RetirementBarrier()
    return DirectAudioResourceLease { retirement.retire(retire) }
}

private fun hasBluetoothConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

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

@SuppressLint("MissingPermission")
private fun BluetoothDevice.safeBluetoothLabel(): String =
    "${name ?: "unnamed"}:${address ?: "unknown"}"

private fun logCapabilityDebug(message: String) {
    runCatching { Log.d(CAPABILITY_TAG, message) }
}

private fun logCapabilityWarning(message: String, error: Throwable? = null) {
    runCatching {
        if (error == null) Log.w(CAPABILITY_TAG, message) else Log.w(CAPABILITY_TAG, message, error)
    }
}

internal fun voiceAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

private const val CAPABILITY_TAG = "DirectAudioCapabilities"
