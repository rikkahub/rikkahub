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

    override fun acquireCapture(): VoiceAudioCaptureRouteLease = synchronized(lock) {
        check(!closed) { "Direct audio route controller is closed" }
        val acquired = mutableListOf<DirectAudioResourceLease>()
        try {
            capabilities.focus.acquire { focusChange ->
                if (VoiceAudioFocusPolicy.isFocusChangeFatal(focusChange)) {
                    onAudioError("Audio focus lost: $focusChange")
                } else if (focusChange < 0) {
                    logWarning("Recoverable direct audio focus change: $focusChange")
                }
            }?.let(acquired::add)
            acquireBestEffort(
                message = "Direct communication mode setup failed",
                action = capabilities.communicationMode::acquire,
            )?.let(acquired::add)
            acquireBestEffort(
                message = "Direct Bluetooth capture setup failed",
                action = capabilities.bluetoothCapture::acquire,
            )?.let(acquired::add)
            DirectVoiceAudioCaptureRouteLease(
                captureDevice = capabilities.captureDevice,
                initialLeases = acquired,
                logWarning = ::logWarning,
            )
        } catch (fatal: Throwable) {
            acquired.asReversed().forEach { lease ->
                retireBestEffort("Direct audio rollback failed", lease)
            }
            throw fatal
        }
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else true.also { closed = true }
        }
        if (!shouldClose) return
        runCatching(capabilities.close)
            .onFailure { logWarning("Direct audio capabilities close failed", it) }
    }

    private fun acquireBestEffort(
        message: String,
        action: () -> DirectAudioResourceLease?,
    ): DirectAudioResourceLease? = runCatching(action)
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

internal class DirectVoiceAudioCaptureRouteLease(
    private val captureDevice: DirectCaptureDeviceCapability,
    initialLeases: List<DirectAudioResourceLease>,
    private val logWarning: (String, Throwable?) -> Unit,
) : VoiceAudioCaptureRouteLease {
    private val lock = Any()
    private val retirement = RetirementBarrier()
    private val leases = initialLeases.toMutableList()
    private var retired = false

    override fun configureRecorder(recorder: AudioRecord) {
        synchronized(lock) {
            if (retired) return
            runCatching { captureDevice.configure(recorder) }
                .onFailure { logWarning("Direct capture device configuration failed", it) }
                .getOrNull()
                ?.let(leases::add)
        }
    }

    override fun retire() = retirement.retire {
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
