package me.rerere.rikkahub.voiceagent.audio

import java.util.concurrent.CountDownLatch
import java.util.IdentityHashMap
import java.util.WeakHashMap
import me.rerere.rikkahub.voiceagent.RetirementBarrier
import me.rerere.rikkahub.voiceagent.runVoiceAgentCleanupStages

internal class VoiceAudioCaptureToken internal constructor()

internal enum class VoiceAudioCaptureStartOutcome {
    Started,
    Rejected,
}

private sealed interface ActivePublicationDecision<out Recorder : Any, out CaptureTask : Any> {
    data object Published : ActivePublicationDecision<Nothing, Nothing>

    data class Retiring<Recorder : Any, CaptureTask : Any>(
        val retirement: CaptureState.Retiring<Recorder, CaptureTask>,
    ) : ActivePublicationDecision<Recorder, CaptureTask>

    data object Rejected : ActivePublicationDecision<Nothing, Nothing>
}

internal class VoiceAudioCaptureOwnership<Recorder : Any, CaptureTask : Any>(
    private val startRecorder: (Recorder) -> Unit,
    private val isRecorderRecording: (Recorder) -> Boolean,
    private val stopRecorder: (Recorder) -> Unit,
    private val releaseRecorder: (Recorder) -> Unit,
    private val startTask: (CaptureTask) -> Boolean,
    private val cancelTask: (CaptureTask) -> Unit,
    private val onRetirementResultPublished: (Result<Unit>) -> Unit = {},
    private val beforeActivePublication: () -> Unit = {},
) {
    private val lock = Any()
    private val retirementBarriers = WeakHashMap<VoiceAudioCaptureToken, RetirementBarrier>()
    private var state: CaptureState<Recorder, CaptureTask> = CaptureState.Idle

    fun reserve(): VoiceAudioCaptureToken {
        while (true) {
            val retirementToJoin = synchronized(lock) {
                when (val current = state) {
                    CaptureState.Idle -> {
                        val token = VoiceAudioCaptureToken()
                        val retirementBarrier = RetirementBarrier()
                        retirementBarriers[token] = retirementBarrier
                        state = CaptureState.Starting.Reserved(token, retirementBarrier)
                        return token
                    }

                    CaptureState.Released -> error("Voice audio engine is released")
                    is CaptureState.Retiring -> current
                    else -> error("Voice audio capture is already reserved")
                }
            }
            retireOwnedCapture(retirementToJoin)
        }
    }

    fun publishRoute(token: VoiceAudioCaptureToken, routeLease: VoiceAudioCaptureRouteLease): Boolean =
        synchronized(lock) {
            val current = state
            if (current is CaptureState.Starting.Reserved && current.token === token) {
                state = CaptureState.Starting.Routed(
                    token = token,
                    routeLease = routeLease,
                    retirementBarrier = current.retirementBarrier,
                )
                true
            } else {
                false
            }
        }

    fun publishAndStart(
        token: VoiceAudioCaptureToken,
        recorder: Recorder,
        task: CaptureTask,
    ): VoiceAudioCaptureStartOutcome {
        val activationBarrier = ActivationBarrier()
        val admitted = synchronized(lock) {
            val current = state
            if (current is CaptureState.Starting.Routed && current.token === token) {
                state = CaptureState.Starting.Activating(
                    token = token,
                    recorder = recorder,
                    task = task,
                    routeLease = current.routeLease,
                    activationBarrier = activationBarrier,
                    retirementBarrier = current.retirementBarrier,
                )
                true
            } else {
                false
            }
        }
        if (!admitted) {
            cleanupRejectedPublication(token, recorder, task)
            return VoiceAudioCaptureStartOutcome.Rejected
        }

        val activationFailure = try {
            startRecorder(recorder)
            if (isRecorderRecording(recorder)) null else IllegalStateException("AudioRecord start failed")
        } catch (failure: Throwable) {
            IllegalStateException("AudioRecord start failed", failure)
        } finally {
            activationBarrier.complete()
        }

        if (activationFailure != null) {
            val (claimed, retirement) = claimActivationForRetirement(token, recorder, activationBarrier)
            if (!claimed) {
                retirement?.let(::retireOwnedCapture) ?: replayRetirement(token)
                return VoiceAudioCaptureStartOutcome.Rejected
            }
            throwWithRetirementFailure(activationFailure, requireNotNull(retirement))
        }

        beforeActivePublication()
        val activePublication: ActivePublicationDecision<Recorder, CaptureTask> = synchronized(lock) {
            val current = state
            when {
                current is CaptureState.Starting.Activating &&
                    current.token === token &&
                    current.recorder === recorder &&
                    current.activationBarrier === activationBarrier -> {
                    state = CaptureState.Active(
                        token = token,
                        recorder = recorder,
                        task = task,
                        routeLease = current.routeLease,
                        callbackAdmission = CallbackAdmissionBarrier(),
                        retirementBarrier = current.retirementBarrier,
                    )
                    ActivePublicationDecision.Published
                }

                current is CaptureState.Retiring && current.matches(token, recorder) ->
                    ActivePublicationDecision.Retiring(current)

                else -> ActivePublicationDecision.Rejected
            }
        }
        when (activePublication) {
            ActivePublicationDecision.Published -> Unit
            is ActivePublicationDecision.Retiring -> {
                retireOwnedCapture(activePublication.retirement)
                return VoiceAudioCaptureStartOutcome.Rejected
            }

            ActivePublicationDecision.Rejected -> {
                replayRetirement(token)
                return VoiceAudioCaptureStartOutcome.Rejected
            }
        }

        val taskStarted = try {
            startTask(task)
        } catch (failure: Throwable) {
            val retirement = claimActiveForRetirement(token, recorder)
            if (retirement == null) {
                throwWithReplayFailure(failure, token)
            }
            throwWithRetirementFailure(failure, retirement)
        }
        if (taskStarted) return VoiceAudioCaptureStartOutcome.Started

        claimActiveForRetirement(token, recorder)?.let(::retireOwnedCapture) ?: replayRetirement(token)
        return VoiceAudioCaptureStartOutcome.Rejected
    }

    fun abort(token: VoiceAudioCaptureToken): Boolean {
        val (claimed, retirement) = synchronized(lock) {
            when (val current = state) {
                is CaptureState.Starting.Reserved -> {
                    if (current.token !== token) return@synchronized false to null
                    state = CaptureState.Idle
                    true to null
                }

                is CaptureState.Starting.Routed -> claimRoutedLocked(current, token)
                is CaptureState.Starting.Activating -> claimActivatingLocked(current, token, expectedRecorder = null)
                is CaptureState.Active -> claimActiveLocked(current, token, expectedRecorder = null)
                is CaptureState.Retiring -> false to current.takeIf { it.ownedResources.token === token }
                CaptureState.Idle,
                CaptureState.Released,
                -> false to null
            }
        }
        if (retirement != null) {
            retireOwnedCapture(retirement)
        } else if (!claimed) {
            replayRetirement(token)
        }
        return claimed
    }

    fun stop() {
        val retirement = synchronized(lock) {
            when (val current = state) {
                CaptureState.Idle,
                CaptureState.Released,
                -> null

                is CaptureState.Starting.Reserved -> {
                    state = CaptureState.Idle
                    null
                }

                is CaptureState.Starting.Routed -> claimRoutedLocked(current, current.token).second
                is CaptureState.Starting.Activating ->
                    claimActivatingLocked(current, current.token, expectedRecorder = null).second
                is CaptureState.Active -> claimActiveLocked(current, current.token, expectedRecorder = null).second
                is CaptureState.Retiring -> current
            }
        }
        retirement?.let(::retireOwnedCapture)
    }

    fun release(): Boolean {
        val releaseDecision: Pair<Boolean, CaptureState.Retiring<Recorder, CaptureTask>?> = synchronized(lock) {
            when (val current = state) {
                CaptureState.Released -> false to null
                CaptureState.Idle,
                is CaptureState.Starting.Reserved,
                -> {
                    state = CaptureState.Released
                    true to null
                }

                is CaptureState.Starting.Routed -> {
                    val retirement = current.toRetiring(TerminalTarget.Released)
                    state = retirement
                    true to retirement
                }

                is CaptureState.Starting.Activating -> {
                    val retirement = current.toRetiring(TerminalTarget.Released)
                    state = retirement
                    true to retirement
                }

                is CaptureState.Active -> {
                    val retirement = current.toRetiring(TerminalTarget.Released)
                    state = retirement
                    true to retirement
                }

                is CaptureState.Retiring -> {
                    if (current.terminalTarget == TerminalTarget.Released) {
                        false to current
                    } else {
                        val upgraded = current.copy(terminalTarget = TerminalTarget.Released)
                        state = upgraded
                        true to upgraded
                    }
                }
            }
        }
        val (firstRelease, retirement) = releaseDecision
        retirement?.let(::retireOwnedCapture)
        return firstRelease
    }

    fun terminate(token: VoiceAudioCaptureToken, recorder: Recorder): Boolean {
        val (claimed, retirement) = synchronized(lock) {
            when (val current = state) {
                is CaptureState.Starting.Activating -> claimActivatingLocked(current, token, recorder)
                is CaptureState.Active -> claimActiveLocked(current, token, recorder)
                is CaptureState.Retiring -> false to current.takeIf { it.matches(token, recorder) }
                else -> false to null
            }
        }
        if (retirement != null) {
            retireOwnedCapture(retirement)
        } else if (!claimed) {
            replayRetirement(token)
        }
        return claimed
    }

    fun isCurrent(token: VoiceAudioCaptureToken, recorder: Recorder): Boolean = synchronized(lock) {
        val current = state
        current is CaptureState.Active && current.token === token && current.recorder === recorder
    }

    fun runCallbackIfCurrent(
        token: VoiceAudioCaptureToken,
        recorder: Recorder,
        callback: () -> Unit,
    ): Boolean {
        val admission = synchronized(lock) {
            val current = state
            if (
                current is CaptureState.Active &&
                current.token === token &&
                current.recorder === recorder &&
                current.callbackAdmission.tryAdmit()
            ) {
                current.callbackAdmission
            } else {
                null
            }
        } ?: return false

        runVoiceAgentCleanupStages(
            callback,
            {
                if (admission.leave()) resumeRetirementAfterCallback(token, recorder)
            },
        )
        return true
    }

    fun isReleased(): Boolean = synchronized(lock) {
        state === CaptureState.Released ||
            (state as? CaptureState.Retiring)?.terminalTarget == TerminalTarget.Released
    }

    private fun claimActivationForRetirement(
        token: VoiceAudioCaptureToken,
        recorder: Recorder,
        activationBarrier: ActivationBarrier,
    ): Pair<Boolean, CaptureState.Retiring<Recorder, CaptureTask>?> = synchronized(lock) {
        when (val current = state) {
            is CaptureState.Starting.Activating -> {
                if (
                    current.token !== token ||
                    current.recorder !== recorder ||
                    current.activationBarrier !== activationBarrier
                ) {
                    false to null
                } else {
                    claimActivatingLocked(current, token, recorder)
                }
            }

            is CaptureState.Retiring -> false to current.takeIf { it.matches(token, recorder) }
            else -> false to null
        }
    }

    private fun claimActiveForRetirement(
        token: VoiceAudioCaptureToken,
        recorder: Recorder,
    ): CaptureState.Retiring<Recorder, CaptureTask>? = synchronized(lock) {
        when (val current = state) {
            is CaptureState.Active -> claimActiveLocked(current, token, recorder).second
            is CaptureState.Retiring -> current.takeIf { it.matches(token, recorder) }
            else -> null
        }
    }

    private fun claimRoutedLocked(
        current: CaptureState.Starting.Routed<Recorder, CaptureTask>,
        token: VoiceAudioCaptureToken,
    ): Pair<Boolean, CaptureState.Retiring<Recorder, CaptureTask>?> {
        if (current.token !== token) return false to null
        val retirement = current.toRetiring(TerminalTarget.Idle)
        state = retirement
        return true to retirement
    }

    private fun claimActivatingLocked(
        current: CaptureState.Starting.Activating<Recorder, CaptureTask>,
        token: VoiceAudioCaptureToken,
        expectedRecorder: Recorder?,
    ): Pair<Boolean, CaptureState.Retiring<Recorder, CaptureTask>?> {
        if (current.token !== token || (expectedRecorder != null && current.recorder !== expectedRecorder)) {
            return false to null
        }
        val retirement = current.toRetiring(TerminalTarget.Idle)
        state = retirement
        return true to retirement
    }

    private fun claimActiveLocked(
        current: CaptureState.Active<Recorder, CaptureTask>,
        token: VoiceAudioCaptureToken,
        expectedRecorder: Recorder?,
    ): Pair<Boolean, CaptureState.Retiring<Recorder, CaptureTask>?> {
        if (current.token !== token || (expectedRecorder != null && current.recorder !== expectedRecorder)) {
            return false to null
        }
        val retirement = current.toRetiring(TerminalTarget.Idle)
        state = retirement
        return true to retirement
    }

    private fun retireOwnedCapture(retirement: CaptureState.Retiring<Recorder, CaptureTask>) {
        val activation = (retirement.ownedResources as? OwnedCaptureResources.Activating)?.activationBarrier
        if (activation != null && !activation.awaitUnlessOwner()) return
        val callbackAdmission =
            (retirement.ownedResources as? OwnedCaptureResources.Active)?.callbackAdmission
        if (callbackAdmission != null && !callbackAdmission.awaitUnlessAdmitted()) return

        retirement.retirementBarrier.retire(
            afterResultPublished = { result ->
                try {
                    onRetirementResultPublished(result)
                } finally {
                    synchronized(lock) {
                        val current = state
                        if (
                            current is CaptureState.Retiring &&
                            current.ownedResources.token === retirement.ownedResources.token &&
                            current.retirementBarrier === retirement.retirementBarrier
                        ) {
                            state = when (current.terminalTarget) {
                                TerminalTarget.Idle -> CaptureState.Idle
                                TerminalTarget.Released -> CaptureState.Released
                            }
                        }
                    }
                }
            },
        ) {
            when (val resources = retirement.ownedResources) {
                is OwnedCaptureResources.RouteOnly -> resources.routeLease.retire()
                is OwnedCaptureResources.Activating -> cleanupCaptureResources(
                    task = resources.task,
                    recorder = resources.recorder,
                    routeLease = resources.routeLease,
                )

                is OwnedCaptureResources.Active -> cleanupCaptureResources(
                    task = resources.task,
                    recorder = resources.recorder,
                    routeLease = resources.routeLease,
                )
            }
        }
    }

    private fun cleanupCaptureResources(
        task: CaptureTask,
        recorder: Recorder,
        routeLease: VoiceAudioCaptureRouteLease,
    ) {
        runVoiceAgentCleanupStages(
            { cancelTask(task) },
            { stopRecorder(recorder) },
            { releaseRecorder(recorder) },
            routeLease::retire,
        )
    }

    private fun cleanupRejectedPublication(
        token: VoiceAudioCaptureToken,
        recorder: Recorder,
        task: CaptureTask,
    ) {
        runVoiceAgentCleanupStages(
            { cancelTask(task) },
            { releaseRecorder(recorder) },
            { replayRetirement(token) },
        )
    }

    private fun resumeRetirementAfterCallback(
        token: VoiceAudioCaptureToken,
        recorder: Recorder,
    ) {
        val retirement = synchronized(lock) {
            (state as? CaptureState.Retiring)?.takeIf { it.matches(token, recorder) }
        }
        retirement?.let(::retireOwnedCapture)
    }

    private fun replayRetirement(token: VoiceAudioCaptureToken) {
        val retirementBarrier = synchronized(lock) { retirementBarriers[token] }
        retirementBarrier?.replayIfStarted()
    }

    private fun throwWithRetirementFailure(
        failure: Throwable,
        retirement: CaptureState.Retiring<Recorder, CaptureTask>,
    ): Nothing {
        runVoiceAgentCleanupStages(
            { throw failure },
            { retireOwnedCapture(retirement) },
        )
        error("Capture retirement returned without its primary failure")
    }

    private fun throwWithReplayFailure(failure: Throwable, token: VoiceAudioCaptureToken): Nothing {
        runVoiceAgentCleanupStages(
            { throw failure },
            { replayRetirement(token) },
        )
        error("Capture retirement replay returned without its primary failure")
    }
}

