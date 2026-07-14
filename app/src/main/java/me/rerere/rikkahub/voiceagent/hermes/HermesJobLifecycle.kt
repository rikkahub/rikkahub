package me.rerere.rikkahub.voiceagent.hermes

import me.rerere.rikkahub.voiceagent.hermesvoice.HermesJobStatus

internal const val HERMES_CANCELED_MESSAGE = "Hermes job canceled."
internal const val HERMES_TIMEOUT_MESSAGE = "Hermes job polling timed out."
internal const val HERMES_UNAVAILABLE_MESSAGE = "Hermes job was no longer available."
internal const val HERMES_SUBMIT_SUCCEEDED_MESSAGE =
    "Hermes submit response cannot be succeeded without an answer"
internal const val HERMES_SUCCEEDED_WITHOUT_ANSWER_MESSAGE = "Hermes job succeeded without an answer"

enum class CancelOrigin { Gemini, User }

sealed interface TerminalKind {
    data class Complete(val answer: String, val serverElapsedMs: Long?) : TerminalKind
    data class Failed(val message: String) : TerminalKind
    data class Expired(val message: String) : TerminalKind
    data class Canceled(val origin: CancelOrigin, val message: String) : TerminalKind
}

sealed interface JobState {
    data object Created : JobState
    data class Submitting(val cancelOrigin: CancelOrigin?) : JobState
    data class Polling(
        val jobId: String,
        val ackDelivered: Boolean,
        val consecutivePollFailures: Int,
    ) : JobState
    data class Terminal(val kind: TerminalKind) : JobState
}

sealed interface JobEvent {
    data object Start : JobEvent
    data class Resume(val jobId: String, val stillWorkingAnnounced: Boolean) : JobEvent
    data class SubmitReturned(
        val jobId: String,
        val status: HermesJobStatus,
        val failureMessage: String?,
    ) : JobEvent
    data class SubmitFailed(val message: String) : JobEvent
    data class PollReturned(
        val status: HermesJobStatus,
        val answer: String?,
        val failureMessage: String?,
        val serverElapsedMs: Long?,
    ) : JobEvent
    data class PollFailed(val message: String, val terminal: Boolean) : JobEvent
    data object TimedOut : JobEvent
    data object StillWorkingDue : JobEvent
    data object QueuedAckDelivered : JobEvent
    data class CancelRequested(val origin: CancelOrigin) : JobEvent
}

sealed interface JobEffect {
    data object StartSubmit : JobEffect
    data class SchedulePoll(val jobId: String, val delayMs: Long) : JobEffect
    data object AbortInFlight : JobEffect
    data object ScheduleStillWorkingTimer : JobEffect
    data object CancelStillWorkingTimer : JobEffect
    data object SendQueuedAck : JobEffect
    data object PersistPending : JobEffect
    data class NoteJobCreated(val jobId: String, val status: HermesJobStatus) : JobEffect
    data class PersistActive(val status: HermesJobStatus, val jobId: String) : JobEffect
    data class PersistTerminal(
        val kind: TerminalKind,
        val jobId: String?,
        val emitFailureTelemetry: Boolean,
    ) : JobEffect
    data class CancelRemoteJob(val jobId: String) : JobEffect
    data class AnnounceCompletion(val jobId: String) : JobEffect
    data class AnnounceTerminal(val jobId: String?, val ackDelivered: Boolean) : JobEffect
    data class AnnounceStillWorking(val jobId: String) : JobEffect
    data class NotifyPollFailure(val attempt: Int, val message: String) : JobEffect
}

data class Transition(val state: JobState, val effects: List<JobEffect>)

/**
 * The entire Hermes job lifecycle as a pure, total transition function. All
 * mutation, IO, timing, and telemetry live in the effect executor
 * (HermesJobManager); this class owns only which effects happen in which
 * state, in which order. Late poll results, duplicate cancels, and stale
 * timer fires are absorbed by the Terminal rows instead of guarded by flags.
 */
