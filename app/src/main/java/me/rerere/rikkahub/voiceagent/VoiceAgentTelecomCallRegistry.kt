package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
    private val state = MutableStateFlow<AttemptState>(AttemptState.Idle)
    private var nextAttemptId = 0L

    fun beginAttempt(): VoiceAgentTelecomAttemptId {
        val (id, previous) = synchronized(lock) {
            val id = VoiceAgentTelecomAttemptId(++nextAttemptId)
            val previous = (state.value as? AttemptState.Active)?.call
            state.value = AttemptState.Pending(id)
            id to previous
        }
        previous?.disconnectFromApp()
        return id
    }

    fun activate(id: VoiceAgentTelecomAttemptId, connection: VoiceAgentTelecomCall): Boolean {
        val accepted = synchronized(lock) {
            val current = state.value
            if (current is AttemptState.Pending && current.id == id) {
                state.value = AttemptState.Active(id, connection)
                true
            } else {
                false
            }
        }
        if (!accepted) {
            connection.disconnectFromApp()
        }
        return accepted
    }

    fun fail(id: VoiceAgentTelecomAttemptId, failure: VoiceAgentTelecomFailure) {
        synchronized(lock) {
            val current = state.value
            if (current is AttemptState.Pending && current.id == id) {
                state.value = AttemptState.Failed(id, failure)
            }
        }
    }

    suspend fun awaitOutcome(id: VoiceAgentTelecomAttemptId): VoiceAgentTelecomOutcome =
        state.filter { candidate ->
            candidate.id == id &&
                (candidate is AttemptState.Active || candidate is AttemptState.Failed)
        }.map { candidate ->
            when (candidate) {
                is AttemptState.Active -> VoiceAgentTelecomOutcome.Active
                is AttemptState.Failed -> VoiceAgentTelecomOutcome.Failed(candidate.failure)
                else -> error("Filtered non-terminal Telecom state")
            }
        }.first()

    fun clear(connection: VoiceAgentTelecomCall) {
        synchronized(lock) {
            val current = state.value
            if (current is AttemptState.Active && current.call === connection) {
                state.value = AttemptState.Idle
            }
        }
    }

    fun hasActiveConnection(): Boolean = synchronized(lock) {
        state.value is AttemptState.Active
    }

    fun disconnectActive() {
        val previous = synchronized(lock) {
            (state.value as? AttemptState.Active)?.call.also {
                state.value = AttemptState.Idle
            }
        }
        previous?.disconnectFromApp()
    }

    private sealed interface AttemptState {
        val id: VoiceAgentTelecomAttemptId?

        data object Idle : AttemptState {
            override val id: VoiceAgentTelecomAttemptId? = null
        }

        data class Pending(override val id: VoiceAgentTelecomAttemptId) : AttemptState

        data class Active(
            override val id: VoiceAgentTelecomAttemptId,
            val call: VoiceAgentTelecomCall,
        ) : AttemptState

        data class Failed(
            override val id: VoiceAgentTelecomAttemptId,
            val failure: VoiceAgentTelecomFailure,
        ) : AttemptState
    }
}
