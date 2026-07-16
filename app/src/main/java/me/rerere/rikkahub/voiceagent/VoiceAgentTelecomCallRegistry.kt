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

class VoiceAgentTelecomAttemptStartException(
    val attemptId: VoiceAgentTelecomAttemptId,
    val failure: VoiceAgentTelecomFailure,
    cause: Throwable,
) : IllegalStateException(failure.detail, cause)

sealed interface VoiceAgentTelecomOutcome {
    data object Active : VoiceAgentTelecomOutcome

    data class Failed(val failure: VoiceAgentTelecomFailure) : VoiceAgentTelecomOutcome
}

class VoiceAgentTelecomCallRegistry {
    private val lock = Any()
    private val attempts = mutableMapOf<VoiceAgentTelecomAttemptId, AttemptRecord>()
    private var nextAttemptId = 0L
    private var currentAttemptId: VoiceAgentTelecomAttemptId? = null

    fun beginAttempt(): VoiceAgentTelecomAttemptId {
        var previousRecord: AttemptRecord? = null
        var previousConnection: VoiceAgentTelecomCall? = null
        var supersededCompletion: CompletableDeferred<VoiceAgentTelecomOutcome>? = null
        var supersededOutcome: VoiceAgentTelecomOutcome? = null
        val id = synchronized(lock) {
            check(nextAttemptId < Long.MAX_VALUE) { "Telecom attempt IDs exhausted" }
            val id = VoiceAgentTelecomAttemptId(++nextAttemptId)
            val previousId = currentAttemptId
            previousRecord = previousId?.let(attempts::get)
            val supersededFailure = previousId?.let {
                VoiceAgentTelecomFailure(
                    diagnosticName = "telecom_attempt_superseded",
                    detail = "Telecom attempt ${it.value} superseded by attempt ${id.value}",
                )
            }
            when (val phase = previousRecord?.phase) {
                AttemptPhase.Pending -> {
                    val outcome = VoiceAgentTelecomOutcome.Failed(checkNotNull(supersededFailure))
                    checkNotNull(previousRecord).phase = AttemptPhase.Failed(outcome.failure)
                    supersededCompletion = previousRecord.completion
                    supersededOutcome = outcome
                }
                is AttemptPhase.Activating -> {
                    checkNotNull(previousRecord).phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = checkNotNull(supersededFailure),
                    )
                }
                is AttemptPhase.Active -> {
                    checkNotNull(previousRecord).phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = checkNotNull(supersededFailure),
                    )
                    previousConnection = phase.connection
                }
                is AttemptPhase.Retiring,
                is AttemptPhase.Failed,
                null,
                -> Unit
            }
            attempts[id] = AttemptRecord()
            currentAttemptId = id
            id
        }

        val supersessionError = try {
            previousConnection?.disconnectFromApp()
            null
        } catch (error: Throwable) {
            error
        } finally {
            val retiredRecord = previousRecord
            val retiredConnection = previousConnection
            if (retiredRecord != null && retiredConnection != null) {
                finishRetiring(retiredRecord, retiredConnection)
            }
        }
        supersededOutcome?.let { supersededCompletion?.complete(it) }
        if (supersessionError != null) {
            val failure = VoiceAgentTelecomFailure(
                diagnosticName = "telecom_supersession_cleanup_failed",
                detail = supersessionError.message ?: supersessionError.javaClass.simpleName,
            )
            val completion = synchronized(lock) {
                attempts[id]?.takeIf { record ->
                    currentAttemptId == id && record.phase == AttemptPhase.Pending
                }?.also { record ->
                    record.phase = AttemptPhase.Failed(failure)
                }?.completion
            }
            completion?.complete(VoiceAgentTelecomOutcome.Failed(failure))
            throw VoiceAgentTelecomAttemptStartException(id, failure, supersessionError)
        }
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

        val activationError = runCatching(makeActive).exceptionOrNull()
        var outcome: VoiceAgentTelecomOutcome? = null
        var shouldDisconnect = false
        val accepted = synchronized(lock) {
            when (val phase = record.phase) {
                is AttemptPhase.Activating -> {
                    if (phase.connection !== connection || currentAttemptId != id) {
                        val failure = cancelledFailure(id)
                        record.phase = AttemptPhase.Retiring(connection, failure)
                        outcome = VoiceAgentTelecomOutcome.Failed(failure)
                        shouldDisconnect = true
                        false
                    } else if (activationError != null) {
                        val failure = activationFailure(activationError)
                        record.phase = AttemptPhase.Retiring(connection, failure)
                        outcome = VoiceAgentTelecomOutcome.Failed(failure)
                        shouldDisconnect = true
                        false
                    } else {
                        record.phase = AttemptPhase.Active(connection)
                        outcome = VoiceAgentTelecomOutcome.Active
                        true
                    }
                }
                is AttemptPhase.Retiring -> {
                    if (phase.connection === connection) {
                        outcome = VoiceAgentTelecomOutcome.Failed(phase.failure)
                    }
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

        if (!shouldDisconnect) {
            outcome?.let(record.completion::complete)
            return accepted
        }

        try {
            connection.disconnectFromApp()
        } finally {
            finishRetiring(record, connection)
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
                    record.phase = AttemptPhase.Retiring(phase.connection, failure)
                }
                is AttemptPhase.Active,
                is AttemptPhase.Retiring,
                is AttemptPhase.Failed,
                -> Unit
            }
        }
        outcome?.let { completion?.complete(it) }
    }

    suspend fun observeOutcome(id: VoiceAgentTelecomAttemptId): VoiceAgentTelecomOutcome {
        val completion = synchronized(lock) {
            requireNotNull(attempts[id]) { "Unknown Telecom attempt ${id.value}" }.completion
        }
        return completion.await()
    }

    fun acknowledgeOutcome(id: VoiceAgentTelecomAttemptId) {
        synchronized(lock) {
            val record = attempts[id] ?: return
            if (record.completion.isCompleted && record.phase is AttemptPhase.Failed) {
                attempts.remove(id)
                if (currentAttemptId == id) currentAttemptId = null
            }
        }
    }

    suspend fun awaitOutcome(id: VoiceAgentTelecomAttemptId): VoiceAgentTelecomOutcome {
        val outcome = observeOutcome(id)
        acknowledgeOutcome(id)
        return outcome
    }

    fun retireOwnedAttempt(id: VoiceAgentTelecomAttemptId) {
        retireAttempt(id, cancelledFailure(id))
    }

    fun isOwnedAttemptActive(id: VoiceAgentTelecomAttemptId): Boolean = synchronized(lock) {
        attempts[id]?.phase is AttemptPhase.Active
    }

    fun retireAttempt(id: VoiceAgentTelecomAttemptId, failure: VoiceAgentTelecomFailure) {
        var record: AttemptRecord? = null
        var connection: VoiceAgentTelecomCall? = null
        var completion: CompletableDeferred<VoiceAgentTelecomOutcome>? = null
        var outcome: VoiceAgentTelecomOutcome? = null
        synchronized(lock) {
            val candidate = attempts[id] ?: return
            when (val phase = candidate.phase) {
                AttemptPhase.Pending -> {
                    candidate.phase = AttemptPhase.Failed(failure)
                    completion = candidate.completion
                    outcome = VoiceAgentTelecomOutcome.Failed(failure)
                }
                is AttemptPhase.Activating -> {
                    candidate.phase = AttemptPhase.Retiring(phase.connection, failure)
                }
                is AttemptPhase.Active -> {
                    candidate.phase = AttemptPhase.Retiring(phase.connection, failure)
                    record = candidate
                    connection = phase.connection
                }
                is AttemptPhase.Retiring,
                is AttemptPhase.Failed,
                -> Unit
            }
        }
        outcome?.let { completion?.complete(it) }

        val retiringRecord = record ?: return
        val retiringConnection = connection ?: return
        try {
            retiringConnection.disconnectFromApp()
        } finally {
            finishRetiring(retiringRecord, retiringConnection)
        }
    }

    fun retiring(connection: VoiceAgentTelecomCall) {
        synchronized(lock) {
            val (_, record) = attemptForConnectionLocked(connection) ?: return
            when (val phase = record.phase) {
                is AttemptPhase.Activating,
                is AttemptPhase.Active,
                -> record.phase = AttemptPhase.Retiring(
                    connection = connection,
                    failure = VoiceAgentTelecomFailure(
                        diagnosticName = "telecom_connection_disconnected",
                        detail = if (phase is AttemptPhase.Activating) {
                            "Telecom connection disconnected during activation"
                        } else {
                            "Telecom connection disconnected"
                        },
                    ),
                )
                AttemptPhase.Pending,
                is AttemptPhase.Retiring,
                is AttemptPhase.Failed,
                -> Unit
            }
        }
    }

    fun clear(connection: VoiceAgentTelecomCall) {
        synchronized(lock) {
            val (id, record) = attemptForConnectionLocked(connection) ?: return
            if (attempts[id] === record) attempts.remove(id)
            if (currentAttemptId == id) currentAttemptId = null
        }
    }

    fun hasActiveConnection(): Boolean = synchronized(lock) {
        attempts.values.any { it.phase is AttemptPhase.Active }
    }

    fun disconnectActive() {
        val id = synchronized(lock) { currentAttemptId } ?: return
        retireOwnedAttempt(id)
    }

    private fun finishRetiring(
        record: AttemptRecord,
        connection: VoiceAgentTelecomCall,
    ) {
        var completion: CompletableDeferred<VoiceAgentTelecomOutcome>? = null
        var outcome: VoiceAgentTelecomOutcome? = null
        synchronized(lock) {
            val phase = record.phase
            if (phase is AttemptPhase.Retiring && phase.connection === connection) {
                record.phase = AttemptPhase.Failed(phase.failure)
                completion = record.completion
                outcome = VoiceAgentTelecomOutcome.Failed(phase.failure)
            }
        }
        outcome?.let { completion?.complete(it) }
    }

    private fun attemptForConnectionLocked(
        connection: VoiceAgentTelecomCall,
    ): Pair<VoiceAgentTelecomAttemptId, AttemptRecord>? = attempts.entries.firstNotNullOfOrNull { entry ->
        val phaseConnection = when (val phase = entry.value.phase) {
            is AttemptPhase.Activating -> phase.connection
            is AttemptPhase.Active -> phase.connection
            is AttemptPhase.Retiring -> phase.connection
            AttemptPhase.Pending,
            is AttemptPhase.Failed,
            -> null
        }
        (entry.key to entry.value).takeIf { phaseConnection === connection }
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

        data class Active(val connection: VoiceAgentTelecomCall) : AttemptPhase

        data class Retiring(
            val connection: VoiceAgentTelecomCall,
            val failure: VoiceAgentTelecomFailure,
        ) : AttemptPhase

        data class Failed(val failure: VoiceAgentTelecomFailure) : AttemptPhase
    }
}
