package me.rerere.rikkahub.voiceagent

import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred

internal class VoiceCaptureEpochToken internal constructor(
    internal val epoch: VoiceCaptureEpoch,
)

internal sealed interface VoiceCaptureStartCompletion {
    data class Accepted(val admission: VoiceCaptureEffectAdmission) : VoiceCaptureStartCompletion
    data class Rejected(val cleanup: VoiceCaptureEpochCleanup) : VoiceCaptureStartCompletion
}

internal class VoiceCaptureEpochOwner(
    private val lock: Any,
    private val canUseEpochLocked: (sessionId: Long) -> Boolean,
) {
    private var current: VoiceCaptureEpoch? = null
    private var nextIdentity = 0L

    suspend fun open(sessionId: Long): VoiceCaptureEpochToken? {
        while (true) {
            val decision = synchronized(lock) {
                val existing = current
                when {
                    existing != null -> OpenDecision.Wait(existing.retired)
                    !canUseEpochLocked(sessionId) -> OpenDecision.Rejected
                    else -> {
                        val epoch = VoiceCaptureEpoch(
                            identity = ++nextIdentity,
                            sessionId = sessionId,
                        )
                        current = epoch
                        OpenDecision.Opened(VoiceCaptureEpochToken(epoch))
                    }
                }
            }
            when (decision) {
                is OpenDecision.Opened -> return decision.token
                is OpenDecision.Wait -> decision.retired.await()
                OpenDecision.Rejected -> return null
            }
        }
    }

    fun tryAdmit(token: VoiceCaptureEpochToken): VoiceCaptureEffectAdmission? =
        synchronized(lock) {
            admitLocked(token.epoch)
        }

    fun claimDebugCompletion(token: VoiceCaptureEpochToken): VoiceCaptureEffectAdmission? =
        synchronized(lock) {
            val epoch = token.epoch
            val admission = admitLocked(epoch) ?: return@synchronized null
            closeLocked(epoch)
            admission
        }

    fun completeStart(token: VoiceCaptureEpochToken): VoiceCaptureStartCompletion {
        val epoch = token.epoch
        val decision = synchronized(lock) {
            check(epoch.startInFlight) { "Capture start completed more than once" }
            epoch.startInFlight = false
            val admission = admitLocked(epoch)
            if (admission != null) {
                VoiceCaptureStartCompletion.Accepted(admission)
            } else {
                closeLocked(epoch)
                epoch.pendingCleanups += 1
                VoiceCaptureStartCompletion.Rejected(VoiceCaptureEpochCleanup(this, epoch))
            }
        }
        publishRetirementIfEligible(epoch)
        return decision
    }

    fun abortStart(token: VoiceCaptureEpochToken) {
        val epoch = token.epoch
        synchronized(lock) {
            if (!epoch.startInFlight) return
            epoch.startInFlight = false
            closeLocked(epoch)
        }
        publishRetirementIfEligible(epoch)
    }

    fun closeCurrent(): VoiceCaptureEpochCleanup {
        val epoch = synchronized(lock) {
            current?.also { owned ->
                closeLocked(owned)
                owned.pendingCleanups += 1
            }
        }
        return VoiceCaptureEpochCleanup(this, epoch)
    }

    private fun admitLocked(epoch: VoiceCaptureEpoch): VoiceCaptureEffectAdmission? {
        if (current !== epoch || !epoch.open || !canUseEpochLocked(epoch.sessionId)) return null
        val thread = Thread.currentThread()
        epoch.admissionsByThread[thread] = (epoch.admissionsByThread[thread] ?: 0) + 1
        epoch.admissionCount += 1
        return VoiceCaptureEffectAdmission(this, epoch, thread)
    }

    private fun closeLocked(epoch: VoiceCaptureEpoch) {
        epoch.open = false
        if (epoch.admissionCount == 0) epoch.admissionsDrained.countDown()
    }

    internal fun leave(epoch: VoiceCaptureEpoch, thread: Thread) {
        val deferred = synchronized(lock) {
            val count = requireNotNull(epoch.admissionsByThread[thread])
            if (count == 1) {
                epoch.admissionsByThread.remove(thread)
            } else {
                epoch.admissionsByThread[thread] = count - 1
            }
            epoch.admissionCount -= 1
            if (!epoch.open && epoch.admissionCount == 0) epoch.admissionsDrained.countDown()
            if (epoch.admissionCount == 0) epoch.deferredCleanups.toList().also {
                epoch.deferredCleanups.clear()
            } else emptyList()
        }
        var primaryFailure: Throwable? = null
        deferred.forEach { cleanup ->
            try {
                cleanup.runDeferred()
            } catch (failure: Throwable) {
                val primary = primaryFailure
                if (primary == null) {
                    primaryFailure = failure
                } else if (primary !== failure) {
                    primary.addSuppressed(failure)
                }
            }
        }
        primaryFailure?.let { throw it }
        publishRetirementIfEligible(epoch)
    }

    internal fun runCleanup(epoch: VoiceCaptureEpoch?, cleanup: () -> Unit) {
        if (epoch == null) {
            cleanup()
            return
        }
        val currentThread = Thread.currentThread()
        val deferred = synchronized(lock) {
            if (epoch.admissionsByThread.containsKey(currentThread)) {
                epoch.deferredCleanups += DeferredEpochCleanup(this, epoch, cleanup)
                true
            } else {
                false
            }
        }
        if (deferred) return
        awaitUninterruptibly(epoch.admissionsDrained)
        try {
            cleanup()
        } finally {
            completeCleanup(epoch)
        }
    }

    internal fun runDeferredCleanup(epoch: VoiceCaptureEpoch, cleanup: () -> Unit) {
        try {
            cleanup()
        } finally {
            completeCleanup(epoch)
        }
    }

    private fun completeCleanup(epoch: VoiceCaptureEpoch) {
        synchronized(lock) {
            check(epoch.pendingCleanups > 0)
            epoch.pendingCleanups -= 1
        }
        publishRetirementIfEligible(epoch)
    }

    private fun publishRetirementIfEligible(epoch: VoiceCaptureEpoch) {
        val retired = synchronized(lock) {
            if (
                current === epoch &&
                !epoch.open &&
                !epoch.startInFlight &&
                epoch.admissionCount == 0 &&
                epoch.pendingCleanups == 0
            ) {
                current = null
                epoch.retired
            } else {
                null
            }
        }
        retired?.complete(Unit)
    }
}

