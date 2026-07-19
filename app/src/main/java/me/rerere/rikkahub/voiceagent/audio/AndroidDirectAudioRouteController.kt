package me.rerere.rikkahub.voiceagent.audio

import android.content.Context
import android.media.AudioRecord
import android.util.Log
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
    private var closed = false
    private var nextAcquisitionId = 0L
    private val acquisitions = mutableSetOf<Long>()

    override fun acquireCapture(): VoiceAudioCaptureRouteLease {
        val acquisitionId = synchronized(lock) {
            check(!closed) { "Direct audio route controller is closed" }
            (++nextAcquisitionId).also(acquisitions::add)
        }
        val acquired = mutableListOf<DirectAudioResourceLease>()
        val focusCallbackGate = DirectAudioFocusCallbackGate()
        val routeLease = try {
            val acquiredFocus = capabilities.focus.acquire { focusChange ->
                focusCallbackGate.deliver {
                    if (VoiceAudioFocusPolicy.isFocusChangeFatal(focusChange)) {
                        onAudioError("Audio focus lost: $focusChange")
                    } else if (focusChange < 0) {
                        logWarning("Recoverable direct audio focus change: $focusChange")
                    }
                }
            }
            if (acquiredFocus == null) {
                focusCallbackGate.close()
            } else {
                acquired += acquiredFocus
            }
            acquireBestEffort(
                message = "Direct communication mode setup failed",
                action = capabilities.communicationMode::acquire,
            )?.let(acquired::add)
            val bluetoothLease = acquireBestEffort(
                message = "Direct Bluetooth capture setup failed",
                action = capabilities.bluetoothCapture::acquire,
            )
            bluetoothLease?.let(acquired::add)
            DirectVoiceAudioCaptureRouteLease(
                captureDevice = capabilities.captureDevice,
                bluetoothCapture = bluetoothLease,
                focusCallbackGate = focusCallbackGate,
                initialLeases = acquired,
                logWarning = ::logWarning,
            )
        } catch (fatal: Throwable) {
            synchronized(lock) { acquisitions.remove(acquisitionId) }
            focusCallbackGate.close()
            acquired.asReversed().forEach { lease ->
                retireBestEffort("Direct audio rollback failed", lease)
            }
            throw fatal
        }
        val accepted = synchronized(lock) {
            acquisitions.remove(acquisitionId) && !closed
        }
        if (accepted) return routeLease
        routeLease.retire()
        throw IllegalStateException("Direct audio route controller is closed")
    }

    override fun close() {
        val closeCapabilities = synchronized(lock) {
            if (closed) null else capabilities.close.also { closed = true }
        }
        closeCapabilities ?: return
        runCatching(closeCapabilities)
            .onFailure { logWarning("Direct audio capabilities close failed", it) }
    }

    private fun <Lease : DirectAudioResourceLease> acquireBestEffort(
        message: String,
        action: () -> Lease?,
    ): Lease? = runCatching(action)
        .onFailure { logWarning(message, it) }
        .getOrNull()

    private fun retireBestEffort(message: String, lease: DirectAudioResourceLease) {
        runCatching(lease::retire)
            .onFailure { logWarning(message, it) }
    }

    private fun logWarning(message: String, error: Throwable? = null) {
        runCatching {
            if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
        }
    }

    private companion object {
        const val TAG = "AndroidDirectAudioRouteController"
    }
}

private class DirectAudioFocusCallbackGate {
    private val lock = Any()
    private var closed = false

    fun deliver(callback: () -> Unit) = synchronized(lock) {
        if (!closed) callback()
    }

    fun close() = synchronized(lock) {
        closed = true
    }
}

private class DirectVoiceAudioCaptureRouteLease(
    private val captureDevice: DirectCaptureDeviceCapability,
    private val bluetoothCapture: DirectBluetoothCaptureLease?,
    private val focusCallbackGate: DirectAudioFocusCallbackGate,
    initialLeases: List<DirectAudioResourceLease>,
    private val logWarning: (String, Throwable?) -> Unit,
) : VoiceAudioCaptureRouteLease {
    private val lock = Any()
    private val retirement = RetirementBarrier()
    private val leases = initialLeases.toMutableList()
    private var retired = false

    override suspend fun prepare() {
        val bluetooth = synchronized(lock) { bluetoothCapture.takeUnless { retired } }
        bluetooth?.prepare()
    }

    override fun configureRecorder(recorder: AudioRecord) {
        val admitted = synchronized(lock) { !retired }
        if (!admitted) return
        val configured = runCatching { captureDevice.configure(recorder) }
            .onFailure { logWarning("Direct capture device configuration failed", it) }
            .getOrNull()
            ?: return
        val accepted = synchronized(lock) {
            if (retired) false else true.also { leases += configured }
        }
        if (!accepted) {
            runCatching(configured::retire)
                .onFailure { logWarning("Direct capture device rollback failed", it) }
        }
    }

    override fun retire() = retirement.retire {
        focusCallbackGate.close()
        val owned = synchronized(lock) {
            retired = true
            leases.toList().asReversed().also { leases.clear() }
        }
        owned.forEach { lease ->
            runCatching(lease::retire)
                .onFailure { logWarning("Direct audio resource retirement failed", it) }
        }
    }
}
