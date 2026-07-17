package me.rerere.rikkahub.voiceagent

internal fun runVoiceAgentCleanupStages(vararg stages: () -> Unit) {
    var primaryFailure: Throwable? = null
    stages.forEach { stage ->
        try {
            stage()
        } catch (error: Throwable) {
            primaryFailure = primaryFailure.withCleanupFailure(error)
        }
    }
    primaryFailure?.let { throw it }
}

internal suspend fun runVoiceAgentSuspendCleanupStages(vararg stages: suspend () -> Unit) {
    var primaryFailure: Throwable? = null
    stages.forEach { stage ->
        try {
            stage()
        } catch (error: Throwable) {
            primaryFailure = primaryFailure.withCleanupFailure(error)
        }
    }
    primaryFailure?.let { throw it }
}

private fun Throwable?.withCleanupFailure(error: Throwable): Throwable = when {
    this == null -> error
    this !== error -> apply { addSuppressed(error) }
    else -> this
}
