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

internal interface DirectAudioFocusHandle

internal data class DirectAudioFocusAcquisition(
    val result: Int,
    val handle: DirectAudioFocusHandle?,
)

internal data class DirectAudioCaptureDevice(
    val routeDevice: VoiceAudioRouteDevice,
    val safeLabel: String,
    val handle: DirectAudioCaptureDeviceHandle,
)

internal interface DirectAudioCaptureDeviceHandle

internal interface DirectBluetoothHeadset

internal interface DirectBluetoothDevice {
    val safeLabel: String
}

internal fun interface DirectAudioRecorder {
    fun setPreferredDevice(device: DirectAudioCaptureDevice): Boolean
}

internal interface DirectBluetoothHeadsetListener {
    fun onConnected(headset: DirectBluetoothHeadset)
    fun onDisconnected()
}

internal interface DirectAudioRoutePlatform {
    val available: Boolean

    fun requestAudioFocus(onFocusChange: (Int) -> Unit): DirectAudioFocusAcquisition
    fun abandonAudioFocus(handle: DirectAudioFocusHandle)
    fun enterCommunicationMode(): Int?
    fun restoreAudioMode(mode: Int)
    fun hasBluetoothConnectPermission(): Boolean
    fun captureDevices(): List<DirectAudioCaptureDevice>
    fun recorder(recorder: AudioRecord): DirectAudioRecorder
    fun setCommunicationDevice(device: DirectAudioCaptureDevice): Boolean
    fun clearCommunicationDevice()
    fun startBluetoothSco()
    fun setBluetoothScoEnabled(enabled: Boolean)
    fun stopBluetoothSco()
    fun requestBluetoothHeadsetProxy(listener: DirectBluetoothHeadsetListener): Boolean
    fun awaitBluetoothHeadset(current: () -> DirectBluetoothHeadset?): DirectBluetoothHeadset?
    fun closeBluetoothHeadsetProxy(headset: DirectBluetoothHeadset)
    fun connectedBluetoothDevices(headset: DirectBluetoothHeadset): List<DirectBluetoothDevice>
    fun startVoiceRecognition(headset: DirectBluetoothHeadset, device: DirectBluetoothDevice): Boolean
    fun stopVoiceRecognition(headset: DirectBluetoothHeadset, device: DirectBluetoothDevice)
    fun dispatch(block: () -> Unit)
    fun close()
}

