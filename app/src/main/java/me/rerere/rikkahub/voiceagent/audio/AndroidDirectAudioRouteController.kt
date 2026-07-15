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

internal class AndroidDirectAudioRouteController(
    context: Context,
    private val onAudioError: (String) -> Unit,
) : VoiceAudioRouteController {
    private val context = context.applicationContext
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasSelectedCommunicationDevice = false
    private var hasStartedBluetoothSco = false
    private var bluetoothProfileProxyRequested = false
    private var wantsBluetoothHeadsetVoiceRecognition = false
    private var bluetoothVoiceRecognitionDevice: BluetoothDevice? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var previousAudioMode: Int? = null
    private var hasAudioFocus = false
    private var closed = false
    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HEADSET) return
            val headset = proxy as? BluetoothHeadset ?: return
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
            Log.d(TAG, "Direct Bluetooth headset profile connected")
            if (state.shouldRequestVoiceRecognition) {
                scope.launch {
                    requestBluetoothHeadsetVoiceRecognition(headset)
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HEADSET) return
            synchronized(lock) {
                bluetoothHeadset = null
                bluetoothVoiceRecognitionDevice = null
                bluetoothProfileProxyRequested = false
                wantsBluetoothHeadsetVoiceRecognition = false
            }
            Log.d(TAG, "Direct Bluetooth headset profile disconnected")
        }
    }

    override fun beforeCapture() {
        synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
        }
        requestAudioFocusBestEffort()
        prepareVoiceCommunicationRoutingBestEffort()
    }

    override fun configureRecorder(recorder: AudioRecord) {
        synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
        }
        val device = selectPreferredBluetoothCaptureDeviceOrNull() ?: return
        val preferredAccepted = runCatching {
            synchronized(lock) {
                check(!closed) { "Direct audio route controller is closed" }
                recorder.setPreferredDevice(device)
            }
        }
            .onFailure { Log.w(TAG, "Direct preferred Bluetooth device failed", it) }
            .getOrDefault(false)
        val communicationAccepted = setCommunicationDeviceBestEffort(device)
        Log.d(
            TAG,
            "Direct capture route=${device.safeRouteLabel()} " +
                "preferredAccepted=$preferredAccepted communicationAccepted=$communicationAccepted",
        )
    }

    override fun afterCapture() {
        if (!isClosed()) clearVoiceCommunicationRoutingBestEffort()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        clearVoiceCommunicationRoutingBestEffort()
        closeBluetoothHeadsetProxy()
        abandonAudioFocus()
        scope.cancel()
    }

    private fun selectPreferredBluetoothCaptureDeviceOrNull(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothConnectPermission()) {
            Log.d(TAG, "Direct Bluetooth route skipped: BLUETOOTH_CONNECT not granted")
            return null
        }
        val devices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.onFailure {
            Log.w(TAG, "Direct capture route enumeration failed", it)
        }.getOrDefault(emptyList())
        val routeDevices = devices.map { it.toVoiceAudioRouteDevice() }
        val selected = selectPreferredCaptureRoute(routeDevices)
        Log.d(
            TAG,
            "Direct capture routes available=${routeDevices.joinToString { it.debugLabel() }} " +
                "selected=${selected?.debugLabel() ?: "default"}",
        )
        return selected?.let { route -> devices.firstOrNull { it.id == route.id } }
    }

    @SuppressLint("MissingPermission")
    private fun setCommunicationDeviceBestEffort(device: AudioDeviceInfo): Boolean {
        val manager = audioManager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            synchronized(lock) {
                check(!closed) { "Direct audio route controller is closed" }
                val accepted = manager.setCommunicationDevice(device)
                if (accepted) {
                    hasSelectedCommunicationDevice = true
                }
                accepted
            }
        }.onFailure {
            Log.w(TAG, "Direct communication route failed", it)
        }.getOrDefault(false)
    }

    private fun clearCommunicationDeviceSelection() {
        val manager = audioManager ?: return
        val shouldClear = synchronized(lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !hasSelectedCommunicationDevice) {
                false
            } else {
                hasSelectedCommunicationDevice = false
                true
            }
        }
        if (!shouldClear) return
        runCatching {
            manager.clearCommunicationDevice()
        }.onFailure {
            Log.w(TAG, "Direct communication route clear failed", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun prepareVoiceCommunicationRoutingBestEffort() {
        val manager = audioManager ?: return
        runCatching {
            synchronized(lock) {
                check(!closed) { "Direct audio route controller is closed" }
                val mode = manager.mode
                if (mode != AudioManager.MODE_IN_COMMUNICATION) {
                    manager.mode = AudioManager.MODE_IN_COMMUNICATION
                    previousAudioMode = mode
                }
            }
        }.onFailure {
            Log.w(TAG, "Direct communication mode setup failed", it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothConnectPermission()) {
            Log.d(TAG, "Direct Bluetooth SCO skipped: BLUETOOTH_CONNECT not granted")
            return
        }
        startBluetoothHeadsetVoiceRecognitionBestEffort()
        runCatching {
            synchronized(lock) {
                check(!closed) { "Direct audio route controller is closed" }
                manager.startBluetoothSco()
                hasStartedBluetoothSco = true
                manager.isBluetoothScoOn = true
            }
            Log.d(TAG, "Direct requested Bluetooth SCO")
        }.onFailure {
            Log.w(TAG, "Direct Bluetooth SCO request failed", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun clearVoiceCommunicationRoutingBestEffort() {
        val manager = audioManager ?: return
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
            runCatching {
                manager.isBluetoothScoOn = false
                manager.stopBluetoothSco()
            }.onFailure {
                Log.w(TAG, "Direct Bluetooth SCO stop failed", it)
            }
        }
        val mode = synchronized(lock) {
            previousAudioMode.also { previousAudioMode = null }
        }
        mode?.let {
            runCatching {
                manager.mode = it
            }.onFailure {
                Log.w(TAG, "Direct communication mode restore failed", it)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothHeadsetVoiceRecognitionBestEffort() {
        synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
            wantsBluetoothHeadsetVoiceRecognition = true
        }
        val headset = bluetoothHeadsetProxyOrNull()
        if (headset == null) {
            Log.d(TAG, "Direct Bluetooth headset voice recognition skipped: profile unavailable")
            return
        }
        requestBluetoothHeadsetVoiceRecognition(headset)
    }

    @SuppressLint("MissingPermission")
    private fun requestBluetoothHeadsetVoiceRecognition(headset: BluetoothHeadset) {
        val shouldRequest = synchronized(lock) {
            !closed && wantsBluetoothHeadsetVoiceRecognition && bluetoothVoiceRecognitionDevice == null
        }
        if (!shouldRequest) return
        val device = runCatching {
            headset.connectedDevices.firstOrNull()
        }.onFailure {
            Log.w(TAG, "Direct Bluetooth headset device lookup failed", it)
        }.getOrNull()
        if (device == null) {
            Log.d(TAG, "Direct Bluetooth headset voice recognition skipped: no connected headset")
            return
        }
        val accepted = runCatching {
            synchronized(lock) {
                if (closed || !wantsBluetoothHeadsetVoiceRecognition || bluetoothVoiceRecognitionDevice != null) {
                    return@synchronized false
                }
                headset.startVoiceRecognition(device).also { started ->
                    if (started) bluetoothVoiceRecognitionDevice = device
                }
            }
        }.onFailure {
            Log.w(TAG, "Direct Bluetooth headset voice recognition request failed", it)
        }.getOrDefault(false)
        Log.d(
            TAG,
            "Direct Bluetooth headset voice recognition requested device=${device.safeBluetoothLabel()} " +
                "accepted=$accepted",
        )
    }

    @SuppressLint("MissingPermission")
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
        runCatching {
            headset.stopVoiceRecognition(device)
        }.onFailure {
            Log.w(TAG, "Direct Bluetooth headset voice recognition stop failed", it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothHeadsetProxyOrNull(): BluetoothHeadset? {
        synchronized(lock) {
            bluetoothHeadset?.let { return it }
            if (closed) return null
            if (!bluetoothProfileProxyRequested) {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                bluetoothProfileProxyRequested = runCatching {
                    adapter?.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.HEADSET) == true
                }.onFailure {
                    Log.w(TAG, "Direct Bluetooth headset profile request failed", it)
                }.getOrDefault(false)
            }
        }
        repeat(BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS) {
            synchronized(lock) {
                bluetoothHeadset?.let { return it }
                if (closed) return null
            }
            Thread.sleep(BLUETOOTH_HEADSET_PROFILE_WAIT_MS)
        }
        return synchronized(lock) { bluetoothHeadset }
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

    private fun closeBluetoothHeadsetProxy(headset: BluetoothHeadset) {
        runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.closeProfileProxy(BluetoothProfile.HEADSET, headset)
        }.onFailure {
            Log.w(TAG, "Direct Bluetooth headset profile close failed", it)
        }
    }

    private fun requestAudioFocusBestEffort() {
        val manager = audioManager ?: return
        synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
            if (hasAudioFocus) return
        }

        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(voiceAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (VoiceAudioFocusPolicy.isFocusChangeFatal(focusChange)) {
                        onAudioError("Audio focus lost: $focusChange")
                    } else if (focusChange < 0) {
                        Log.w(TAG, "Recoverable direct audio focus change: $focusChange")
                    }
                }
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
            Log.w(TAG, "Voice Agent direct audio focus request was not granted: $result")
            return
        }
        val acquiredAfterClose = synchronized(lock) {
            if (closed) {
                true
            } else {
                audioFocusRequest = request
                hasAudioFocus = true
                false
            }
        }
        if (acquiredAfterClose) abandonAudioFocus(manager, request)
    }

    private fun abandonAudioFocus() {
        val state = synchronized(lock) {
            if (!hasAudioFocus) return
            hasAudioFocus = false
            audioFocusRequest.also { audioFocusRequest = null }
        }
        val manager = audioManager ?: return
        abandonAudioFocus(manager, state)
    }

    private fun abandonAudioFocus(manager: AudioManager, request: AudioFocusRequest?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            manager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun hasBluetoothConnectPermission(): Boolean =
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

    private fun AudioDeviceInfo.safeRouteLabel(): String =
        "${id}:${toVoiceAudioRouteDevice().type.name}:" +
            productName?.toString().orEmpty().ifBlank { "unnamed" }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeBluetoothLabel(): String =
        "${name ?: "unnamed"}:${address ?: "unknown"}"

    private companion object {
        const val TAG = "AndroidDirectAudioRouteController"
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_STEPS = 10
        const val BLUETOOTH_HEADSET_PROFILE_WAIT_MS = 100L
    }

    private data class ConnectedHeadsetState(
        val shouldClose: Boolean,
        val shouldRequestVoiceRecognition: Boolean,
    )
}

internal fun voiceAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
