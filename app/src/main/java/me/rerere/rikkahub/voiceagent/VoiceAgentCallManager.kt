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
    private data class ActiveVoiceCall(
        val conversationId: Uuid,
        val launchConfig: VoiceAgentLaunchConfig,
        val route: VoiceAgentRouteMetadata,
        val session: RouteOwnedManagedVoiceCallSession,
    )

    private val lock = Any()
    private val _state = MutableStateFlow(VoiceAgentUiState())
    private val _activeConversationId = MutableStateFlow<Uuid?>(null)
    private var activeCall: ActiveVoiceCall? = null
    private var stateCollectionJob: Job? = null
    private var callStatus: VoiceCallStatus = VoiceCallStatus.Idle

    val activeConversationId: StateFlow<Uuid?> = _activeConversationId.asStateFlow()
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    fun start(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): Boolean = synchronized(lock) {
        if (activeCall?.let { it.conversationId == conversationId && it.launchConfig == config } == true) {
            routeLease.retire()
            return false
        }

        val previousSession = clearActiveSessionLocked()
        try {
            previousSession?.end()
        } catch (previousEndError: Throwable) {
            runCatching(routeLease::retire)
                .exceptionOrNull()
                ?.let(previousEndError::addSuppressed)
            throw previousEndError
        }

        val session = factory.create(
            conversationId = conversationId,
            config = config,
            routeLease = routeLease,
            scope = scope,
        )
        val call = ActiveVoiceCall(
            conversationId = conversationId,
            launchConfig = config,
            route = session.routeMetadata,
            session = session,
        )
        activeCall = call
        _activeConversationId.value = conversationId
        _state.value = session.state.value.copy(call = callStatus)
        stateCollectionJob = scope.launch {
            session.state.collect { sessionState ->
                val status = synchronized(lock) { callStatus }
                _state.value = sessionState.copy(call = status)
            }
        }
        session.start()
        true
    }

    fun matchingRouteMetadata(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
    ): VoiceAgentRouteMetadata? = synchronized(lock) {
        activeCall?.takeIf {
            it.conversationId == conversationId && it.launchConfig == config
        }?.route
    }

    fun canPreserveActiveSession(conversationId: Uuid): Boolean = synchronized(lock) {
        activeCall?.takeIf { it.conversationId == conversationId }
            ?.session
            ?.isRouteUsable == true
    }

    fun interrupt() = synchronized(lock) { activeCall?.session }?.interrupt()
    fun setMuted(value: Boolean) = synchronized(lock) { activeCall?.session }?.setMuted(value)
    fun reconnect() = synchronized(lock) { activeCall?.session }?.reconnect()

    fun updateCallStatus(status: VoiceCallStatus) {
        synchronized(lock) {
            callStatus = status
            _state.value = _state.value.copy(call = status)
        }
    }

    fun recordDiagnostic(name: String, detail: String) =
        synchronized(lock) { activeCall?.session }?.recordDiagnostic(name = name, detail = detail)

    fun end() {
        val session = synchronized(lock) {
            callStatus = VoiceCallStatus.Ending
            _state.value = _state.value.copy(call = VoiceCallStatus.Ending)
            clearActiveSessionLocked()
        }
        session?.end()
    }

    fun detachForEndAndDrain(): ManagedVoiceCallSession? = synchronized(lock) {
        callStatus = VoiceCallStatus.Ending
        _state.value = _state.value.copy(call = VoiceCallStatus.Ending)
        clearActiveSessionLocked()
    }

    fun closeNow() {
        val session = synchronized(lock) {
            callStatus = VoiceCallStatus.Ended
            _state.value = VoiceAgentUiState(call = VoiceCallStatus.Ended)
            clearActiveSessionLocked()
        }
        session?.closeNow()
    }

    private fun clearActiveSessionLocked(): RouteOwnedManagedVoiceCallSession? {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        return activeCall?.session.also {
            activeCall = null
            _activeConversationId.value = null
        }
    }
}
