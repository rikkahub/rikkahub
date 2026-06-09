package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class VoiceAgentCallManager(
    private val factory: VoiceAgentCallFactory,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(VoiceAgentUiState())
    private val _activeConversationId = MutableStateFlow<Uuid?>(null)
    private var activeSession: ManagedVoiceCallSession? = null
    private var activeLaunchConfig: VoiceAgentLaunchConfig? = null
    private var stateCollectionJob: Job? = null
    private var callStatus: VoiceCallStatus = VoiceCallStatus.Idle

    val activeConversationId: StateFlow<Uuid?> = _activeConversationId.asStateFlow()
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    fun start(conversationId: Uuid, config: VoiceAgentLaunchConfig, scope: CoroutineScope): Boolean {
        synchronized(lock) {
            if (
                activeSession != null &&
                _activeConversationId.value == conversationId &&
                activeLaunchConfig == config
            ) {
                return false
            }
        }

        val previousSession = synchronized(lock) {
            clearActiveSessionLocked()
        }
        previousSession?.end()

        val session = factory.create(conversationId = conversationId, config = config, scope = scope)
        synchronized(lock) {
            activeSession = session
            activeLaunchConfig = config
            _activeConversationId.value = conversationId
            _state.value = session.state.value.copy(call = callStatus)
            stateCollectionJob = scope.launch {
                session.state.collect { sessionState ->
                    val status = synchronized(lock) { callStatus }
                    _state.value = sessionState.copy(call = status)
                }
            }
        }
        session.start()
        return true
    }

    fun interrupt() = synchronized(lock) { activeSession }?.interrupt()

    fun setMuted(value: Boolean) = synchronized(lock) { activeSession }?.setMuted(value)

    fun reconnect() = synchronized(lock) { activeSession }?.reconnect()

    fun updateCallStatus(status: VoiceCallStatus) {
        synchronized(lock) {
            callStatus = status
            _state.value = _state.value.copy(call = status)
        }
    }

    fun recordDiagnostic(name: String, detail: String) =
        synchronized(lock) { activeSession }?.recordDiagnostic(name = name, detail = detail)

    fun end() {
        val session = synchronized(lock) {
            callStatus = VoiceCallStatus.Ending
            _state.value = _state.value.copy(call = VoiceCallStatus.Ending)
            clearActiveSessionLocked()
        }
        session?.end()
    }

    fun detachForEndAndDrain(): ManagedVoiceCallSession? {
        return synchronized(lock) {
            callStatus = VoiceCallStatus.Ending
            _state.value = _state.value.copy(call = VoiceCallStatus.Ending)
            clearActiveSessionLocked()
        }
    }

    fun closeNow() {
        val session = synchronized(lock) {
            callStatus = VoiceCallStatus.Ended
            _state.value = VoiceAgentUiState(call = VoiceCallStatus.Ended)
            clearActiveSessionLocked()
        }
        session?.closeNow()
    }

    private fun clearActiveSessionLocked(): ManagedVoiceCallSession? {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        return activeSession.also {
            activeSession = null
            activeLaunchConfig = null
            _activeConversationId.value = null
        }
    }
}
