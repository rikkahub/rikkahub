package me.rerere.rikkahub.voiceagent.audio

internal class VoiceAudioDebugCaptureRegistrationOwner<Token : Any, Recorder : Any, Registration : Any>(
    private val closeRegistration: (Registration) -> Unit,
) {
    private val lock = Any()
    private var current: OwnedRegistration<Token, Recorder, Registration>? = null

    fun publish(
        token: Token,
        recorder: Recorder,
        registration: Registration,
        isCurrent: () -> Boolean,
    ): Boolean {
        var accepted = false
        val displaced = synchronized(lock) {
            if (!isCurrent()) return@synchronized null
            accepted = true
            val previous = current
            current = OwnedRegistration(token, recorder, registration)
            previous
        }
        if (!accepted) {
            closeRegistration(registration)
            return false
        }
        displaced?.registration?.let(closeRegistration)
        return true
    }

    fun unregister(): Boolean {
        val registration = synchronized(lock) {
            current?.registration.also { current = null }
        } ?: return false
        closeRegistration(registration)
        return true
    }

    fun unregister(token: Token, recorder: Recorder): Boolean {
        val registration = synchronized(lock) {
            val owned = current
            if (owned?.token !== token || owned.recorder !== recorder) return@synchronized null
            current = null
            owned.registration
        } ?: return false
        closeRegistration(registration)
        return true
    }

    fun deliver(
        token: Token,
        recorder: Recorder,
        buffer: ByteArray,
        isCurrent: () -> Boolean,
        onPcm16: (ByteArray) -> Unit,
        terminate: () -> Boolean,
        onFailure: (Exception) -> Unit,
    ) {
        if (!isCurrent()) return
        try {
            onPcm16(buffer)
        } catch (failure: Exception) {
            try {
                onFailure(failure)
            } finally {
                try {
                    terminate()
                } finally {
                    unregister(token, recorder)
                }
            }
        }
    }

    private data class OwnedRegistration<Token : Any, Recorder : Any, Registration : Any>(
        val token: Token,
        val recorder: Recorder,
        val registration: Registration,
    )
}