private class ActivationBarrier(
    private val ownerThread: Thread = Thread.currentThread(),
) {
    private val completed = CountDownLatch(1)

    fun complete() {
        completed.countDown()
    }

    fun awaitUnlessOwner(): Boolean {
        if (completed.count == 0L) return true
        if (Thread.currentThread() === ownerThread) return false
        var interrupted = false
        while (true) {
            try {
                completed.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return true
    }
}

private class CallbackAdmissionBarrier {
    private val lock = Any()
    private val quiescent = CountDownLatch(1)
    private val admissionsByThread = IdentityHashMap<Thread, Int>()
    private var accepting = true
    private var admissionCount = 0

    fun tryAdmit(): Boolean = synchronized(lock) {
        if (!accepting) return false
        val thread = Thread.currentThread()
        admissionsByThread[thread] = (admissionsByThread[thread] ?: 0) + 1
        admissionCount += 1
        true
    }

    fun close() {
        synchronized(lock) {
            accepting = false
            if (admissionCount == 0) quiescent.countDown()
        }
    }

    fun leave(): Boolean = synchronized(lock) {
        val thread = Thread.currentThread()
        val threadAdmissions = requireNotNull(admissionsByThread[thread])
        if (threadAdmissions == 1) {
            admissionsByThread.remove(thread)
        } else {
            admissionsByThread[thread] = threadAdmissions - 1
        }
        admissionCount -= 1
        if (!accepting && admissionCount == 0) quiescent.countDown()
        !accepting && admissionCount == 0
    }

    fun awaitUnlessAdmitted(): Boolean {
        synchronized(lock) {
            if (admissionCount == 0) return true
            if (admissionsByThread.containsKey(Thread.currentThread())) return false
        }
        var interrupted = false
        while (true) {
            try {
                quiescent.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return true
    }
}

private enum class TerminalTarget {
    Idle,
    Released,
}

private sealed interface OwnedCaptureResources<out Recorder : Any, out CaptureTask : Any> {
    val token: VoiceAudioCaptureToken

    data class RouteOnly(
        override val token: VoiceAudioCaptureToken,
        val routeLease: VoiceAudioCaptureRouteLease,
    ) : OwnedCaptureResources<Nothing, Nothing>

    data class Activating<Recorder : Any, CaptureTask : Any>(
        override val token: VoiceAudioCaptureToken,
        val recorder: Recorder,
        val task: CaptureTask,
        val routeLease: VoiceAudioCaptureRouteLease,
        val activationBarrier: ActivationBarrier,
    ) : OwnedCaptureResources<Recorder, CaptureTask>

    data class Active<Recorder : Any, CaptureTask : Any>(
        override val token: VoiceAudioCaptureToken,
        val recorder: Recorder,
        val task: CaptureTask,
        val routeLease: VoiceAudioCaptureRouteLease,
        val callbackAdmission: CallbackAdmissionBarrier,
    ) : OwnedCaptureResources<Recorder, CaptureTask>
}

private sealed interface CaptureState<out Recorder : Any, out CaptureTask : Any> {
    data object Idle : CaptureState<Nothing, Nothing>

    sealed interface Starting<out Recorder : Any, out CaptureTask : Any> : CaptureState<Recorder, CaptureTask> {
        data class Reserved(
            val token: VoiceAudioCaptureToken,
            val retirementBarrier: RetirementBarrier,
        ) : Starting<Nothing, Nothing>

        data class Routed<Recorder : Any, CaptureTask : Any>(
            val token: VoiceAudioCaptureToken,
            val routeLease: VoiceAudioCaptureRouteLease,
            val retirementBarrier: RetirementBarrier,
        ) : Starting<Recorder, CaptureTask>

        data class Activating<Recorder : Any, CaptureTask : Any>(
            val token: VoiceAudioCaptureToken,
            val recorder: Recorder,
            val task: CaptureTask,
            val routeLease: VoiceAudioCaptureRouteLease,
            val activationBarrier: ActivationBarrier,
            val retirementBarrier: RetirementBarrier,
        ) : Starting<Recorder, CaptureTask>
    }

    data class Active<Recorder : Any, CaptureTask : Any>(
        val token: VoiceAudioCaptureToken,
        val recorder: Recorder,
        val task: CaptureTask,
        val routeLease: VoiceAudioCaptureRouteLease,
        val callbackAdmission: CallbackAdmissionBarrier,
        val retirementBarrier: RetirementBarrier,
    ) : CaptureState<Recorder, CaptureTask>

    data class Retiring<Recorder : Any, CaptureTask : Any>(
        val ownedResources: OwnedCaptureResources<Recorder, CaptureTask>,
        val retirementBarrier: RetirementBarrier,
        val terminalTarget: TerminalTarget,
    ) : CaptureState<Recorder, CaptureTask>

    data object Released : CaptureState<Nothing, Nothing>
}

private fun <Recorder : Any, CaptureTask : Any> CaptureState.Starting.Routed<Recorder, CaptureTask>.toRetiring(
    terminalTarget: TerminalTarget,
): CaptureState.Retiring<Recorder, CaptureTask> = CaptureState.Retiring(
    ownedResources = OwnedCaptureResources.RouteOnly(
        token = token,
        routeLease = routeLease,
    ),
    retirementBarrier = retirementBarrier.also(RetirementBarrier::begin),
    terminalTarget = terminalTarget,
)

private fun <Recorder : Any, CaptureTask : Any> CaptureState.Starting.Activating<Recorder, CaptureTask>.toRetiring(
    terminalTarget: TerminalTarget,
): CaptureState.Retiring<Recorder, CaptureTask> = CaptureState.Retiring(
    ownedResources = OwnedCaptureResources.Activating(
        token = token,
        recorder = recorder,
        task = task,
        routeLease = routeLease,
        activationBarrier = activationBarrier,
    ),
    retirementBarrier = retirementBarrier.also(RetirementBarrier::begin),
    terminalTarget = terminalTarget,
)

private fun <Recorder : Any, CaptureTask : Any> CaptureState.Active<Recorder, CaptureTask>.toRetiring(
    terminalTarget: TerminalTarget,
): CaptureState.Retiring<Recorder, CaptureTask> {
    callbackAdmission.close()
    return CaptureState.Retiring(
        ownedResources = OwnedCaptureResources.Active(
            token = token,
            recorder = recorder,
            task = task,
            routeLease = routeLease,
            callbackAdmission = callbackAdmission,
        ),
        retirementBarrier = retirementBarrier.also(RetirementBarrier::begin),
        terminalTarget = terminalTarget,
    )
}

private fun <Recorder : Any, CaptureTask : Any> CaptureState.Retiring<Recorder, CaptureTask>.matches(
    token: VoiceAudioCaptureToken,
    recorder: Recorder,
): Boolean {
    if (ownedResources.token !== token) return false
    return when (val resources = ownedResources) {
        is OwnedCaptureResources.RouteOnly -> false
        is OwnedCaptureResources.Activating -> resources.recorder === recorder
        is OwnedCaptureResources.Active -> resources.recorder === recorder
    }
}
