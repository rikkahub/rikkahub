package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

internal sealed interface VoiceAgentManagerStartResult {
    data class Started(val route: VoiceAgentRouteMetadata) : VoiceAgentManagerStartResult
    data class Existing(val route: VoiceAgentRouteMetadata) : VoiceAgentManagerStartResult
    data object Superseded : VoiceAgentManagerStartResult
}

internal enum class VoiceAgentStartupResolution {
    Published,
    Failed,
    Superseded,
}

internal sealed interface VoiceAgentRouteMatchResult {
    data object NoMatch : VoiceAgentRouteMatchResult
    data class Existing(val route: VoiceAgentRouteMetadata) : VoiceAgentRouteMatchResult
    data class Superseded(val route: VoiceAgentRouteMetadata) : VoiceAgentRouteMatchResult
}

class VoiceAgentCallManager(
    private val factory: VoiceAgentCallFactory,
) {
    private class PendingPublication(
        val resolution: CompletableDeferred<VoiceAgentStartupResolution>,
        var selected: VoiceAgentStartupResolution? = null,
    )

    private sealed interface CallSlot {
        data object Idle : CallSlot

        data class CleanupFence(
            val predecessorCleanup: CompletableDeferred<Result<Unit>>,
        ) : CallSlot

        data class CleanupClaim(
            val completion: CompletableDeferred<Unit>,
        ) : CallSlot

        data class Starting(
            val token: Any,
            val conversationId: Uuid,
            val launchConfig: VoiceAgentLaunchConfig,
            val route: VoiceAgentRouteMetadata,
            val resolution: CompletableDeferred<VoiceAgentStartupResolution>,
            val publication: PendingPublication,
            val predecessorCleanup: CompletableDeferred<Result<Unit>>?,
        ) : CallSlot

        data class Active(
            val token: Any,
            val conversationId: Uuid,
            val launchConfig: VoiceAgentLaunchConfig,
            val route: VoiceAgentRouteMetadata,
            val session: RouteOwnedManagedVoiceCallSession,
            val stateCollectionJob: Job?,
            val pendingPublication: PendingPublication?,
        ) : CallSlot
    }

    private sealed interface StartDecision {
        data class Own(
            val reservation: CallSlot.Starting,
            val predecessor: CallSlot.Active?,
            val displacedPublication: PendingPublication?,
        ) : StartDecision

        data class Await(
            val route: VoiceAgentRouteMetadata,
            val resolution: CompletableDeferred<VoiceAgentStartupResolution>,
        ) : StartDecision
        data class AwaitCleanup(val completion: CompletableDeferred<Unit>) : StartDecision
        data class Reuse(val route: VoiceAgentRouteMetadata) : StartDecision
        data object RetrySuperseded : StartDecision
    }

    private val lock = Any()
    private val _state = MutableStateFlow(VoiceAgentUiState())
    private val _activeConversationId = MutableStateFlow<Uuid?>(null)
    private var slot: CallSlot = CallSlot.Idle
    private var callStatus: VoiceCallStatus = VoiceCallStatus.Idle

    val activeConversationId: StateFlow<Uuid?> = _activeConversationId.asStateFlow()
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    internal suspend fun start(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): VoiceAgentManagerStartResult {
        var retryingFailedMatch = false
        while (true) {
            val decision = synchronized(lock) {
                decideStartLocked(
                    conversationId = conversationId,
                    config = config,
                    route = routeLease.metadata,
                    retryingFailedMatch = retryingFailedMatch,
                )
            }
            when (decision) {
                is StartDecision.Reuse -> {
                    routeLease.retire()
                    return VoiceAgentManagerStartResult.Existing(decision.route)
                }

                is StartDecision.RetrySuperseded -> {
                    routeLease.retire()
                    return VoiceAgentManagerStartResult.Superseded
                }

                is StartDecision.Await -> {
                    val resolution = try {
                        decision.resolution.await()
                    } catch (cancellation: CancellationException) {
                        runCatching(routeLease::retire)
                            .exceptionOrNull()
                            ?.takeIf { it !== cancellation }
                            ?.let(cancellation::addSuppressed)
                        throw cancellation
                    }
                    when (resolution) {
                        VoiceAgentStartupResolution.Published -> {
                            routeLease.retire()
                            return VoiceAgentManagerStartResult.Existing(decision.route)
                        }

                        VoiceAgentStartupResolution.Superseded -> {
                            routeLease.retire()
                            return VoiceAgentManagerStartResult.Superseded
                        }

                        VoiceAgentStartupResolution.Failed -> retryingFailedMatch = true
                    }
                }

                is StartDecision.AwaitCleanup -> {
                    try {
                        decision.completion.await()
                    } catch (cancellation: CancellationException) {
                        runCatching(routeLease::retire)
                            .exceptionOrNull()
                            ?.takeIf { it !== cancellation }
                            ?.let(cancellation::addSuppressed)
                        throw cancellation
                    }
                }

                is StartDecision.Own -> {
                    decision.displacedPublication?.resolution?.complete(VoiceAgentStartupResolution.Superseded)
                    return runReservationOwner(
                        reservation = decision.reservation,
                        routeLease = routeLease,
                        scope = scope,
                        predecessor = decision.predecessor,
                    )
                }
            }
        }
    }

    internal suspend fun matchingRoute(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
    ): VoiceAgentRouteMatchResult {
        var awaitedRoute: VoiceAgentRouteMetadata? = null
        while (true) {
            when (val current = synchronized(lock) { slot }) {
                CallSlot.Idle -> return VoiceAgentRouteMatchResult.NoMatch
                is CallSlot.CleanupFence -> return VoiceAgentRouteMatchResult.NoMatch
                is CallSlot.CleanupClaim -> current.completion.await()
                is CallSlot.Active -> {
                    if (current.conversationId != conversationId || current.launchConfig != config) {
                        return awaitedRoute?.let(VoiceAgentRouteMatchResult::Superseded)
                            ?: VoiceAgentRouteMatchResult.NoMatch
                    }
                    val pendingPublication = current.pendingPublication?.resolution
                    if (pendingPublication == null) {
                        return VoiceAgentRouteMatchResult.Existing(current.route)
                    }
                    awaitedRoute = current.route
                    when (pendingPublication.await()) {
                        VoiceAgentStartupResolution.Published -> {
                            return VoiceAgentRouteMatchResult.Existing(current.route)
                        }
                        VoiceAgentStartupResolution.Superseded -> {
                            return VoiceAgentRouteMatchResult.Superseded(current.route)
                        }
                        VoiceAgentStartupResolution.Failed -> Unit
                    }
                }
                is CallSlot.Starting -> {
                    if (current.conversationId != conversationId || current.launchConfig != config) {
                        return awaitedRoute?.let(VoiceAgentRouteMatchResult::Superseded)
                            ?: VoiceAgentRouteMatchResult.NoMatch
                    }
                    awaitedRoute = current.route
                    when (current.resolution.await()) {
                        VoiceAgentStartupResolution.Published -> {
                            return VoiceAgentRouteMatchResult.Existing(current.route)
                        }

                        VoiceAgentStartupResolution.Superseded -> {
                            return VoiceAgentRouteMatchResult.Superseded(current.route)
                        }

                        VoiceAgentStartupResolution.Failed -> Unit
                    }
                }
            }
        }
    }

    fun canPreserveActiveSession(conversationId: Uuid): Boolean = synchronized(lock) {
        (slot as? CallSlot.Active)
            ?.takeIf { it.conversationId == conversationId }
            ?.session
            ?.isRouteUsable == true
    }

    fun interrupt() = activeSessionSnapshot()?.interrupt()
    fun setMuted(value: Boolean) = activeSessionSnapshot()?.setMuted(value)
    fun reconnect() = activeSessionSnapshot()?.reconnect()

    fun updateCallStatus(status: VoiceCallStatus) {
        synchronized(lock) {
            callStatus = status
            _state.value = _state.value.copy(call = status)
        }
    }

    fun recordDiagnostic(name: String, detail: String) =
        activeSessionSnapshot()?.recordDiagnostic(name = name, detail = detail)

    fun end() {
        val detached = synchronized(lock) {
            callStatus = VoiceCallStatus.Ending
            _state.value = _state.value.copy(call = VoiceCallStatus.Ending)
            detachTerminalLocked()
        }
        when (detached) {
            null, CallSlot.Idle, is CallSlot.CleanupFence, is CallSlot.CleanupClaim -> Unit
            is CallSlot.Starting -> {
                detached.publication.resolution.complete(VoiceAgentStartupResolution.Superseded)
            }
            is CallSlot.Active -> {
                detached.pendingPublication?.resolution?.complete(VoiceAgentStartupResolution.Superseded)
                detached.stateCollectionJob?.cancel()
                detached.session.end()
            }
        }
    }

    fun detachForEndAndDrain(): RouteOwnedManagedVoiceCallSession? {
        val detached = synchronized(lock) {
            callStatus = VoiceCallStatus.Ending
            _state.value = _state.value.copy(call = VoiceCallStatus.Ending)
            detachTerminalLocked()
        }
        return when (detached) {
            null, CallSlot.Idle, is CallSlot.CleanupFence, is CallSlot.CleanupClaim -> null
            is CallSlot.Starting -> {
                detached.publication.resolution.complete(VoiceAgentStartupResolution.Superseded)
                null
            }
            is CallSlot.Active -> {
                detached.pendingPublication?.resolution?.complete(VoiceAgentStartupResolution.Superseded)
                detached.stateCollectionJob?.cancel()
                detached.session
            }
        }
    }

    fun closeNow() {
        val detached = synchronized(lock) {
            callStatus = VoiceCallStatus.Ended
            _state.value = VoiceAgentUiState(call = VoiceCallStatus.Ended)
            detachTerminalLocked()
        }
        when (detached) {
            null, CallSlot.Idle, is CallSlot.CleanupFence, is CallSlot.CleanupClaim -> Unit
            is CallSlot.Starting -> {
                detached.publication.resolution.complete(VoiceAgentStartupResolution.Superseded)
            }
            is CallSlot.Active -> {
                detached.pendingPublication?.resolution?.complete(VoiceAgentStartupResolution.Superseded)
                detached.stateCollectionJob?.cancel()
                detached.session.closeNow()
            }
        }
    }

    private fun decideStartLocked(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        route: VoiceAgentRouteMetadata,
        retryingFailedMatch: Boolean,
    ): StartDecision = when (val current = slot) {
        CallSlot.Idle -> StartDecision.Own(
            reservation = installReservationLocked(conversationId, config, route, predecessorCleanup = null),
            predecessor = null,
            displacedPublication = null,
        )

        is CallSlot.CleanupFence -> StartDecision.Own(
            reservation = installReservationLocked(
                conversationId,
                config,
                route,
                current.predecessorCleanup,
            ),
            predecessor = null,
            displacedPublication = null,
        )

        is CallSlot.CleanupClaim -> StartDecision.AwaitCleanup(current.completion)

        is CallSlot.Active -> if (current.conversationId == conversationId && current.launchConfig == config) {
            current.pendingPublication?.let {
                StartDecision.Await(current.route, it.resolution)
            } ?: StartDecision.Reuse(current.route)
        } else if (retryingFailedMatch) {
            StartDecision.RetrySuperseded
        } else {
            val cleanup = CompletableDeferred<Result<Unit>>()
            val displacedPublication = selectPublicationLocked(
                current.pendingPublication,
                VoiceAgentStartupResolution.Superseded,
            )
            StartDecision.Own(
                reservation = installReservationLocked(conversationId, config, route, cleanup),
                predecessor = current,
                displacedPublication = displacedPublication,
            )
        }

        is CallSlot.Starting -> if (current.conversationId == conversationId && current.launchConfig == config) {
            StartDecision.Await(current.route, current.resolution)
        } else if (retryingFailedMatch) {
            StartDecision.RetrySuperseded
        } else {
            val displacedPublication = selectPublicationLocked(
                current.publication,
                VoiceAgentStartupResolution.Superseded,
            )
            StartDecision.Own(
                reservation = installReservationLocked(
                    conversationId,
                    config,
                    route,
                    current.predecessorCleanup,
                ),
                predecessor = null,
                displacedPublication = displacedPublication,
            )
        }
    }

    private fun installReservationLocked(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        route: VoiceAgentRouteMetadata,
        predecessorCleanup: CompletableDeferred<Result<Unit>>?,
    ): CallSlot.Starting {
        val resolution = CompletableDeferred<VoiceAgentStartupResolution>()
        return CallSlot.Starting(
            token = Any(),
            conversationId = conversationId,
            launchConfig = config,
            route = route,
            resolution = resolution,
            publication = PendingPublication(resolution),
            predecessorCleanup = predecessorCleanup,
        ).also {
            slot = it
            _activeConversationId.value = null
        }
    }

    private suspend fun runReservationOwner(
        reservation: CallSlot.Starting,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
        predecessor: CallSlot.Active?,
    ): VoiceAgentManagerStartResult {
        var factoryOwnsLease = false
        var routeLeaseCleanupAttempted = false
        var createdSession: RouteOwnedManagedVoiceCallSession? = null
        var createdSessionCleanupAttempted = false
        var activeInstalled = false
        try {
            if (predecessor != null) {
                val cleanupResult = runCatching {
                    predecessor.stateCollectionJob?.cancel()
                    predecessor.session.end()
                }
                checkNotNull(reservation.predecessorCleanup).complete(cleanupResult)
            }
            reservation.predecessorCleanup?.await()?.getOrThrow()
            currentCoroutineContext().ensureActive()
            if (!owns(reservation)) {
                routeLeaseCleanupAttempted = true
                routeLease.retire()
                return VoiceAgentManagerStartResult.Superseded
            }

            factoryOwnsLease = true
            val session = factory.create(
                conversationId = reservation.conversationId,
                config = reservation.launchConfig,
                routeLease = routeLease,
                scope = scope,
            )
            createdSession = session
            currentCoroutineContext().ensureActive()
            if (!owns(reservation)) {
                createdSessionCleanupAttempted = true
                session.closeNow()
                return VoiceAgentManagerStartResult.Superseded
            }

            session.start()
            currentCoroutineContext().ensureActive()
            if (!owns(reservation)) {
                createdSessionCleanupAttempted = true
                session.closeNow()
                return VoiceAgentManagerStartResult.Superseded
            }

            val active = CallSlot.Active(
                token = reservation.token,
                conversationId = reservation.conversationId,
                launchConfig = reservation.launchConfig,
                route = session.routeMetadata,
                session = session,
                stateCollectionJob = null,
                pendingPublication = reservation.publication,
            )
            val published = synchronized(lock) {
                if (slot === reservation) {
                    slot = active
                    _activeConversationId.value = reservation.conversationId
                    _state.value = session.state.value.copy(call = callStatus)
                    true
                } else {
                    false
                }
            }
            if (!published) {
                createdSessionCleanupAttempted = true
                session.closeNow()
                return VoiceAgentManagerStartResult.Superseded
            }
            activeInstalled = true

            val collector = scope.launch {
                session.state.collect { sessionState ->
                    synchronized(lock) {
                        val current = slot
                        if (current is CallSlot.Active && current.token === reservation.token) {
                            _state.value = sessionState.copy(call = callStatus)
                        }
                    }
                }
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancellation: CancellationException) {
                collector.cancel()
                throw cancellation
            }
            val attached = synchronized(lock) {
                val current = slot
                if (current === active) {
                    slot = active.copy(stateCollectionJob = collector)
                    true
                } else {
                    false
                }
            }
            if (!attached) {
                collector.cancel()
                return VoiceAgentManagerStartResult.Superseded
            }
            val publicationWon = synchronized(lock) {
                val current = slot
                if (current is CallSlot.Active &&
                    current.token === reservation.token &&
                    selectPublicationLocked(
                        reservation.publication,
                        VoiceAgentStartupResolution.Published,
                    ) != null
                ) {
                    slot = current.copy(pendingPublication = null)
                    true
                } else {
                    false
                }
            }
            if (!publicationWon) return VoiceAgentManagerStartResult.Superseded
            reservation.publication.resolution.complete(VoiceAgentStartupResolution.Published)
            return VoiceAgentManagerStartResult.Started(session.routeMetadata)
        } catch (failure: Throwable) {
            var cleanupClaim: CallSlot.CleanupClaim? = null
            val predecessorCleanupToPreserve = synchronized(lock) {
                val current = slot
                val ownsCurrentSlot = current === reservation ||
                    current is CallSlot.Active && current.token === reservation.token
                if (current is CallSlot.Active &&
                    current.token === reservation.token &&
                    current.pendingPublication === reservation.publication
                ) {
                    selectPublicationLocked(
                        reservation.publication,
                        VoiceAgentStartupResolution.Failed,
                    )
                    cleanupClaim = CallSlot.CleanupClaim(CompletableDeferred()).also { claim ->
                        slot = claim
                        _activeConversationId.value = null
                        _state.value = VoiceAgentUiState(call = callStatus)
                    }
                    null
                } else {
                    reservation.predecessorCleanup?.takeIf {
                        ownsCurrentSlot && !it.isCompleted
                    }
                }
            }
            val cleanupFailure = when {
                factoryOwnsLease &&
                    createdSession != null &&
                    !createdSessionCleanupAttempted &&
                    (!activeInstalled || cleanupClaim != null) -> {
                    createdSessionCleanupAttempted = true
                    runCatching(createdSession::closeNow).exceptionOrNull()
                }
                !factoryOwnsLease && !routeLeaseCleanupAttempted -> {
                    routeLeaseCleanupAttempted = true
                    runCatching(routeLease::retire).exceptionOrNull()
                }
                else -> null
            }
            cleanupFailure?.takeIf { it !== failure }?.let(failure::addSuppressed)
            val claimed = cleanupClaim
            if (claimed != null) {
                val wasOwner = synchronized(lock) {
                    if (slot === claimed) {
                        slot = CallSlot.Idle
                        true
                    } else {
                        false
                    }
                }
                if (wasOwner) reservation.resolution.complete(VoiceAgentStartupResolution.Failed)
                claimed.completion.complete(Unit)
                throw failure
            }
            val wasOwner = synchronized(lock) {
                val current = slot
                if (current === reservation ||
                    current is CallSlot.Active && current.token === reservation.token
                ) {
                    selectPublicationLocked(
                        reservation.publication,
                        VoiceAgentStartupResolution.Failed,
                    )
                    slot = predecessorCleanupToPreserve
                        ?.let { CallSlot.CleanupFence(it) }
                        ?: CallSlot.Idle
                    _activeConversationId.value = null
                    _state.value = VoiceAgentUiState()
                    true
                } else {
                    false
                }
            }
            if (wasOwner) reservation.resolution.complete(VoiceAgentStartupResolution.Failed)
            throw failure
        }
    }

    private fun owns(reservation: CallSlot.Starting): Boolean = synchronized(lock) {
        slot === reservation
    }

    private fun activeSessionSnapshot(): RouteOwnedManagedVoiceCallSession? = synchronized(lock) {
        (slot as? CallSlot.Active)?.session
    }

    private fun selectPublicationLocked(
        publication: PendingPublication?,
        resolution: VoiceAgentStartupResolution,
    ): PendingPublication? = publication?.takeIf { pending ->
        pending.selected == null
    }?.also { pending ->
        pending.selected = resolution
    }

    private fun detachTerminalLocked(): CallSlot? = when (val current = slot) {
        CallSlot.Idle -> null
        is CallSlot.CleanupFence -> null
        is CallSlot.CleanupClaim -> null
        is CallSlot.Starting,
        is CallSlot.Active,
        -> current.also {
            selectPublicationLocked(
                when (current) {
                    is CallSlot.Starting -> current.publication
                    is CallSlot.Active -> current.pendingPublication
                },
                VoiceAgentStartupResolution.Superseded,
            )
            slot = if (current is CallSlot.Starting) {
                current.predecessorCleanup?.let { CallSlot.CleanupFence(it) } ?: CallSlot.Idle
            } else {
                CallSlot.Idle
            }
            _activeConversationId.value = null
        }
    }
}
