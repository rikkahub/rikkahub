package me.rerere.rikkahub.voiceagent.audio

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import me.rerere.rikkahub.voiceagent.RetirementBarrier

internal class AndroidDirectAudioRouteController(
    private val capabilities: DirectAudioRouteCapabilities,
    private val onAudioError: (String) -> Unit,
) : VoiceAudioRouteController {
    constructor(context: Context, onAudioError: (String) -> Unit) : this(
        capabilities = systemDirectAudioRouteCapabilities(context.applicationContext),
        onAudioError = onAudioError,
    )

    private val lock = Any()
    private val closeLock = Any()
    private val captureTransitionLock = Any()
    private val nonFocusOperations = DirectAudioOperationGate()
    private var focusLease: DirectAudioResourceLease? = null
    private var communicationModeLease: DirectAudioResourceLease? = null
    private var bluetoothCaptureLease: DirectAudioResourceLease? = null
    private var captureDeviceLease: DirectAudioResourceLease? = null
    private var captureDeviceGeneration: Long? = null
    private var closed = false
    private var captureGeneration = 0L

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
            clearAfterCapture = { clearAfterCapture(generation) },
        )
    }

    private fun prepareForCapture() {
        synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
        }
        acquireFocusIfNeeded()
        if (isClosed()) return
        acquireCommunicationModeIfNeeded()
        if (isClosed()) return
        acquireBluetoothCaptureIfNeeded()
    }

    private fun acquireFocusIfNeeded() {
        val shouldAcquire = synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
            focusLease == null
        }
        if (!shouldAcquire) return
        val acquired = capabilities.focus.acquire { focusChange ->
            if (VoiceAudioFocusPolicy.isFocusChangeFatal(focusChange)) {
                onAudioError("Audio focus lost: $focusChange")
            } else if (focusChange < 0) {
                logWarning("Recoverable direct audio focus change: $focusChange")
            }
        } ?: return
        val retireImmediately = synchronized(lock) {
            if (closed || focusLease != null) {
                true
            } else {
                focusLease = acquired
                false
            }
        }
        if (retireImmediately) retireBestEffort("Direct audio focus retirement failed", acquired)
    }

    private fun acquireCommunicationModeIfNeeded() {
        if (!nonFocusOperations.enter()) return
        try {
            val shouldAcquire = synchronized(lock) { !closed && communicationModeLease == null }
            if (!shouldAcquire) return
            val acquired = runCatching(capabilities.communicationMode::acquire)
                .onFailure { logWarning("Direct communication mode setup failed", it) }
                .getOrNull() ?: return
            val retireImmediately = synchronized(lock) {
                if (closed || communicationModeLease != null) {
                    true
                } else {
                    communicationModeLease = acquired
                    false
                }
            }
            if (retireImmediately) retireBestEffort("Direct communication mode retirement failed", acquired)
        } finally {
            nonFocusOperations.exit()
        }
    }

    private fun acquireBluetoothCaptureIfNeeded() {
        if (!nonFocusOperations.enter()) return
        try {
            val shouldAcquire = synchronized(lock) { !closed && bluetoothCaptureLease == null }
            if (!shouldAcquire) return
            val acquired = capabilityBestEffort(
                message = "Direct Bluetooth capture setup failed",
                action = capabilities.bluetoothCapture::acquire,
            ) ?: return
            val retireImmediately = synchronized(lock) {
                if (closed || bluetoothCaptureLease != null) {
                    true
                } else {
                    bluetoothCaptureLease = acquired
                    false
                }
            }
            if (retireImmediately) retireBestEffort("Direct Bluetooth capture retirement failed", acquired)
        } finally {
            nonFocusOperations.exit()
        }
    }

    private fun configureCaptureRecorder(generation: Long, recorder: AudioRecord) {
        synchronized(captureTransitionLock) {
            check(nonFocusOperations.enter()) { "Direct audio route controller is closed" }
            try {
                val priorDeviceLease = synchronized(lock) {
                    check(!closed) { "Direct audio route controller is closed" }
                    check(captureGeneration == generation) { "Direct audio capture route lease is stale" }
                    if (captureDeviceLease != null && captureDeviceGeneration == generation) return
                    detachCaptureDeviceLease()
                }
                priorDeviceLease?.let {
                    retireBestEffort("Direct capture device retirement failed", it)
                }
                val acquired = capabilityBestEffort("Direct capture device configuration failed") {
                    capabilities.captureDevice.configure(recorder)
                } ?: return
                val retireImmediately = synchronized(lock) {
                    if (closed || captureGeneration != generation || captureDeviceLease != null) {
                        true
                    } else {
                        captureDeviceLease = acquired
                        captureDeviceGeneration = generation
                        false
                    }
                }
                if (retireImmediately) {
                    retireBestEffort("Direct capture device retirement failed", acquired)
                    synchronized(lock) {
                        check(!closed) { "Direct audio route controller is closed" }
                        check(captureGeneration == generation) { "Direct audio capture route lease is stale" }
                    }
                }
            } finally {
                nonFocusOperations.exit()
            }
        }
    }

    private fun clearAfterCapture(generation: Long) = synchronized(captureTransitionLock) {
        val owned = synchronized(lock) {
            if (closed || captureGeneration != generation) {
                null
            } else {
                captureGeneration += 1
                detachCaptureResources()
            }
        }
        owned?.retireBestEffort()
    }

    override fun close(): Unit = synchronized(closeLock) {
        synchronized(lock) {
            if (closed) return
            closed = true
            captureGeneration += 1
        }
        runCatching(capabilities.beginClose)
            .onFailure { logWarning("Direct audio capabilities close signal failed", it) }
        nonFocusOperations.closeAndAwait()
        val owned = synchronized(lock) {
            DetachedDirectAudioResources(
                captureDevice = detachCaptureDeviceLease(),
                bluetoothCapture = bluetoothCaptureLease.also { bluetoothCaptureLease = null },
                communicationMode = communicationModeLease.also { communicationModeLease = null },
                focus = focusLease.also { focusLease = null },
            )
        }
        owned.retireBestEffort()
        runCatching(capabilities.close)
            .onFailure { logWarning("Direct audio capabilities close failed", it) }
    }

    private fun detachCaptureResources(): DetachedDirectAudioResources =
        DetachedDirectAudioResources(
            captureDevice = detachCaptureDeviceLease(),
            bluetoothCapture = bluetoothCaptureLease.also { bluetoothCaptureLease = null },
            communicationMode = communicationModeLease.also { communicationModeLease = null },
            focus = null,
        )

    private fun detachCaptureDeviceLease(): DirectAudioResourceLease? =
        captureDeviceLease.also {
            captureDeviceLease = null
            captureDeviceGeneration = null
        }

    private fun DetachedDirectAudioResources.retireBestEffort() {
        captureDevice?.let { retireBestEffort("Direct capture device retirement failed", it) }
        bluetoothCapture?.let { retireBestEffort("Direct Bluetooth capture retirement failed", it) }
        communicationMode?.let { retireBestEffort("Direct communication mode retirement failed", it) }
        focus?.let { retireBestEffort("Direct audio focus retirement failed", it) }
    }

    private fun retireBestEffort(message: String, lease: DirectAudioResourceLease) {
        runCatching(lease::retire)
            .onFailure { logWarning(message, it) }
    }

    private fun <T> capabilityBestEffort(message: String, action: () -> T): T? =
        try {
            action()
        } catch (failure: DirectAudioPermissionProbeFailure) {
            throw failure.permissionFailure
        } catch (failure: Throwable) {
            logWarning(message, failure)
            null
        }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun logWarning(message: String, error: Throwable? = null) {
        runCatching {
            if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
        }
    }

    private companion object {
        const val TAG = "AndroidDirectAudioRouteController"
    }
}

private data class DetachedDirectAudioResources(
    val captureDevice: DirectAudioResourceLease?,
    val bluetoothCapture: DirectAudioResourceLease?,
    val communicationMode: DirectAudioResourceLease?,
    val focus: DirectAudioResourceLease?,
)

private class DirectAudioOperationGate {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var closing = false
    private var active = 0

    fun enter(): Boolean = lock.withLock {
        if (closing) return false
        active += 1
        true
    }

    fun exit() = lock.withLock {
        check(active > 0) { "Direct audio operation gate underflow" }
        active -= 1
        if (active == 0) idle.signalAll()
    }

    fun closeAndAwait() = lock.withLock {
        closing = true
        while (active > 0) idle.awaitUninterruptibly()
    }
}

internal class DirectVoiceAudioCaptureRouteLease(
    private val configureAudioRecord: (AudioRecord) -> Unit,
    private val clearAfterCapture: () -> Unit,
) : VoiceAudioCaptureRouteLease {
    private val retirement = RetirementBarrier()

    override fun configureRecorder(recorder: AudioRecord) {
        configureAudioRecord(recorder)
    }

    override fun retire() {
        retirement.retire(clearAfterCapture)
    }
}
