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

internal interface DirectBluetoothHeadset

internal interface DirectBluetoothDevice {
    val safeLabel: String
}

internal interface DirectBluetoothHeadsetListener {
    fun onConnected(headset: DirectBluetoothHeadset)
    fun onDisconnected()
}

internal interface DirectBluetoothCaptureOperations {
    fun createCallbackDispatcher(): DirectBluetoothCallbackDispatcher
    fun hasConnectPermission(): Boolean
    fun requestHeadsetProxy(listener: DirectBluetoothHeadsetListener): Boolean
    fun awaitHeadset(
        current: () -> DirectBluetoothHeadset?,
        shouldStop: () -> Boolean,
    ): DirectBluetoothHeadset?
    fun closeHeadsetProxy(headset: DirectBluetoothHeadset)
    fun connectedDevices(headset: DirectBluetoothHeadset): List<DirectBluetoothDevice>
    fun startVoiceRecognition(headset: DirectBluetoothHeadset, device: DirectBluetoothDevice): Boolean
    fun stopVoiceRecognition(headset: DirectBluetoothHeadset, device: DirectBluetoothDevice)
    fun startBluetoothSco()
    fun setBluetoothScoEnabled(enabled: Boolean)
    fun stopBluetoothSco()
}

internal interface DirectBluetoothCallbackDispatcher {
    fun dispatch(block: () -> Unit)
    fun close()
}

internal data class DirectAudioCaptureDevice(
    val routeDevice: VoiceAudioRouteDevice,
    val safeLabel: String,
    val handle: DirectAudioCaptureDeviceHandle,
)

internal interface DirectAudioCaptureDeviceHandle

internal interface DirectCaptureDeviceOperations {
    fun hasConnectPermission(): Boolean
    fun captureDevices(): List<DirectAudioCaptureDevice>
    fun setPreferredDevice(recorder: AudioRecord, device: DirectAudioCaptureDevice): Boolean
    fun setCommunicationDevice(device: DirectAudioCaptureDevice): Boolean
    fun clearCommunicationDevice()
}