class HermesJobReducer(
    private val pollIntervalMs: Long,
    private val pollRetryDelayMs: Long,
) {
    fun reduce(state: JobState, event: JobEvent): Transition = when (state) {
        JobState.Created -> reduceCreated(event)
        is JobState.Submitting -> reduceSubmitting(state, event)
        is JobState.Polling -> reducePolling(state, event)
        is JobState.Terminal -> Transition(state, emptyList())
    }

    private fun reduceCreated(event: JobEvent): Transition = when (event) {
        JobEvent.Start -> Transition(
            JobState.Submitting(cancelOrigin = null),
            listOf(JobEffect.PersistPending, JobEffect.StartSubmit),
        )

        is JobEvent.Resume -> Transition(
            JobState.Polling(jobId = event.jobId, ackDelivered = true, consecutivePollFailures = 0),
            buildList {
                if (!event.stillWorkingAnnounced) add(JobEffect.ScheduleStillWorkingTimer)
                add(JobEffect.SchedulePoll(jobId = event.jobId, delayMs = 0L))
            },
        )

        // Defensive totality: submit()/resumeActiveJobs() enqueue Start/Resume
        // synchronously at actor creation, so a cancel can never actually arrive
        // first — but if it did, terminate without ever submitting.
        is JobEvent.CancelRequested -> Transition(
            JobState.Terminal(TerminalKind.Canceled(event.origin, HERMES_CANCELED_MESSAGE)),
            listOf(
                JobEffect.PersistTerminal(
                    kind = TerminalKind.Canceled(event.origin, HERMES_CANCELED_MESSAGE),
                    jobId = null,
                    emitFailureTelemetry = false,
                )
            ),
        )

        else -> Transition(JobState.Created, emptyList())
    }

    private fun reduceSubmitting(state: JobState.Submitting, event: JobEvent): Transition {
        val cancelOrigin = state.cancelOrigin
        return when (event) {
            is JobEvent.SubmitReturned -> if (cancelOrigin != null) {
                // The cancel raced the submit; this is the one point that knows the
                // real jobId: adopt it onto the canceled record and cancel remotely.
                val kind = TerminalKind.Canceled(cancelOrigin, HERMES_CANCELED_MESSAGE)
                Transition(
                    JobState.Terminal(kind),
                    listOf(
                        JobEffect.PersistTerminal(kind = kind, jobId = event.jobId, emitFailureTelemetry = false),
                        JobEffect.CancelRemoteJob(event.jobId),
                    ),
                )
            } else when (event.status) {
                HermesJobStatus.Accepted,
                HermesJobStatus.Queued,
                HermesJobStatus.Running,
                    -> Transition(
                    JobState.Polling(jobId = event.jobId, ackDelivered = false, consecutivePollFailures = 0),
                    listOf(
                        JobEffect.PersistActive(status = event.status, jobId = event.jobId),
                        JobEffect.NoteJobCreated(jobId = event.jobId, status = event.status),
                        JobEffect.SendQueuedAck,
                        JobEffect.ScheduleStillWorkingTimer,
                        JobEffect.SchedulePoll(jobId = event.jobId, delayMs = 0L),
                    ),
                )

                HermesJobStatus.Succeeded -> terminalFromSubmit(
                    TerminalKind.Failed(HERMES_SUBMIT_SUCCEEDED_MESSAGE),
                    event.jobId,
                )

                HermesJobStatus.Failed -> terminalFromSubmit(
                    TerminalKind.Failed(event.failureMessage ?: HERMES_UNAVAILABLE_MESSAGE),
                    event.jobId,
                )

                HermesJobStatus.Expired -> terminalFromSubmit(
                    TerminalKind.Expired(event.failureMessage ?: HERMES_UNAVAILABLE_MESSAGE),
                    event.jobId,
                )

                HermesJobStatus.Canceled -> terminalFromSubmit(
                    TerminalKind.Canceled(CancelOrigin.Gemini, event.failureMessage ?: HERMES_CANCELED_MESSAGE),
                    event.jobId,
                )
            }

            is JobEvent.SubmitFailed -> if (cancelOrigin != null) {
                Transition(
                    JobState.Terminal(TerminalKind.Canceled(cancelOrigin, HERMES_CANCELED_MESSAGE)),
                    emptyList(),
                )
            } else {
                val kind = TerminalKind.Failed(event.message)
                Transition(
                    JobState.Terminal(kind),
                    listOf(JobEffect.PersistTerminal(kind = kind, jobId = null, emitFailureTelemetry = true)),
                )
            }

            JobEvent.TimedOut -> if (cancelOrigin != null) {
                Transition(
                    JobState.Terminal(TerminalKind.Canceled(cancelOrigin, HERMES_CANCELED_MESSAGE)),
                    emptyList(),
                )
            } else {
                val kind = TerminalKind.Expired(HERMES_TIMEOUT_MESSAGE)
                Transition(
                    JobState.Terminal(kind),
                    listOf(JobEffect.PersistTerminal(kind = kind, jobId = null, emitFailureTelemetry = true)),
                )
            }

            is JobEvent.CancelRequested -> Transition(
                JobState.Submitting(cancelOrigin = escalate(cancelOrigin, event.origin)),
                if (cancelOrigin == null) {
                    listOf(
                        JobEffect.PersistTerminal(
                            kind = TerminalKind.Canceled(event.origin, HERMES_CANCELED_MESSAGE),
                            jobId = null,
                            emitFailureTelemetry = false,
                        )
                    )
                } else {
                    emptyList()
                },
            )

            else -> Transition(state, emptyList())
        }
    }

    private fun reducePolling(state: JobState.Polling, event: JobEvent): Transition = when (event) {
        is JobEvent.PollReturned -> when (event.status) {
            HermesJobStatus.Accepted,
            HermesJobStatus.Queued,
                -> Transition(
                state.copy(consecutivePollFailures = 0),
                listOf(
                    JobEffect.PersistActive(status = HermesJobStatus.Queued, jobId = state.jobId),
                    JobEffect.SchedulePoll(jobId = state.jobId, delayMs = pollIntervalMs),
                ),
            )

            HermesJobStatus.Running -> Transition(
                state.copy(consecutivePollFailures = 0),
                listOf(
                    JobEffect.PersistActive(status = HermesJobStatus.Running, jobId = state.jobId),
                    JobEffect.SchedulePoll(jobId = state.jobId, delayMs = pollIntervalMs),
                ),
            )

            HermesJobStatus.Succeeded -> {
                val answer = event.answer
                if (answer != null) {
                    val kind = TerminalKind.Complete(answer = answer, serverElapsedMs = event.serverElapsedMs)
                    Transition(
                        JobState.Terminal(kind),
                        listOf(
                            JobEffect.CancelStillWorkingTimer,
                            JobEffect.PersistTerminal(kind = kind, jobId = state.jobId, emitFailureTelemetry = false),
                            JobEffect.AnnounceCompletion(state.jobId),
                        ),
                    )
                } else {
                    terminalFromPolling(state, TerminalKind.Failed(HERMES_SUCCEEDED_WITHOUT_ANSWER_MESSAGE))
                }
            }

            HermesJobStatus.Failed -> terminalFromPolling(
                state,
                TerminalKind.Failed(event.failureMessage ?: HERMES_UNAVAILABLE_MESSAGE),
            )

            HermesJobStatus.Expired -> terminalFromPolling(
                state,
                TerminalKind.Expired(event.failureMessage ?: HERMES_UNAVAILABLE_MESSAGE),
            )

            HermesJobStatus.Canceled -> terminalFromPolling(
                state,
                TerminalKind.Canceled(CancelOrigin.Gemini, event.failureMessage ?: HERMES_CANCELED_MESSAGE),
            )
        }

        is JobEvent.PollFailed -> if (event.terminal) {
            terminalFromPolling(state, TerminalKind.Failed(event.message))
        } else {
            val failures = state.consecutivePollFailures + 1
            Transition(
                state.copy(consecutivePollFailures = failures),
                listOf(
                    JobEffect.NotifyPollFailure(attempt = failures, message = event.message),
                    JobEffect.SchedulePoll(jobId = state.jobId, delayMs = nextPollRetryDelayMs(failures)),
                ),
            )
        }

        JobEvent.TimedOut -> {
            val kind = TerminalKind.Expired(HERMES_TIMEOUT_MESSAGE)
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = state.jobId, emitFailureTelemetry = true),
                    JobEffect.CancelRemoteJob(state.jobId),
                    JobEffect.AnnounceTerminal(jobId = state.jobId, ackDelivered = state.ackDelivered),
                ),
            )
        }

        JobEvent.StillWorkingDue -> Transition(
            state,
            if (state.ackDelivered) listOf(JobEffect.AnnounceStillWorking(state.jobId)) else emptyList(),
        )

        JobEvent.QueuedAckDelivered -> Transition(state.copy(ackDelivered = true), emptyList())

        is JobEvent.CancelRequested -> {
            val kind = TerminalKind.Canceled(event.origin, HERMES_CANCELED_MESSAGE)
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.AbortInFlight,
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = state.jobId, emitFailureTelemetry = false),
                    JobEffect.CancelRemoteJob(state.jobId),
                ),
            )
        }

        else -> Transition(state, emptyList())
    }

    private fun terminalFromSubmit(kind: TerminalKind, jobId: String): Transition = Transition(
        JobState.Terminal(kind),
        listOf(
            JobEffect.PersistTerminal(kind = kind, jobId = jobId, emitFailureTelemetry = true),
            JobEffect.AnnounceTerminal(jobId = jobId, ackDelivered = false),
        ),
    )

    private fun terminalFromPolling(state: JobState.Polling, kind: TerminalKind): Transition = Transition(
        JobState.Terminal(kind),
        listOf(
            JobEffect.CancelStillWorkingTimer,
            JobEffect.PersistTerminal(kind = kind, jobId = state.jobId, emitFailureTelemetry = true),
            JobEffect.AnnounceTerminal(jobId = state.jobId, ackDelivered = state.ackDelivered),
        ),
    )

    private fun escalate(current: CancelOrigin?, incoming: CancelOrigin): CancelOrigin =
        if (current == CancelOrigin.User || incoming == CancelOrigin.User) CancelOrigin.User else incoming

    private fun nextPollRetryDelayMs(failures: Int): Long {
        val multiplier = when {
            failures <= 1 -> 1L
            failures >= 7 -> 64L
            else -> 1L shl (failures - 1)
        }
        return (pollRetryDelayMs * multiplier)
            .coerceAtMost(pollIntervalMs)
            .coerceAtLeast(1L)
    }
}