internal class VoiceCaptureEffectAdmission internal constructor(
    private val owner: VoiceCaptureEpochOwner,
    private val epoch: VoiceCaptureEpoch,
    private val thread: Thread,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        check(!closed) { "Capture effect admission left more than once" }
        closed = true
        owner.leave(epoch, thread)
    }
}

internal class VoiceCaptureEpochCleanup internal constructor(
    private val owner: VoiceCaptureEpochOwner,
    private val epoch: VoiceCaptureEpoch?,
) {
    private var finished = false

    fun finish(cleanup: () -> Unit = {}) {
        check(!finished) { "Capture epoch cleanup finished more than once" }
        finished = true
        owner.runCleanup(epoch, cleanup)
    }
}

internal class VoiceCaptureEpoch internal constructor(
    val identity: Long,
    val sessionId: Long,
) {
    var open = true
    var startInFlight = true
    var admissionCount = 0
    var pendingCleanups = 0
    val admissionsByThread = IdentityHashMap<Thread, Int>()
    val deferredCleanups = mutableListOf<DeferredEpochCleanup>()
    val admissionsDrained = CountDownLatch(1)
    val retired = CompletableDeferred<Unit>()
}

internal class DeferredEpochCleanup(
    private val owner: VoiceCaptureEpochOwner,
    private val epoch: VoiceCaptureEpoch,
    private val cleanup: () -> Unit,
) {
    fun runDeferred() = owner.runDeferredCleanup(epoch, cleanup)
}

private sealed interface OpenDecision {
    data class Opened(val token: VoiceCaptureEpochToken) : OpenDecision
    data class Wait(val retired: CompletableDeferred<Unit>) : OpenDecision
    data object Rejected : OpenDecision
}

private fun awaitUninterruptibly(latch: CountDownLatch) {
    val thread = Thread.currentThread()
    var interrupted = false
    while (true) {
        try {
            latch.await()
            break
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) thread.interrupt()
}