internal fun systemDirectAudioRouteCapabilities(context: Context): DirectAudioRouteCapabilities {
    val audioManager = context.getSystemService(AudioManager::class.java)
    val bluetoothCapture = SystemDirectBluetoothCaptureCapability(
        AndroidDirectBluetoothCaptureOperations(context, audioManager),
    )
    return DirectAudioRouteCapabilities(
        focus = SystemDirectAudioFocusCapability(audioManager),
        communicationMode = SystemDirectCommunicationModeCapability(audioManager),
        bluetoothCapture = bluetoothCapture,
        captureDevice = SystemDirectCaptureDeviceCapability(
            AndroidDirectCaptureDeviceOperations(context, audioManager),
        ),
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

internal class SystemDirectBluetoothCaptureCapability(
    private val operations: DirectBluetoothCaptureOperations,
) : DirectBluetoothCaptureCapability {
    private val lock = Any()
    private val factoryRetirement = RetirementBarrier()
    private var closed = false

    override fun acquire(): DirectAudioResourceLease? {
        if (!operations.hasConnectPermission()) {
            logCapabilityDebug("Direct Bluetooth SCO skipped: BLUETOOTH_CONNECT not granted")
            return null
        }
        return synchronized(lock) {
            check(!closed) { "Direct Bluetooth capture capability is closed" }
            val lease = SystemBluetoothCaptureLease(operations)
            try {
                lease.acquireRouting()
                lease
            } catch (failure: Throwable) {
                runCatching(lease::retire).onFailure(failure::addSuppressed)
                throw failure
            }
        }
    }

    fun close() = factoryRetirement.retire {
        synchronized(lock) {
            closed = true
        }
    }
}

private class SystemBluetoothCaptureLease(
    private val operations: DirectBluetoothCaptureOperations,
) : DirectAudioResourceLease {
    private val lock = Any()
    private val retirement = RetirementBarrier()
    private val callbackDispatcher = operations.createCallbackDispatcher()
    private var closed = false
    private var headset: DirectBluetoothHeadset? = null
    private var recognitionDevice: DirectBluetoothDevice? = null
    private var recognitionAttempted = false
    private var scoAttempted = false
    private var startedSco = false
    private val listener = object : DirectBluetoothHeadsetListener {
        override fun onConnected(headset: DirectBluetoothHeadset) {
            val reject = synchronized(lock) {
                if (closed) {
                    true
                } else {
                    this@SystemBluetoothCaptureLease.headset = headset
                    false
                }
            }
            if (reject) {
                closeHeadsetProxy(headset)
                return
            }
            logCapabilityDebug("Direct Bluetooth headset profile connected")
            callbackDispatcher.dispatch { activateConnectedHeadset(headset) }
        }

        override fun onDisconnected() {
            synchronized(lock) {
                headset = null
                recognitionDevice = null
                recognitionAttempted = false
            }
            logCapabilityDebug("Direct Bluetooth headset profile disconnected")
        }
    }

    fun acquireRouting() {
        val proxyRequested = runCatching { operations.requestHeadsetProxy(listener) }
            .onFailure { logCapabilityWarning("Direct Bluetooth headset profile request failed", it) }
            .getOrDefault(false)
        val connected = if (proxyRequested) {
            operations.awaitHeadset(
                current = { synchronized(lock) { headset.takeUnless { closed } } },
                shouldStop = { synchronized(lock) { closed } },
            )
        } else {
            null
        }
        if (connected == null) {
            logCapabilityDebug("Direct Bluetooth headset voice recognition skipped: profile unavailable")
        } else {
            requestVoiceRecognition(connected)
        }
        requestBluetoothSco()
    }

    override fun retire() = retirement.retire {
        val owned = synchronized(lock) {
            closed = true
            BluetoothCaptureResources(
                headset = headset.also { headset = null },
                recognitionDevice = recognitionDevice.also { recognitionDevice = null },
                stoppedSco = startedSco.also { startedSco = false },
            )
        }
        runCatching(callbackDispatcher::close)
            .onFailure { logCapabilityWarning("Direct Bluetooth callback dispatcher close failed", it) }
        val connected = owned.headset
        val recognized = owned.recognitionDevice
        if (connected != null && recognized != null) {
            runCatching { operations.stopVoiceRecognition(connected, recognized) }
                .onFailure { logCapabilityWarning("Direct Bluetooth headset voice recognition stop failed", it) }
        }
        if (owned.stoppedSco) {
            runCatching { operations.setBluetoothScoEnabled(false) }
                .onFailure { logCapabilityWarning("Direct Bluetooth SCO disable failed", it) }
            runCatching(operations::stopBluetoothSco)
                .onFailure { logCapabilityWarning("Direct Bluetooth SCO stop failed", it) }
        }
        connected?.let(::closeHeadsetProxy)
    }

    private fun activateConnectedHeadset(connected: DirectBluetoothHeadset) {
        requestVoiceRecognition(connected)
        requestBluetoothSco()
    }

    private fun requestBluetoothSco() {
        runCatching {
            synchronized(lock) {
                if (closed || scoAttempted) return@runCatching
                scoAttempted = true
                operations.startBluetoothSco()
                startedSco = true
                operations.setBluetoothScoEnabled(true)
            }
            logCapabilityDebug("Direct requested Bluetooth SCO")
        }.onFailure { logCapabilityWarning("Direct Bluetooth SCO request failed", it) }
    }

    private fun requestVoiceRecognition(connected: DirectBluetoothHeadset) {
        val shouldRequest = synchronized(lock) {
            if (closed || headset !== connected || recognitionAttempted) {
                false
            } else {
                recognitionAttempted = true
                true
            }
        }
        if (!shouldRequest) return
        val device = runCatching { operations.connectedDevices(connected).firstOrNull() }
            .onFailure { logCapabilityWarning("Direct Bluetooth headset device lookup failed", it) }
            .getOrNull()
        if (device == null) {
            logCapabilityDebug("Direct Bluetooth headset voice recognition skipped: no connected headset")
            return
        }
        val accepted = runCatching {
            synchronized(lock) {
                if (closed || headset !== connected || recognitionDevice != null) {
                    false
                } else {
                    operations.startVoiceRecognition(connected, device).also { started ->
                        if (started) recognitionDevice = device
                    }
                }
            }
        }.onFailure {
            logCapabilityWarning("Direct Bluetooth headset voice recognition request failed", it)
        }.getOrDefault(false)
        logCapabilityDebug(
            "Direct Bluetooth headset voice recognition requested " +
                "device=${device.safeLabel} accepted=$accepted",
        )
    }

    private fun closeHeadsetProxy(connected: DirectBluetoothHeadset) {
        runCatching { operations.closeHeadsetProxy(connected) }
            .onFailure { logCapabilityWarning("Direct Bluetooth headset profile close failed", it) }
    }
}

internal class SystemDirectCaptureDeviceCapability(
    private val operations: DirectCaptureDeviceOperations,
) : DirectCaptureDeviceCapability {
    override fun configure(recorder: AudioRecord): DirectAudioResourceLease? {
        if (!operations.hasConnectPermission()) {
            logCapabilityDebug("Direct Bluetooth route skipped: BLUETOOTH_CONNECT not granted")
            return null
        }
        val devices = runCatching(operations::captureDevices)
            .onFailure { logCapabilityWarning("Direct capture route enumeration failed", it) }
            .getOrDefault(emptyList())
        val routeDevices = devices.map { it.routeDevice }
        val selectedRoute = selectPreferredCaptureRoute(routeDevices)
        logCapabilityDebug(
            "Direct capture routes available=${routeDevices.joinToString { it.debugLabel() }} " +
                "selected=${selectedRoute?.debugLabel() ?: "default"}",
        )
        val selected = selectedRoute?.let { route -> devices.firstOrNull { it.routeDevice.id == route.id } }
            ?: return null
        val preferredAccepted = runCatching { operations.setPreferredDevice(recorder, selected) }
            .onFailure { logCapabilityWarning("Direct preferred Bluetooth device failed", it) }
            .getOrDefault(false)
        val communicationAccepted = runCatching { operations.setCommunicationDevice(selected) }
            .onFailure { logCapabilityWarning("Direct communication route failed", it) }
            .getOrDefault(false)
        logCapabilityDebug(
            "Direct capture route=${selected.safeLabel} " +
                "preferredAccepted=$preferredAccepted communicationAccepted=$communicationAccepted",
        )
        if (!communicationAccepted) return null
        return retirementLease(operations::clearCommunicationDevice)
    }
}

private class AndroidDirectBluetoothCaptureOperations(
    private val context: Context,
    private val audioManager: AudioManager?,
) : DirectBluetoothCaptureOperations {
    override fun createCallbackDispatcher(): DirectBluetoothCallbackDispatcher =
        AndroidDirectBluetoothCallbackDispatcher()

    override fun hasConnectPermission(): Boolean = directBluetoothCaptureAvailable(audioManager != null) {
        hasBluetoothConnectPermission(context)
    }

    @SuppressLint("MissingPermission")
    override fun requestHeadsetProxy(listener: DirectBluetoothHeadsetListener): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val androidListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    (proxy as? BluetoothHeadset)?.let { listener.onConnected(AndroidDirectBluetoothHeadset(it)) }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) listener.onDisconnected()
            }
        }
        return adapter.getProfileProxy(context, androidListener, BluetoothProfile.HEADSET)
    }

    override fun awaitHeadset(
        current: () -> DirectBluetoothHeadset?,
        shouldStop: () -> Boolean,
    ): DirectBluetoothHeadset? {
        repeat(BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS) {
            if (shouldStop()) return null
            current()?.let { return it }
            Thread.sleep(BLUETOOTH_HEADSET_PROFILE_WAIT_MS)
        }
        return current().takeUnless { shouldStop() }
    }

    override fun closeHeadsetProxy(headset: DirectBluetoothHeadset) {
        val androidHeadset = headset.requireAndroidHeadset()
        context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.closeProfileProxy(BluetoothProfile.HEADSET, androidHeadset)
    }

    @SuppressLint("MissingPermission")
    override fun connectedDevices(headset: DirectBluetoothHeadset): List<DirectBluetoothDevice> =
        headset.requireAndroidHeadset().connectedDevices.map { device ->
            AndroidDirectBluetoothDevice(device, device.safeBluetoothLabel())
        }

    @SuppressLint("MissingPermission")
    override fun startVoiceRecognition(
        headset: DirectBluetoothHeadset,
        device: DirectBluetoothDevice,
    ): Boolean = headset.requireAndroidHeadset().startVoiceRecognition(device.requireBluetoothDevice())

    @SuppressLint("MissingPermission")
    override fun stopVoiceRecognition(headset: DirectBluetoothHeadset, device: DirectBluetoothDevice) {
        headset.requireAndroidHeadset().stopVoiceRecognition(device.requireBluetoothDevice())
    }

    @Suppress("DEPRECATION")
    override fun startBluetoothSco() {
        audioManager?.startBluetoothSco()
    }

    @Suppress("DEPRECATION")
    override fun setBluetoothScoEnabled(enabled: Boolean) {
        audioManager?.isBluetoothScoOn = enabled
    }

    @Suppress("DEPRECATION")
    override fun stopBluetoothSco() {
        audioManager?.stopBluetoothSco()
    }

    private fun DirectBluetoothHeadset.requireAndroidHeadset(): BluetoothHeadset =
        requireNotNull(this as? AndroidDirectBluetoothHeadset) { "Unexpected direct Bluetooth headset handle" }.headset

    private fun DirectBluetoothDevice.requireBluetoothDevice(): BluetoothDevice =
        requireNotNull(this as? AndroidDirectBluetoothDevice) { "Unexpected direct Bluetooth device handle" }.device

    private companion object {
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS = 10
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_MS = 100L
    }
}

