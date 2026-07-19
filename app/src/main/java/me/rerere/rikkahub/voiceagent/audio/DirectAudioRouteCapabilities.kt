package me.rerere.rikkahub.voiceagent.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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

internal interface DirectBluetoothCaptureLease : DirectAudioResourceLease {
    suspend fun prepare()
}

internal fun interface DirectBluetoothCaptureCapability {
    fun acquire(): DirectBluetoothCaptureLease?
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

internal interface DirectBluetoothHeadsetListener<Headset : Any> {
    fun onConnected(headset: Headset)
    fun onDisconnected()
}

internal interface DirectBluetoothCaptureOperations<Headset : Any, Device : Any> {
    fun createCallbackDispatcher(): DirectBluetoothCallbackDispatcher
    fun hasConnectPermission(): Boolean
    fun requestHeadsetProxy(listener: DirectBluetoothHeadsetListener<Headset>): Boolean
    fun closeHeadsetProxy(headset: Headset)
    fun connectedDevices(headset: Headset): List<Device>
    fun safeLabel(device: Device): String
    fun startVoiceRecognition(headset: Headset, device: Device): Boolean
    fun stopVoiceRecognition(headset: Headset, device: Device)
    fun startBluetoothSco()
    fun setBluetoothScoEnabled(enabled: Boolean)
    fun stopBluetoothSco()
}

internal interface DirectBluetoothCallbackDispatcher {
    fun dispatch(block: () -> Unit)
    fun close()
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
        captureDevice = AndroidDirectCaptureDeviceAdapter(context, audioManager),
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

internal class SystemDirectBluetoothCaptureCapability<Headset : Any, Device : Any>(
    private val operations: DirectBluetoothCaptureOperations<Headset, Device>,
    private val profileWaitMillis: Long = 1_000L,
) : DirectBluetoothCaptureCapability {
    private val lock = Any()
    private val factoryRetirement = RetirementBarrier()
    private var closed = false
    private var nextAcquisitionId = 0L
    private val acquisitions = mutableSetOf<Long>()

    override fun acquire(): DirectBluetoothCaptureLease? {
        val acquisitionId = synchronized(lock) {
            check(!closed) { "Direct Bluetooth capture capability is closed" }
            (++nextAcquisitionId).also(acquisitions::add)
        }
        val lease = try {
            if (!operations.hasConnectPermission()) {
                logCapabilityDebug("Direct Bluetooth SCO skipped: BLUETOOTH_CONNECT not granted")
                null
            } else {
                SystemBluetoothCaptureLease(operations, profileWaitMillis).also { it.requestProfile() }
            }
        } catch (failure: Throwable) {
            synchronized(lock) { acquisitions.remove(acquisitionId) }
            throw failure
        }
        val accepted = synchronized(lock) {
            acquisitions.remove(acquisitionId) && !closed
        }
        if (accepted) return lease
        lease?.retire()
        throw IllegalStateException("Direct Bluetooth capture capability is closed")
    }

    fun close() = factoryRetirement.retire {
        synchronized(lock) {
            closed = true
        }
    }
}

private class SystemBluetoothCaptureLease<Headset : Any, Device : Any>(
    private val operations: DirectBluetoothCaptureOperations<Headset, Device>,
    private val profileWaitMillis: Long,
) : DirectBluetoothCaptureLease {
    private val lock = Any()
    private val retirement = RetirementBarrier()
    private val callbackDispatcher = operations.createCallbackDispatcher()
    private val profile = CompletableDeferred<Headset?>()
    private val mutationAdmission = DirectBluetoothMutationAdmission()
    private var closed = false
    private var routingOpen = false
    private var headset: Headset? = null
    private val ownedHeadsets = mutableListOf<Headset>()
    private val closedHeadsets = mutableListOf<Headset>()
    private var recognition: RecognitionOwnership<Headset, Device>? = null
    private var recognitionAttempted = false
    private var scoAttempted = false
    private var startedSco = false
    private val listener = object : DirectBluetoothHeadsetListener<Headset> {
        override fun onConnected(headset: Headset) {
            val reject = synchronized(lock) {
                if (closed) {
                    true
                } else {
                    this@SystemBluetoothCaptureLease.headset = headset
                    if (ownedHeadsets.none { it === headset }) ownedHeadsets += headset
                    false
                }
            }
            if (reject) {
                closeHeadsetProxy(headset)
                return
            }
            logCapabilityDebug("Direct Bluetooth headset profile connected")
            profile.complete(headset)
            val shouldRoute = synchronized(lock) { routingOpen && !closed }
            if (shouldRoute) {
                runCatching { callbackDispatcher.dispatch { activateConnectedHeadset(headset) } }
                    .onFailure { logCapabilityWarning("Direct Bluetooth callback dispatch failed", it) }
            }
        }

        override fun onDisconnected() {
            synchronized(lock) {
                headset = null
            }
            logCapabilityDebug("Direct Bluetooth headset profile disconnected")
        }
    }

    fun requestProfile() {
        val proxyRequested = runCatching { operations.requestHeadsetProxy(listener) }
            .onFailure { logCapabilityWarning("Direct Bluetooth headset profile request failed", it) }
            .getOrDefault(false)
        if (!proxyRequested) profile.complete(null)
    }

    override suspend fun prepare() {
        val admitted = synchronized(lock) {
            if (closed) false else true.also { routingOpen = true }
        }
        if (!admitted) return
        withTimeoutOrNull(profileWaitMillis) { profile.await() }
        val connected = synchronized(lock) { if (closed) null else headset }
        if (connected == null) {
            logCapabilityDebug("Direct Bluetooth headset voice recognition skipped: profile unavailable")
        } else {
            requestVoiceRecognition(connected)
        }
        requestBluetoothSco()
    }

    override fun retire() {
        synchronized(lock) {
            closed = true
            mutationAdmission.close()
            profile.complete(null)
        }
        if (mutationAdmission.isCurrentThreadAdmitted()) return
        completeRetirement()
    }

    private fun completeRetirement() = retirement.retire {
        mutationAdmission.awaitDrained()
        val owned = synchronized(lock) {
            BluetoothCaptureResources(
                headsets = ownedHeadsets.toList().also { ownedHeadsets.clear() },
                recognition = recognition.also { recognition = null },
                stoppedSco = startedSco.also { startedSco = false },
            ).also { headset = null }
        }
        owned.recognition?.let { recognized ->
            runCatching { operations.stopVoiceRecognition(recognized.headset, recognized.device) }
                .onFailure { logCapabilityWarning("Direct Bluetooth headset voice recognition stop failed", it) }
        }
        if (owned.stoppedSco) {
            runCatching { operations.setBluetoothScoEnabled(false) }
                .onFailure { logCapabilityWarning("Direct Bluetooth SCO disable failed", it) }
            runCatching(operations::stopBluetoothSco)
                .onFailure { logCapabilityWarning("Direct Bluetooth SCO stop failed", it) }
        }
        owned.headsets.forEach(::closeHeadsetProxy)
        runCatching(callbackDispatcher::close)
            .onFailure { logCapabilityWarning("Direct Bluetooth callback dispatcher close failed", it) }
    }

    private fun activateConnectedHeadset(connected: Headset) {
        requestVoiceRecognition(connected)
        requestBluetoothSco()
    }

    private fun requestBluetoothSco() {
        if (!mutationAdmission.tryAdmit()) return
        try {
            val shouldRequest = synchronized(lock) {
                if (closed || !routingOpen || scoAttempted) {
                    false
                } else {
                    scoAttempted = true
                    true
                }
            }
            if (!shouldRequest) return
            val started = runCatching {
                operations.startBluetoothSco()
                true
            }.onFailure { logCapabilityWarning("Direct Bluetooth SCO request failed", it) }
                .getOrDefault(false)
            if (!started) return
            val accepted = synchronized(lock) {
                if (closed) false else true.also { startedSco = true }
            }
            if (!accepted) {
                runCatching(operations::stopBluetoothSco)
                    .onFailure { logCapabilityWarning("Direct Bluetooth SCO rollback failed", it) }
                return
            }
            runCatching { operations.setBluetoothScoEnabled(true) }
                .onFailure { logCapabilityWarning("Direct Bluetooth SCO enable failed", it) }
            val rollback = synchronized(lock) {
                if (!closed && startedSco) {
                    false
                } else {
                    startedSco = false
                    true
                }
            }
            if (rollback) {
                runCatching { operations.setBluetoothScoEnabled(false) }
                    .onFailure { logCapabilityWarning("Direct Bluetooth SCO disable rollback failed", it) }
                runCatching(operations::stopBluetoothSco)
                    .onFailure { logCapabilityWarning("Direct Bluetooth SCO stop rollback failed", it) }
                return
            }
            logCapabilityDebug("Direct requested Bluetooth SCO")
        } finally {
            if (mutationAdmission.leave()) completeRetirement()
        }
    }

    private fun requestVoiceRecognition(connected: Headset) {
        if (!mutationAdmission.tryAdmit()) return
        try {
            val shouldRequest = synchronized(lock) {
                if (closed || !routingOpen || headset !== connected || recognitionAttempted) {
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
            val stillCurrent = synchronized(lock) { !closed && headset === connected }
            if (!stillCurrent) return
            val started = runCatching { operations.startVoiceRecognition(connected, device) }
                .onFailure { logCapabilityWarning("Direct Bluetooth headset voice recognition request failed", it) }
                .getOrDefault(false)
            val accepted = if (started) {
                synchronized(lock) {
                    if (closed || headset !== connected || recognition != null) {
                        false
                    } else {
                        recognition = RecognitionOwnership(connected, device)
                        true
                    }
                }
            } else {
                false
            }
            if (started && !accepted) {
                runCatching { operations.stopVoiceRecognition(connected, device) }
                    .onFailure { logCapabilityWarning("Direct Bluetooth recognition rollback failed", it) }
            }
            val safeLabel = runCatching { operations.safeLabel(device) }.getOrDefault("unknown")
            logCapabilityDebug(
                "Direct Bluetooth headset voice recognition requested " +
                    "device=$safeLabel accepted=$accepted",
            )
        } finally {
            if (mutationAdmission.leave()) completeRetirement()
        }
    }

    private fun closeHeadsetProxy(connected: Headset) {
        val claimed = synchronized(lock) {
            if (closedHeadsets.any { it === connected }) {
                false
            } else {
                closedHeadsets += connected
                true
            }
        }
        if (!claimed) return
        runCatching { operations.closeHeadsetProxy(connected) }
            .onFailure { logCapabilityWarning("Direct Bluetooth headset profile close failed", it) }
    }
}

internal fun directBluetoothCaptureAvailable(
    audioManagerAvailable: Boolean,
    hasConnectPermission: () -> Boolean,
): Boolean = audioManagerAvailable && hasConnectPermission()

private data class BluetoothCaptureResources<Headset : Any, Device : Any>(
    val headsets: List<Headset>,
    val recognition: RecognitionOwnership<Headset, Device>?,
    val stoppedSco: Boolean,
)

private data class RecognitionOwnership<Headset : Any, Device : Any>(
    val headset: Headset,
    val device: Device,
)

private class DirectBluetoothMutationAdmission {
    private val lock = ReentrantLock()
    private val admissionsChanged = lock.newCondition()
    private val admissionsByThread = IdentityHashMap<Thread, Int>()
    private var accepting = true
    private var admissionCount = 0

    fun tryAdmit(): Boolean = lock.withLock {
        if (!accepting) return false
        val thread = Thread.currentThread()
        admissionsByThread[thread] = (admissionsByThread[thread] ?: 0) + 1
        admissionCount += 1
        true
    }

    fun close() = lock.withLock {
        accepting = false
        admissionsChanged.signalAll()
    }

    fun isCurrentThreadAdmitted(): Boolean = lock.withLock {
        admissionsByThread.containsKey(Thread.currentThread())
    }

    fun leave(): Boolean = lock.withLock {
        val thread = Thread.currentThread()
        val threadAdmissions = requireNotNull(admissionsByThread[thread])
        if (threadAdmissions == 1) {
            admissionsByThread.remove(thread)
        } else {
            admissionsByThread[thread] = threadAdmissions - 1
        }
        admissionCount -= 1
        admissionsChanged.signalAll()
        !accepting && !admissionsByThread.containsKey(thread)
    }

    fun awaitDrained() {
        val currentThread = Thread.currentThread()
        var interrupted = false
        lock.withLock {
            while (admissionCount > 0) {
                try {
                    admissionsChanged.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) currentThread.interrupt()
    }
}

private fun retirementLease(retire: () -> Unit): DirectAudioResourceLease {
    val retirement = RetirementBarrier()
    return DirectAudioResourceLease { retirement.retire(retire) }
}

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
