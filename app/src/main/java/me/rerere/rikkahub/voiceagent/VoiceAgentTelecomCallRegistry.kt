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

class VoiceAgentTelecomCallRegistry internal constructor(
    private val afterActivationOutcomeSelected: (
        VoiceAgentTelecomAttemptId,
        VoiceAgentTelecomOutcome,
    ) -> Unit,
) {
    constructor() : this(afterActivationOutcomeSelected = { _, _ -> })

    private val lock = Any()
    private val attempts = mutableMapOf<VoiceAgentTelecomAttemptId, AttemptRecord>()
    private var nextAttemptId = 0L
    private var currentAttemptId: VoiceAgentTelecomAttemptId? = null

    fun beginAttempt(): VoiceAgentTelecomAttemptId {
        var previousId: VoiceAgentTelecomAttemptId? = null
        var previousRecord: AttemptRecord? = null
        var previousConnection: VoiceAgentTelecomCall? = null
        var supersededPublication: OutcomePublication? = null
        val id = synchronized(lock) {
            check(nextAttemptId < Long.MAX_VALUE) { "Telecom attempt IDs exhausted" }
            val id = VoiceAgentTelecomAttemptId(++nextAttemptId)
            previousId = currentAttemptId
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
                    supersededPublication = selectOutcomeLocked(previousRecord, outcome)
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
            val retiredId = previousId
            if (retiredId != null && retiredRecord != null && retiredConnection != null) {
                finishRetiring(retiredId, retiredRecord, retiredConnection)
            }
        }
        supersededPublication?.publish()
        if (supersessionError != null) {
            val failure = VoiceAgentTelecomFailure(
                diagnosticName = "telecom_supersession_cleanup_failed",
                detail = supersessionError.message ?: supersessionError.javaClass.simpleName,
            )
            var publication: OutcomePublication? = null
            val outcome = VoiceAgentTelecomOutcome.Failed(failure)
            synchronized(lock) {
                attempts[id]?.takeIf { record ->
                    currentAttemptId == id && record.phase == AttemptPhase.Pending
                }?.also { record ->
                    record.phase = AttemptPhase.Failed(failure)
                    publication = selectOutcomeLocked(record, outcome)
                }
            }
            publication?.publish()
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
        var publication: OutcomePublication? = null
        var shouldDisconnect = false
        val accepted = synchronized(lock) {
            when (val phase = record.phase) {
                is AttemptPhase.Activating -> {
                    if (phase.connection !== connection || currentAttemptId != id) {
                        val failure = cancelledFailure(id)
                        record.phase = AttemptPhase.Retiring(connection, failure)
                        publication = selectOutcomeLocked(
                            record,
                            VoiceAgentTelecomOutcome.Failed(failure),
                        )
                        shouldDisconnect = true
                        false
                    } else if (activationError != null) {
                        val failure = activationFailure(activationError)
                        record.phase = AttemptPhase.Retiring(connection, failure)
                        publication = selectOutcomeLocked(
                            record,
                            VoiceAgentTelecomOutcome.Failed(failure),
                        )
                        shouldDisconnect = true
                        false
                    } else {
                        record.phase = AttemptPhase.Active(connection)
                        publication = checkNotNull(
                            selectOutcomeLocked(record, VoiceAgentTelecomOutcome.Active),
                        )
                        true
                    }
                }
                is AttemptPhase.Retiring -> {
                    if (phase.connection === connection) {
                        publication = selectOutcomeLocked(
                            record,
                            VoiceAgentTelecomOutcome.Failed(phase.failure),
                        )
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
        publication?.let { selected ->
            afterActivationOutcomeSelected(id, selected.outcome)
        }

        if (!shouldDisconnect) {
            publication?.publish()
            return accepted
        }

        try {
            connection.disconnectFromApp()
        } finally {
            finishRetiring(id, record, connection)
            publication?.publish()
        }
        return accepted
    }

    fun fail(id: VoiceAgentTelecomAttemptId, failure: VoiceAgentTelecomFailure) {
        var publication: OutcomePublication? = null
        synchronized(lock) {
            val record = attempts[id] ?: return@synchronized
            when (val phase = record.phase) {
                AttemptPhase.Pending -> {
                    record.phase = AttemptPhase.Failed(failure)
                    publication = selectOutcomeLocked(
                        record,
                        VoiceAgentTelecomOutcome.Failed(failure),
                    )
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
        publication?.publish()
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
            if (!record.completion.isCompleted) return
            when (record.phase) {
                is AttemptPhase.Active,
                is AttemptPhase.Retiring,
                -> record.outcomeAcknowledged = true
                is AttemptPhase.Failed -> {
                    attempts.remove(id)
                    if (currentAttemptId == id) currentAttemptId = null
                }
                AttemptPhase.Pending,
                is AttemptPhase.Activating,
                -> Unit
            }
        }
    }

    suspend fun awaitOutcome(id: VoiceAgentTelecomAttemptId): VoiceAgentTelecomOutcome {
        val outcome = observeOutcome(id)
        acknowledgeOutcome(id)
        return outcome
    }

    internal suspend fun awaitOutcomeIfPresent(
        id: VoiceAgentTelecomAttemptId,
    ): VoiceAgentTelecomOutcome? {
        val completion = synchronized(lock) { attempts[id]?.completion } ?: return null
        val outcome = completion.await()
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
        var publication: OutcomePublication? = null
        synchronized(lock) {
            val candidate = attempts[id] ?: return
            when (val phase = candidate.phase) {
                AttemptPhase.Pending -> {
                    candidate.phase = AttemptPhase.Failed(failure)
                    publication = selectOutcomeLocked(
                        candidate,
                        VoiceAgentTelecomOutcome.Failed(failure),
                    )
                    if (currentAttemptId == id) currentAttemptId = null
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
        publication?.publish()

        val retiringRecord = record ?: return
        val retiringConnection = connection ?: return
        try {
            retiringConnection.disconnectFromApp()
        } finally {
            finishRetiring(id, retiringRecord, retiringConnection)
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
                    failure = disconnectedFailure(phase is AttemptPhase.Activating),
                )
                AttemptPhase.Pending,
                is AttemptPhase.Retiring,
                is AttemptPhase.Failed,
                -> Unit
            }
        }
    }

    fun clear(connection: VoiceAgentTelecomCall) {
        var publication: OutcomePublication? = null
        synchronized(lock) {
            val (id, record) = attemptForConnectionLocked(connection) ?: return
            val failure = when (val phase = record.phase) {
                is AttemptPhase.Activating -> disconnectedFailure(duringActivation = true)
                is AttemptPhase.Active -> disconnectedFailure(duringActivation = false)
                is AttemptPhase.Retiring -> phase.failure
                AttemptPhase.Pending,
                is AttemptPhase.Failed,
                -> return
            }
            publication = terminalizeLocked(id, record, failure)
        }
        publication?.publish()
    }

    private fun finishRetiring(
        id: VoiceAgentTelecomAttemptId,
        record: AttemptRecord,
        connection: VoiceAgentTelecomCall,
    ) {
        var publication: OutcomePublication? = null
        synchronized(lock) {
            val phase = record.phase
            if (phase is AttemptPhase.Retiring && phase.connection === connection) {
                publication = terminalizeLocked(id, record, phase.failure)
            }
        }
        publication?.publish()
    }

    private fun terminalizeLocked(
        id: VoiceAgentTelecomAttemptId,
        record: AttemptRecord,
        failure: VoiceAgentTelecomFailure,
    ): OutcomePublication? {
        record.phase = AttemptPhase.Failed(failure)
        val publication = selectOutcomeLocked(
            record,
            VoiceAgentTelecomOutcome.Failed(failure),
        )
        if (currentAttemptId == id) currentAttemptId = null
        if (record.outcomeAcknowledged && attempts[id] === record) {
            attempts.remove(id)
        }
        return publication
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

    private fun disconnectedFailure(duringActivation: Boolean) = VoiceAgentTelecomFailure(
        diagnosticName = "telecom_connection_disconnected",
        detail = if (duringActivation) {
            "Telecom connection disconnected during activation"
        } else {
            "Telecom connection disconnected"
        },
    )

    private fun selectOutcomeLocked(
        record: AttemptRecord,
        outcome: VoiceAgentTelecomOutcome,
    ): OutcomePublication? {
        if (record.selectedOutcome != null) return null
        record.selectedOutcome = outcome
        return OutcomePublication(record.completion, outcome)
    }

    private data class OutcomePublication(
        val completion: CompletableDeferred<VoiceAgentTelecomOutcome>,
        val outcome: VoiceAgentTelecomOutcome,
    ) {
        fun publish() {
            completion.complete(outcome)
        }
    }

    private class AttemptRecord {
        val completion = CompletableDeferred<VoiceAgentTelecomOutcome>()
        var phase: AttemptPhase = AttemptPhase.Pending
        var selectedOutcome: VoiceAgentTelecomOutcome? = null
        var outcomeAcknowledged = false
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
