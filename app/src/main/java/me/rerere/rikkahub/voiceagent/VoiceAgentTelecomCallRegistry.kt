package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred

interface VoiceAgentTelecomCall {
    fun disconnectFromApp()
}

@JvmInline
value class VoiceAgentTelecomAttemptId(val value: Long)

data class VoiceAgentTelecomFailure(
    val diagnosticName: String,
    val detail: String,
)

sealed interface VoiceAgentTelecomOutcome {
    data object Active : VoiceAgentTelecomOutcome

    data class Failed(val failure: VoiceAgentTelecomFailure) : VoiceAgentTelecomOutcome
}

class VoiceAgentTelecomCallRegistry {
    private val lock = Any()
    private val attempts = mutableMapOf<VoiceAgentTelecomAttemptId, AttemptRecord>()
    private var nextAttemptId = 0L
    private var currentAttemptId: VoiceAgentTelecomAttemptId? = null
    private var activeConnection: VoiceAgentTelecomCall? = null

    fun beginAttempt(): VoiceAgentTelecomAttemptId {
        var previousConnection: VoiceAgentTelecomCall? = null
        var supersededCompletion: CompletableDeferred<VoiceAgentTelecomOutcome>? = null
        var supersededOutcome: VoiceAgentTelecomOutcome? = null
        val id = synchronized(lock) {
            check(nextAttemptId < Long.MAX_VALUE) { "Telecom attempt IDs exhausted" }
            val id = VoiceAgentTelecomAttemptId(++nextAttemptId)
            val previousId = currentAttemptId
            val previousRecord = previousId?.let(attempts::get)
            val supersededFailure = previousId?.let {
                VoiceAgentTelecomFailure(
                    diagnosticName = "telecom_attempt_superseded",
                    detail = "Telecom attempt ${it.value} superseded by attempt ${id.value}",
                )
            }
            when (val phase = previousRecord?.phase) {
                AttemptPhase.Pending -> {
                    val outcome = VoiceAgentTelecomOutcome.Failed(checkNotNull(supersededFailure))
                    previousRecord.phase = AttemptPhase.Failed(outcome.failure)
                    supersededCompletion = previousRecord.completion
                    supersededOutcome = outcome
                }
                is AttemptPhase.Activating -> {
                    previousRecord.phase = AttemptPhase.Cancelling(
                        connection = phase.connection,
                        failure = checkNotNull(supersededFailure),
                    )
                }
                is AttemptPhase.Active -> {
                    if (activeConnection === phase.connection) {
                        activeConnection = null
                        previousConnection = phase.connection
                    }
                }
                is AttemptPhase.Cancelling,
                is AttemptPhase.Failed,
                null,
                -> Unit
            }
            attempts[id] = AttemptRecord()
            currentAttemptId = id
            id
        }
        previousConnection?.disconnectFromApp()
        supersededOutcome?.let { supersededCompletion?.complete(it) }
        return id
    }

    fun activate(
        id: VoiceAgentTelecomAttemptId,
        connection: VoiceAgentTelecomCall,
        makeActive: () -> Unit = {},
    ): Boolean {
        val record = synchronized(lock) {
            attempts[id]?.takeIf { candidate ->
                currentAttemptId == id && candidate.phase == AttemptPhase.Pending
            }?.also { candidate ->
                candidate.phase = AttemptPhase.Activating(connection)
            }
        }
        if (record == null) {
            connection.disconnectFromApp()
            return false
        }

        val activationError = try {
            makeActive()
            null
        } catch (error: Throwable) {
            error
        }

        var outcome: VoiceAgentTelecomOutcome? = null
        var shouldDisconnect = false
        val accepted = synchronized(lock) {
            when (val phase = record.phase) {
                is AttemptPhase.Activating -> {
                    if (phase.connection !== connection || currentAttemptId != id) {
                        val failure = cancelledFailure(id)
                        record.phase = AttemptPhase.Failed(failure)
                        outcome = VoiceAgentTelecomOutcome.Failed(failure)
                        shouldDisconnect = true
                        false
                    } else if (activationError != null) {
                        val failure = activationFailure(activationError)
                        record.phase = AttemptPhase.Failed(failure)
                        outcome = VoiceAgentTelecomOutcome.Failed(failure)
                        shouldDisconnect = true
                        false
                    } else {
                        record.phase = AttemptPhase.Active(connection)
                        activeConnection = connection
                        outcome = VoiceAgentTelecomOutcome.Active
                        true
                    }
                }
                is AttemptPhase.Cancelling -> {
                    record.phase = AttemptPhase.Failed(phase.failure)
                    outcome = VoiceAgentTelecomOutcome.Failed(phase.failure)
                    shouldDisconnect = true
                    false
                }
                AttemptPhase.Pending,
                is AttemptPhase.Active,
                is AttemptPhase.Failed,
                -> {
                    shouldDisconnect = true
                    false
                }
            }
        }

        try {
            if (shouldDisconnect) {
                connection.disconnectFromApp()
            }
        } finally {
            outcome?.let(record.completion::complete)
        }
        return accepted
    }

