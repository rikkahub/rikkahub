package me.rerere.rikkahub.voiceagent

interface RouteOwnedManagedVoiceCallSession : ManagedVoiceCallSession {
    val routeMetadata: VoiceAgentRouteMetadata
    val isRouteUsable: Boolean
}

class RouteOwnedVoiceCallSession(
    private val delegate: ManagedVoiceCallSession,
    private val routeLease: VoiceAgentRouteLease,
) : RouteOwnedManagedVoiceCallSession {
    override val state = delegate.state
    override val routeMetadata = routeLease.metadata
    override val isRouteUsable: Boolean
        get() = routeLease.isUsable

    override fun start() = delegate.start()

    override fun interrupt() = delegate.interrupt()

    override fun setMuted(value: Boolean) = delegate.setMuted(value)

    override fun reconnect() = delegate.reconnect()

    override fun recordDiagnostic(name: String, detail: String) = delegate.recordDiagnostic(name, detail)

    override fun end() = runVoiceAgentCleanupStages(routeLease::retire, delegate::end)

    override suspend fun endAndDrain() = runVoiceAgentSuspendCleanupStages(
        { routeLease.retire() },
        delegate::endAndDrain,
    )

    override fun closeNow() = runVoiceAgentCleanupStages(routeLease::retire, delegate::closeNow)
}