internal class AndroidDirectAudioRouteController(
    private val platform: DirectAudioRoutePlatform,
    private val onAudioError: (String) -> Unit,
) : VoiceAudioRouteController {
    constructor(context: Context, onAudioError: (String) -> Unit) : this(
        platform = SystemDirectAudioRoutePlatform(context.applicationContext),
        onAudioError = onAudioError,
    )

    private val lock = Any()
    private val captureTransitionLock = Any()
    private var audioFocusHandle: DirectAudioFocusHandle? = null
    private var hasSelectedCommunicationDevice = false
    private var hasStartedBluetoothSco = false
    private var bluetoothProfileProxyRequested = false
    private var wantsBluetoothHeadsetVoiceRecognition = false
    private var bluetoothVoiceRecognitionDevice: DirectBluetoothDevice? = null
    private var bluetoothHeadset: DirectBluetoothHeadset? = null
    private var previousAudioMode: Int? = null
    private var hasAudioFocus = false
    private var closed = false
    private var captureGeneration = 0L
    private val bluetoothHeadsetListener = object : DirectBluetoothHeadsetListener {
        override fun onConnected(headset: DirectBluetoothHeadset) {
            val state = synchronized(lock) {
                if (closed) {
                    ConnectedHeadsetState(shouldClose = true, shouldRequestVoiceRecognition = false)
                } else {
                    bluetoothHeadset = headset
                    ConnectedHeadsetState(
                        shouldClose = false,
                        shouldRequestVoiceRecognition = wantsBluetoothHeadsetVoiceRecognition,
                    )
                }
            }
            if (state.shouldClose) {
                closeBluetoothHeadsetProxy(headset)
                return
            }
            logDebug("Direct Bluetooth headset profile connected")
            if (state.shouldRequestVoiceRecognition) {
                platform.dispatch {
                    requestBluetoothHeadsetVoiceRecognition(headset)
                }
            }
        }

        override fun onDisconnected() {
            synchronized(lock) {
                bluetoothHeadset = null
                bluetoothVoiceRecognitionDevice = null
                bluetoothProfileProxyRequested = false
                wantsBluetoothHeadsetVoiceRecognition = false
            }
            logDebug("Direct Bluetooth headset profile disconnected")
        }
    }

    override fun acquireCapture(): VoiceAudioCaptureRouteLease = synchronized(captureTransitionLock) {
        val generation = synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
            captureGeneration += 1
            captureGeneration
        }
        try {
            prepareForCapture()
        } catch (failure: Throwable) {
            clearAfterCapture(generation)
            throw failure
        }
        DirectVoiceAudioCaptureRouteLease(
            configureAudioRecord = { recorder -> configureCaptureRecorder(generation, recorder) },
            configureDirectRecorder = { recorder -> configureCaptureRecorder(generation, recorder) },
            clearAfterCapture = { clearAfterCapture(generation) },
        )
    }

    private fun prepareForCapture() {
        synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
        }
        if (!platform.available) return
        requestAudioFocusBestEffort()
        if (isClosed()) return
        prepareVoiceCommunicationRoutingBestEffort()
    }

    private fun configureCaptureRecorder(generation: Long, recorder: AudioRecord) {
        configureCaptureRecorder(generation, platform.recorder(recorder))
    }

    private fun configureCaptureRecorder(
        generation: Long,
        recorder: DirectAudioRecorder,
    ) {
        synchronized(captureTransitionLock) {
            synchronized(lock) {
                check(!closed) { "Direct audio route controller is closed" }
                check(captureGeneration == generation) { "Direct audio capture route lease is stale" }
            }
            if (!platform.available) return
            val device = selectPreferredBluetoothCaptureDeviceOrNull() ?: return
            val preferredAccepted = runCatching {
                synchronized(lock) {
                    check(!closed) { "Direct audio route controller is closed" }
                    check(captureGeneration == generation) { "Direct audio capture route lease is stale" }
                    recorder.setPreferredDevice(device)
                }
            }
                .onFailure { logWarning("Direct preferred Bluetooth device failed", it) }
                .getOrDefault(false)
            val communicationAccepted = setCommunicationDeviceBestEffort(generation, device)
            logDebug(
                "Direct capture route=${device.safeLabel} " +
                    "preferredAccepted=$preferredAccepted communicationAccepted=$communicationAccepted",
            )
        }
    }

    private fun clearAfterCapture(generation: Long) = synchronized(captureTransitionLock) {
        val ownsRoute = synchronized(lock) {
            if (closed || captureGeneration != generation) {
                false
            } else {
                captureGeneration += 1
                true
            }
        }
        if (ownsRoute) clearVoiceCommunicationRoutingBestEffort()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            captureGeneration += 1
        }
        clearVoiceCommunicationRoutingBestEffort()
        closeBluetoothHeadsetProxy()
        abandonAudioFocus()
        platform.close()
    }

    private fun selectPreferredBluetoothCaptureDeviceOrNull(): DirectAudioCaptureDevice? {
        if (!platform.hasBluetoothConnectPermission()) {
            logDebug("Direct Bluetooth route skipped: BLUETOOTH_CONNECT not granted")
            return null
        }
        val devices = runCatching { platform.captureDevices() }
            .onFailure { logWarning("Direct capture route enumeration failed", it) }
            .getOrDefault(emptyList())
        val routeDevices = devices.map { it.routeDevice }
        val selected = selectPreferredCaptureRoute(routeDevices)
        logDebug(
            "Direct capture routes available=${routeDevices.joinToString { it.debugLabel() }} " +
                "selected=${selected?.debugLabel() ?: "default"}",
        )
        return selected?.let { route -> devices.firstOrNull { it.routeDevice.id == route.id } }
    }

    private fun setCommunicationDeviceBestEffort(
        generation: Long,
        device: DirectAudioCaptureDevice,
    ): Boolean = runCatching {
        synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
            check(captureGeneration == generation) { "Direct audio capture route lease is stale" }
            val accepted = platform.setCommunicationDevice(device)
            if (accepted) hasSelectedCommunicationDevice = true
            accepted
        }
    }.onFailure {
        logWarning("Direct communication route failed", it)
    }.getOrDefault(false)

    private fun clearCommunicationDeviceSelection() {
        val shouldClear = synchronized(lock) {
            if (!hasSelectedCommunicationDevice) {
                false
            } else {
                hasSelectedCommunicationDevice = false
                true
            }
        }
        if (!shouldClear) return
        runCatching { platform.clearCommunicationDevice() }
            .onFailure { logWarning("Direct communication route clear failed", it) }
    }

    private fun prepareVoiceCommunicationRoutingBestEffort() {
        runCatching {
            synchronized(lock) {
                check(!closed) { "Direct audio route controller is closed" }
                if (previousAudioMode == null) {
                    previousAudioMode = platform.enterCommunicationMode()
                }
            }
        }.onFailure {
            logWarning("Direct communication mode setup failed", it)
        }

        if (!platform.hasBluetoothConnectPermission()) {
            logDebug("Direct Bluetooth SCO skipped: BLUETOOTH_CONNECT not granted")
            return
        }
        startBluetoothHeadsetVoiceRecognitionBestEffort()
        runCatching {
            synchronized(lock) {
                check(!closed) { "Direct audio route controller is closed" }
                if (!hasStartedBluetoothSco) {
                    platform.startBluetoothSco()
                    hasStartedBluetoothSco = true
                    platform.setBluetoothScoEnabled(true)
                }
            }
            logDebug("Direct requested Bluetooth SCO")
        }.onFailure {
            logWarning("Direct Bluetooth SCO request failed", it)
        }
    }

    private fun clearVoiceCommunicationRoutingBestEffort() {
        clearCommunicationDeviceSelection()
        stopBluetoothHeadsetVoiceRecognitionBestEffort()
        val stoppedSco = synchronized(lock) {
            if (hasStartedBluetoothSco) {
                hasStartedBluetoothSco = false
                true
            } else {
                false
            }
        }
        if (stoppedSco) {
            runCatching { platform.setBluetoothScoEnabled(false) }
                .onFailure { logWarning("Direct Bluetooth SCO disable failed", it) }
            runCatching { platform.stopBluetoothSco() }
                .onFailure { logWarning("Direct Bluetooth SCO stop failed", it) }
        }
        val mode = synchronized(lock) {
            previousAudioMode.also { previousAudioMode = null }
        }
        mode?.let {
            runCatching { platform.restoreAudioMode(it) }
                .onFailure { error -> logWarning("Direct communication mode restore failed", error) }
        }
    }

    private fun startBluetoothHeadsetVoiceRecognitionBestEffort() {
        synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
            wantsBluetoothHeadsetVoiceRecognition = true
        }
        val headset = bluetoothHeadsetProxyOrNull()
        if (headset == null) {
            logDebug("Direct Bluetooth headset voice recognition skipped: profile unavailable")
            return
        }
        requestBluetoothHeadsetVoiceRecognition(headset)
    }

    private fun requestBluetoothHeadsetVoiceRecognition(headset: DirectBluetoothHeadset) {
        val shouldRequest = synchronized(lock) {
            !closed && wantsBluetoothHeadsetVoiceRecognition && bluetoothVoiceRecognitionDevice == null
        }
        if (!shouldRequest) return
        val device = runCatching { platform.connectedBluetoothDevices(headset).firstOrNull() }
            .onFailure { logWarning("Direct Bluetooth headset device lookup failed", it) }
            .getOrNull()
        if (device == null) {
            logDebug("Direct Bluetooth headset voice recognition skipped: no connected headset")
            return
        }
        val accepted = runCatching {
            synchronized(lock) {
                if (closed || !wantsBluetoothHeadsetVoiceRecognition || bluetoothVoiceRecognitionDevice != null) {
                    return@synchronized false
                }
                platform.startVoiceRecognition(headset, device).also { started ->
                    if (started) bluetoothVoiceRecognitionDevice = device
                }
            }
        }.onFailure {
            logWarning("Direct Bluetooth headset voice recognition request failed", it)
        }.getOrDefault(false)
        logDebug(
            "Direct Bluetooth headset voice recognition requested device=${device.safeLabel} accepted=$accepted",
        )
    }

    private fun stopBluetoothHeadsetVoiceRecognitionBestEffort() {
        val state = synchronized(lock) {
            wantsBluetoothHeadsetVoiceRecognition = false
            val headset = bluetoothHeadset
            val device = bluetoothVoiceRecognitionDevice
            bluetoothVoiceRecognitionDevice = null
            headset to device
        }
        val headset = state.first ?: return
        val device = state.second ?: return
        runCatching { platform.stopVoiceRecognition(headset, device) }
            .onFailure { logWarning("Direct Bluetooth headset voice recognition stop failed", it) }
    }

    private fun bluetoothHeadsetProxyOrNull(): DirectBluetoothHeadset? {
        synchronized(lock) {
            bluetoothHeadset?.let { return it }
            if (closed) return null
            if (!bluetoothProfileProxyRequested) {
                bluetoothProfileProxyRequested = runCatching {
                    platform.requestBluetoothHeadsetProxy(bluetoothHeadsetListener)
                }.onFailure {
                    logWarning("Direct Bluetooth headset profile request failed", it)
                }.getOrDefault(false)
            }
        }
        return platform.awaitBluetoothHeadset {
            synchronized(lock) {
                bluetoothHeadset.takeUnless { closed }
            }
        }
    }

    private fun closeBluetoothHeadsetProxy() {
        val headset = synchronized(lock) {
            bluetoothHeadset.also {
                bluetoothHeadset = null
                bluetoothProfileProxyRequested = false
                bluetoothVoiceRecognitionDevice = null
            }
        } ?: return
        closeBluetoothHeadsetProxy(headset)
    }

    private fun closeBluetoothHeadsetProxy(headset: DirectBluetoothHeadset) {
        runCatching { platform.closeBluetoothHeadsetProxy(headset) }
            .onFailure { logWarning("Direct Bluetooth headset profile close failed", it) }
    }

    private fun requestAudioFocusBestEffort() {
        synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
            if (hasAudioFocus) return
        }
        val acquisition = platform.requestAudioFocus { focusChange ->
            if (VoiceAudioFocusPolicy.isFocusChangeFatal(focusChange)) {
                onAudioError("Audio focus lost: $focusChange")
            } else if (focusChange < 0) {
                logWarning("Recoverable direct audio focus change: $focusChange")
            }
        }
        if (acquisition.result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (VoiceAudioFocusPolicy.isRequestFailureFatal(acquisition.result)) {
                throw IllegalStateException("Voice Agent audio focus request failed: ${acquisition.result}")
            }
            logWarning("Voice Agent direct audio focus request was not granted: ${acquisition.result}")
            return
        }
        val handle = requireNotNull(acquisition.handle) { "Granted audio focus requires a handle" }
        val acquiredAfterClose = synchronized(lock) {
            if (closed) {
                true
            } else {
                audioFocusHandle = handle
                hasAudioFocus = true
                false
            }
        }
        if (acquiredAfterClose) platform.abandonAudioFocus(handle)
    }

    private fun abandonAudioFocus() {
        val handle = synchronized(lock) {
            if (!hasAudioFocus) return
            hasAudioFocus = false
            audioFocusHandle.also { audioFocusHandle = null }
        } ?: return
        platform.abandonAudioFocus(handle)
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logWarning(message: String, error: Throwable? = null) {
        runCatching {
            if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
        }
    }

    private fun VoiceAudioRouteDevice.debugLabel(): String =
        "$id:${type.name}:${name.ifBlank { "unnamed" }}"

    private companion object {
        const val TAG = "AndroidDirectAudioRouteController"
    }

    private data class ConnectedHeadsetState(
        val shouldClose: Boolean,
        val shouldRequestVoiceRecognition: Boolean,
    )
}