private class AndroidDirectBluetoothCallbackDispatcher : DirectBluetoothCallbackDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun dispatch(block: () -> Unit) {
        scope.launch { block() }
    }

    override fun close() {
        scope.cancel()
    }
}

internal fun directBluetoothCaptureAvailable(
    audioManagerAvailable: Boolean,
    hasConnectPermission: () -> Boolean,
): Boolean = audioManagerAvailable && hasConnectPermission()

private class AndroidDirectCaptureDeviceOperations(
    private val context: Context,
    private val audioManager: AudioManager?,
) : DirectCaptureDeviceOperations {
    override fun hasConnectPermission(): Boolean = audioManager != null && hasBluetoothConnectPermission(context)

    override fun captureDevices(): List<DirectAudioCaptureDevice> =
        audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty().map { device ->
            val routeDevice = device.toVoiceAudioRouteDevice()
            DirectAudioCaptureDevice(
                routeDevice = routeDevice,
                safeLabel = routeDevice.debugLabel(),
                handle = AndroidDirectAudioCaptureDeviceHandle(device),
            )
        }

    override fun setPreferredDevice(recorder: AudioRecord, device: DirectAudioCaptureDevice): Boolean =
        recorder.setPreferredDevice(device.requireAudioDeviceInfo())

    @SuppressLint("MissingPermission")
    override fun setCommunicationDevice(device: DirectAudioCaptureDevice): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            audioManager?.setCommunicationDevice(device.requireAudioDeviceInfo()) == true

    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager?.clearCommunicationDevice()
    }

    private fun DirectAudioCaptureDevice.requireAudioDeviceInfo(): AudioDeviceInfo =
        requireNotNull(handle as? AndroidDirectAudioCaptureDeviceHandle) {
            "Unexpected direct capture device handle"
        }.device
}

private data class BluetoothCaptureResources(
    val headset: DirectBluetoothHeadset?,
    val recognitionDevice: DirectBluetoothDevice?,
    val stoppedSco: Boolean,
)

private data class AndroidDirectBluetoothHeadset(
    val headset: BluetoothHeadset,
) : DirectBluetoothHeadset

private data class AndroidDirectBluetoothDevice(
    val device: BluetoothDevice,
    override val safeLabel: String,
) : DirectBluetoothDevice

private data class AndroidDirectAudioCaptureDeviceHandle(
    val device: AudioDeviceInfo,
) : DirectAudioCaptureDeviceHandle

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
