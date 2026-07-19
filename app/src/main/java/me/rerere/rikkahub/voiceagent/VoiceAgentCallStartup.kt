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
        when (val match = manager.matchingRoute(conversationId, config)) {
            is VoiceAgentRouteMatchResult.Existing -> {
                return VoiceAgentCallStartupResult.Started(match.route, startedNewSession = false)
            }
            is VoiceAgentRouteMatchResult.Superseded -> {
                return VoiceAgentCallStartupResult.Stale(match.route)
            }
            VoiceAgentRouteMatchResult.NoMatch -> Unit
        }

        val routeLease = resolveRoute()
        val route = routeLease.metadata
        if (!isCurrent()) {
            routeLease.retire()
            return VoiceAgentCallStartupResult.Stale(route)
        }

        return when (val result = manager.start(
            conversationId = conversationId,
            config = config,
            routeLease = routeLease,
            scope = scope,
        )) {
            is VoiceAgentManagerStartResult.Started -> {
                VoiceAgentCallStartupResult.Started(result.route, startedNewSession = true)
            }
            is VoiceAgentManagerStartResult.Existing -> {
                VoiceAgentCallStartupResult.Started(result.route, startedNewSession = false)
            }
            VoiceAgentManagerStartResult.Superseded -> VoiceAgentCallStartupResult.Stale(route)
        }
    }
}