internal class DirectVoiceAudioCaptureRouteLease(
    private val configureAudioRecord: (AudioRecord) -> Unit,
    private val configureDirectRecorder: (DirectAudioRecorder) -> Unit,
    private val clearAfterCapture: () -> Unit,
) : VoiceAudioCaptureRouteLease {
    private val retirement = RetirementBarrier()

    override fun configureRecorder(recorder: AudioRecord) {
        configureAudioRecord(recorder)
    }

    internal fun configureRecorder(recorder: DirectAudioRecorder) {
        configureDirectRecorder(recorder)
    }

    override fun retire() {
        retirement.retire(clearAfterCapture)
    }
}

private class SystemDirectAudioRoutePlatform(
    private val context: Context,
) : DirectAudioRoutePlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var serviceListener: BluetoothProfile.ServiceListener? = null

    override val available: Boolean
        get() = audioManager != null

    override fun requestAudioFocus(onFocusChange: (Int) -> Unit): DirectAudioFocusAcquisition {
        val manager = audioManager
            ?: return DirectAudioFocusAcquisition(AudioManager.AUDIOFOCUS_REQUEST_FAILED, null)
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
        return DirectAudioFocusAcquisition(
            result = result,
            handle = AndroidDirectAudioFocusHandle(request)
                .takeIf { result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED },
        )
    }

    override fun abandonAudioFocus(handle: DirectAudioFocusHandle) {
        val manager = audioManager ?: return
        val androidHandle = requireNotNull(handle as? AndroidDirectAudioFocusHandle) {
            "Unexpected direct audio focus handle"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.abandonAudioFocusRequest(requireNotNull(androidHandle.request))
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    override fun enterCommunicationMode(): Int? {
        val manager = audioManager ?: return null
        val mode = manager.mode
        if (mode == AudioManager.MODE_IN_COMMUNICATION) return null
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        return mode
    }

    override fun restoreAudioMode(mode: Int) {
        audioManager?.mode = mode
    }

    override fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun captureDevices(): List<DirectAudioCaptureDevice> {
        val manager = audioManager ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { device ->
            val routeDevice = device.toVoiceAudioRouteDevice()
            DirectAudioCaptureDevice(
                routeDevice = routeDevice,
                safeLabel = routeDevice.debugLabel(),
                handle = AndroidDirectAudioCaptureDeviceHandle(device),
            )
        }
    }

    override fun recorder(recorder: AudioRecord): DirectAudioRecorder = DirectAudioRecorder { device ->
        recorder.setPreferredDevice(device.requireAudioDeviceInfo())
    }

    @SuppressLint("MissingPermission")
    override fun setCommunicationDevice(device: DirectAudioCaptureDevice): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return audioManager?.setCommunicationDevice(device.requireAudioDeviceInfo()) == true
    }

    override fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager?.clearCommunicationDevice()
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

    @SuppressLint("MissingPermission")
    override fun requestBluetoothHeadsetProxy(listener: DirectBluetoothHeadsetListener): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val androidListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    (proxy as? BluetoothHeadset)?.let {
                        listener.onConnected(AndroidDirectBluetoothHeadset(it))
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) listener.onDisconnected()
            }
        }
        serviceListener = androidListener
        return adapter.getProfileProxy(context, androidListener, BluetoothProfile.HEADSET)
    }

    override fun awaitBluetoothHeadset(current: () -> DirectBluetoothHeadset?): DirectBluetoothHeadset? {
        repeat(BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS) {
            current()?.let { return it }
            Thread.sleep(BLUETOOTH_HEADSET_PROFILE_WAIT_MS)
        }
        return current()
    }

    override fun closeBluetoothHeadsetProxy(headset: DirectBluetoothHeadset) {
        val androidHeadset = requireNotNull(headset as? AndroidDirectBluetoothHeadset) {
            "Unexpected direct Bluetooth headset handle"
        }.headset
        context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.closeProfileProxy(BluetoothProfile.HEADSET, androidHeadset)
    }

    @SuppressLint("MissingPermission")
    override fun connectedBluetoothDevices(headset: DirectBluetoothHeadset): List<DirectBluetoothDevice> {
        val androidHeadset = requireNotNull(headset as? AndroidDirectBluetoothHeadset) {
            "Unexpected direct Bluetooth headset handle"
        }.headset
        return androidHeadset.connectedDevices.map { device ->
            AndroidDirectBluetoothDevice(device, device.safeBluetoothLabel())
        }
    }

    @SuppressLint("MissingPermission")
    override fun startVoiceRecognition(
        headset: DirectBluetoothHeadset,
        device: DirectBluetoothDevice,
    ): Boolean = headset.requireAndroidHeadset()
        .startVoiceRecognition(device.requireBluetoothDevice())

    @SuppressLint("MissingPermission")
    override fun stopVoiceRecognition(headset: DirectBluetoothHeadset, device: DirectBluetoothDevice) {
        headset.requireAndroidHeadset().stopVoiceRecognition(device.requireBluetoothDevice())
    }

    override fun dispatch(block: () -> Unit) {
        scope.launch { block() }
    }

    override fun close() {
        scope.cancel()
        serviceListener = null
    }

    private fun DirectAudioCaptureDevice.requireAudioDeviceInfo(): AudioDeviceInfo =
        requireNotNull(handle as? AndroidDirectAudioCaptureDeviceHandle) {
            "Unexpected direct capture device handle"
        }.device

    private fun DirectBluetoothDevice.requireBluetoothDevice(): BluetoothDevice =
        requireNotNull(this as? AndroidDirectBluetoothDevice) {
            "Unexpected direct Bluetooth device handle"
        }.device

    private fun DirectBluetoothHeadset.requireAndroidHeadset(): BluetoothHeadset =
        requireNotNull(this as? AndroidDirectBluetoothHeadset) {
            "Unexpected direct Bluetooth headset handle"
        }.headset

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

    private companion object {
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS = 10
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_MS = 100L
    }
}

private data class AndroidDirectAudioFocusHandle(
    val request: AudioFocusRequest?,
) : DirectAudioFocusHandle

private data class AndroidDirectAudioCaptureDeviceHandle(
    val device: AudioDeviceInfo,
) : DirectAudioCaptureDeviceHandle

private data class AndroidDirectBluetoothHeadset(
    val headset: BluetoothHeadset,
) : DirectBluetoothHeadset

private data class AndroidDirectBluetoothDevice(
    val device: BluetoothDevice,
    override val safeLabel: String,
) : DirectBluetoothDevice

internal fun voiceAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