    fun fail(id: VoiceAgentTelecomAttemptId, failure: VoiceAgentTelecomFailure) {
        var completion: CompletableDeferred<VoiceAgentTelecomOutcome>? = null
        var outcome: VoiceAgentTelecomOutcome? = null
        synchronized(lock) {
            val record = attempts[id] ?: return@synchronized
            when (val phase = record.phase) {
                AttemptPhase.Pending -> {
                    record.phase = AttemptPhase.Failed(failure)
                    completion = record.completion
                    outcome = VoiceAgentTelecomOutcome.Failed(failure)
                }
                is AttemptPhase.Activating -> {
                    record.phase = AttemptPhase.Cancelling(phase.connection, failure)
                }
                is AttemptPhase.Active,
                is AttemptPhase.Cancelling,
                is AttemptPhase.Failed,
                -> Unit
            }
        }
        outcome?.let { completion?.complete(it) }
    }

    suspend fun awaitOutcome(id: VoiceAgentTelecomAttemptId): VoiceAgentTelecomOutcome {
        val completion = synchronized(lock) {
            requireNotNull(attempts[id]) { "Unknown Telecom attempt ${id.value}" }.completion
        }
        return completion.await()
    }

    fun clear(connection: VoiceAgentTelecomCall) {
        synchronized(lock) {
            if (activeConnection === connection) {
                activeConnection = null
                return@synchronized
            }
            val record = currentAttemptId?.let(attempts::get) ?: return@synchronized
            val phase = record.phase
            if (phase is AttemptPhase.Activating && phase.connection === connection) {
                record.phase = AttemptPhase.Cancelling(
                    connection = connection,
                    failure = VoiceAgentTelecomFailure(
                        diagnosticName = "telecom_connection_disconnected",
                        detail = "Telecom connection disconnected during activation",
                    ),
                )
            }
        }
    }

    fun hasActiveConnection(): Boolean = synchronized(lock) {
        activeConnection != null
    }

    fun disconnectActive() {
        var connection: VoiceAgentTelecomCall? = null
        var completion: CompletableDeferred<VoiceAgentTelecomOutcome>? = null
        var outcome: VoiceAgentTelecomOutcome? = null
        synchronized(lock) {
            val id = currentAttemptId ?: return@synchronized
            val record = attempts[id] ?: return@synchronized
            val failure = cancelledFailure(id)
            when (val phase = record.phase) {
                AttemptPhase.Pending -> {
                    record.phase = AttemptPhase.Failed(failure)
                    completion = record.completion
                    outcome = VoiceAgentTelecomOutcome.Failed(failure)
                }
                is AttemptPhase.Activating -> {
                    record.phase = AttemptPhase.Cancelling(phase.connection, failure)
                }
                is AttemptPhase.Active -> {
                    if (activeConnection === phase.connection) {
                        activeConnection = null
                        connection = phase.connection
                    }
                }
                is AttemptPhase.Cancelling,
                is AttemptPhase.Failed,
                -> Unit
            }
        }
        connection?.disconnectFromApp()
        outcome?.let { completion?.complete(it) }
    }

    private fun cancelledFailure(id: VoiceAgentTelecomAttemptId) = VoiceAgentTelecomFailure(
        diagnosticName = "telecom_attempt_cancelled",
        detail = "Telecom attempt ${id.value} canceled by cleanup",
    )

    private fun activationFailure(error: Throwable) = VoiceAgentTelecomFailure(
        diagnosticName = "telecom_activation_failed",
        detail = error.message ?: error.javaClass.simpleName,
    )

    private class AttemptRecord {
        val completion = CompletableDeferred<VoiceAgentTelecomOutcome>()
        var phase: AttemptPhase = AttemptPhase.Pending
    }

    private sealed interface AttemptPhase {
        data object Pending : AttemptPhase

        data class Activating(val connection: VoiceAgentTelecomCall) : AttemptPhase

        data class Cancelling(
            val connection: VoiceAgentTelecomCall,
            val failure: VoiceAgentTelecomFailure,
        ) : AttemptPhase

        data class Active(val connection: VoiceAgentTelecomCall) : AttemptPhase

        data class Failed(val failure: VoiceAgentTelecomFailure) : AttemptPhase
    }
}
