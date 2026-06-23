package me.rerere.rikkahub.service.generation

/**
 * The foreground-generation lifecycle ChatService drives (#360 P1a). Extracted as a narrow port so
 * ChatService depends on the THREE operations it actually calls — not on the concrete
 * [GenerationForegroundCoordinator] (which owns an Android foreground-service controller). A test can
 * supply a no-op/recording fake; production binds the coordinator.
 *
 * Implemented by [GenerationForegroundCoordinator], whose refcount/start/stop/renew state machine is
 * already unit-tested directly (GenerationForegroundCoordinatorTest).
 */
interface ForegroundGenerationLifecycle {
    /** A generation started: 0→1 edge starts the foreground service. */
    fun onGenerationStart()

    /** A generation stopped: 1→0 edge stops the foreground service. */
    fun onGenerationStop()

    /** A streaming chunk arrived: throttled WakeLock renewal while generation is live. */
    fun onStreamingProgress()
}
