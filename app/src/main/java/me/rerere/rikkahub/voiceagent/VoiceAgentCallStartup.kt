package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import kotlin.uuid.Uuid

sealed interface VoiceAgentCallStartupResult {
    data class Started(
        val resolution: VoiceAgentAudioRouteResolution,
        val startedNewSession: Boolean,
    ) : VoiceAgentCallStartupResult

    data class Stale(
        val resolution: VoiceAgentAudioRouteResolution,
    ) : VoiceAgentCallStartupResult
}

class VoiceAgentCallStartup internal constructor(
    private val manager: VoiceAgentCallManager,
    private val telecomRegistry: VoiceAgentTelecomCallRegistry,
    private val resolveRoute: suspend () -> VoiceAgentAudioRouteResolution,
) {
    constructor(
        manager: VoiceAgentCallManager,
        routeResolver: VoiceAgentAudioRouteResolver,
        telecomRegistry: VoiceAgentTelecomCallRegistry,
    ) : this(manager, telecomRegistry, routeResolver::resolve)

    suspend fun start(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
        isCurrent: () -> Boolean,
    ): VoiceAgentCallStartupResult {
        val resolution = manager.routeOwnerForActiveSession(conversationId, config)
            ?.let(::VoiceAgentAudioRouteResolution)
            ?: resolveRoute()
        if (!isCurrent()) {
            if (
                resolution.owner == VoiceAudioRouteOwner.Telecom &&
                manager.routeOwnerForActiveSession(conversationId, config) == null
            ) {
                telecomRegistry.disconnectActive()
            }
            return VoiceAgentCallStartupResult.Stale(resolution)
        }
        return VoiceAgentCallStartupResult.Started(
            resolution = resolution,
            startedNewSession = manager.start(
                conversationId = conversationId,
                config = config,
                routeOwner = resolution.owner,
                scope = scope,
            ),
        )
    }
}
