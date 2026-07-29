package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.flow.StateFlow

internal interface RouteOwnedManagedVoiceCallSession {
    val state: StateFlow<VoiceAgentUiState>
    val routeMetadata: VoiceAgentRouteMetadata
    val isRouteUsable: Boolean
    val cleanupOperation: VoiceAgentCleanupOperation
    fun start()
    fun interrupt()
    fun setMuted(value: Boolean)
    fun reconnect()
    fun recordDiagnostic(name: String, detail: String)
}

internal class RouteOwnedVoiceCallSession(
    private val delegate: ManagedVoiceCallSession,
    private val routeLease: VoiceAgentRouteLease,
    endDrainTimeoutMillis: Long = VOICE_AGENT_END_DRAIN_TIMEOUT_MS,
) : RouteOwnedManagedVoiceCallSession {
    override val state = delegate.state
    override val routeMetadata = routeLease.metadata
    override val cleanupOperation = voiceAgentSessionCleanupOperation(
        delegate = delegate,
        routeLease = routeLease,
        endDrainTimeoutMillis = endDrainTimeoutMillis,
    )
    override val isRouteUsable: Boolean
        get() = routeLease.isUsable

    override fun start() = delegate.start()

    override fun interrupt() = delegate.interrupt()

    override fun setMuted(value: Boolean) = delegate.setMuted(value)

    override fun reconnect() = delegate.reconnect()

    override fun recordDiagnostic(name: String, detail: String) = delegate.recordDiagnostic(name, detail)
}
