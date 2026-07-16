package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

sealed interface VoiceAgentCallStartupResult {
    val route: VoiceAgentRouteMetadata

    data class Started(
        override val route: VoiceAgentRouteMetadata,
        val startedNewSession: Boolean,
    ) : VoiceAgentCallStartupResult

    data class Stale(
        override val route: VoiceAgentRouteMetadata,
    ) : VoiceAgentCallStartupResult
}

class VoiceAgentCallStartup internal constructor(
    private val manager: VoiceAgentCallManager,
    private val resolveRoute: suspend () -> VoiceAgentRouteLease,
) {
    constructor(
        manager: VoiceAgentCallManager,
        routeResolver: VoiceAgentAudioRouteResolver,
    ) : this(manager, routeResolver::resolve)

    suspend fun start(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
        isCurrent: () -> Boolean,
    ): VoiceAgentCallStartupResult {
        manager.matchingRouteMetadata(conversationId, config)?.let { existing ->
            return VoiceAgentCallStartupResult.Started(existing, startedNewSession = false)
        }

        val routeLease = resolveRoute()
        val route = routeLease.metadata
        if (!isCurrent()) {
            routeLease.retire()
            return VoiceAgentCallStartupResult.Stale(route)
        }

        val startedNewSession = manager.start(
            conversationId = conversationId,
            config = config,
            routeLease = routeLease,
            scope = scope,
        )
        val installedRoute = if (startedNewSession) {
            route
        } else {
            manager.matchingRouteMetadata(conversationId, config) ?: route
        }
        return VoiceAgentCallStartupResult.Started(installedRoute, startedNewSession)
    }
}
