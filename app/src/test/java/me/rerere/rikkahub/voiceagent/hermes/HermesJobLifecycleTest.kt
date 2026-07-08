package me.rerere.rikkahub.voiceagent.hermes

import me.rerere.rikkahub.voiceagent.voicelab.HermesJobStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class HermesJobLifecycleTest {

    private val reducer = HermesJobReducer(pollIntervalMs = 1_000L, pollRetryDelayMs = 250L)

    private fun polling(ack: Boolean = true, failures: Int = 0) =
        JobState.Polling(jobId = "job-1", ackDelivered = ack, consecutivePollFailures = failures)

    // ---------------------------------------------------------------------
    // Created
    // ---------------------------------------------------------------------

    @Test
    fun `start persists pending then submits`() {
        assertEquals(
            Transition(
                JobState.Submitting(cancelOrigin = null),
                listOf(JobEffect.PersistPending, JobEffect.StartSubmit),
            ),
            reducer.reduce(JobState.Created, JobEvent.Start),
        )
    }

    @Test
    fun `resume with still-working not yet announced schedules timer then poll`() {
        assertEquals(
            Transition(
                JobState.Polling(jobId = "job-9", ackDelivered = true, consecutivePollFailures = 0),
                listOf(
                    JobEffect.ScheduleStillWorkingTimer,
                    JobEffect.SchedulePoll(jobId = "job-9", delayMs = 0L),
                ),
            ),
            reducer.reduce(JobState.Created, JobEvent.Resume(jobId = "job-9", stillWorkingAnnounced = false)),
        )
    }

    @Test
    fun `resume with still-working already announced only schedules poll`() {
        assertEquals(
            Transition(
                JobState.Polling(jobId = "job-9", ackDelivered = true, consecutivePollFailures = 0),
                listOf(JobEffect.SchedulePoll(jobId = "job-9", delayMs = 0L)),
            ),
            reducer.reduce(JobState.Created, JobEvent.Resume(jobId = "job-9", stillWorkingAnnounced = true)),
        )
    }

    @Test
    fun `cancel requested while created terminates canceled without submitting - gemini origin`() {
        val kind = TerminalKind.Canceled(CancelOrigin.Gemini, HERMES_CANCELED_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(JobEffect.PersistTerminal(kind = kind, jobId = null, emitFailureTelemetry = false)),
            ),
            reducer.reduce(JobState.Created, JobEvent.CancelRequested(CancelOrigin.Gemini)),
        )
    }

    @Test
    fun `cancel requested while created terminates canceled without submitting - user origin`() {
        val kind = TerminalKind.Canceled(CancelOrigin.User, HERMES_CANCELED_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(JobEffect.PersistTerminal(kind = kind, jobId = null, emitFailureTelemetry = false)),
            ),
            reducer.reduce(JobState.Created, JobEvent.CancelRequested(CancelOrigin.User)),
        )
    }

    @Test
    fun `created absorbs unrelated events without effects`() {
        val unrelated = listOf(
            JobEvent.SubmitReturned("job-9", HermesJobStatus.Queued, null),
            JobEvent.SubmitFailed("boom"),
            JobEvent.PollReturned(HermesJobStatus.Running, null, null, null),
            JobEvent.PollFailed("boom", terminal = false),
            JobEvent.TimedOut,
            JobEvent.StillWorkingDue,
            JobEvent.QueuedAckDelivered,
        )
        for (event in unrelated) {
            assertEquals(
                "event=$event",
                Transition(JobState.Created, emptyList()),
                reducer.reduce(JobState.Created, event),
            )
        }
    }

    // ---------------------------------------------------------------------
    // Submitting(cancelOrigin = null)
    // ---------------------------------------------------------------------

    @Test
    fun `submit returned accepted starts polling and sends queued ack`() {
        assertEquals(
            Transition(
                JobState.Polling(jobId = "job-1", ackDelivered = false, consecutivePollFailures = 0),
                listOf(
                    JobEffect.PersistActive(status = HermesJobStatus.Accepted, jobId = "job-1"),
                    JobEffect.NoteJobCreated(jobId = "job-1", status = HermesJobStatus.Accepted),
                    JobEffect.SendQueuedAck,
                    JobEffect.ScheduleStillWorkingTimer,
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 0L),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Accepted, null),
            ),
        )
    }

    @Test
    fun `submit returned queued starts polling and sends queued ack`() {
        assertEquals(
            Transition(
                JobState.Polling(jobId = "job-1", ackDelivered = false, consecutivePollFailures = 0),
                listOf(
                    JobEffect.PersistActive(status = HermesJobStatus.Queued, jobId = "job-1"),
                    JobEffect.NoteJobCreated(jobId = "job-1", status = HermesJobStatus.Queued),
                    JobEffect.SendQueuedAck,
                    JobEffect.ScheduleStillWorkingTimer,
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 0L),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Queued, null),
            ),
        )
    }

    @Test
    fun `submit returned running starts polling and sends queued ack`() {
        assertEquals(
            Transition(
                JobState.Polling(jobId = "job-1", ackDelivered = false, consecutivePollFailures = 0),
                listOf(
                    JobEffect.PersistActive(status = HermesJobStatus.Running, jobId = "job-1"),
                    JobEffect.NoteJobCreated(jobId = "job-1", status = HermesJobStatus.Running),
                    JobEffect.SendQueuedAck,
                    JobEffect.ScheduleStillWorkingTimer,
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 0L),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Running, null),
            ),
        )
    }

    @Test
    fun `submit returned succeeded is treated as failure since submit cannot carry an answer`() {
        val kind = TerminalKind.Failed(HERMES_SUBMIT_SUCCEEDED_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Succeeded, null),
            ),
        )
    }

    @Test
    fun `submit returned failed with message becomes terminal failed`() {
        val kind = TerminalKind.Failed("server exploded")
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Failed, "server exploded"),
            ),
        )
    }

    @Test
    fun `submit returned failed with null message falls back to unavailable message`() {
        val kind = TerminalKind.Failed(HERMES_UNAVAILABLE_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Failed, null),
            ),
        )
    }

    @Test
    fun `submit returned expired with message becomes terminal expired`() {
        val kind = TerminalKind.Expired("gone")
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Expired, "gone"),
            ),
        )
    }

    @Test
    fun `submit returned expired with null message falls back to unavailable message`() {
        val kind = TerminalKind.Expired(HERMES_UNAVAILABLE_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Expired, null),
            ),
        )
    }

    @Test
    fun `submit returned canceled with message becomes terminal canceled gemini origin`() {
        val kind = TerminalKind.Canceled(CancelOrigin.Gemini, "remote canceled it")
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Canceled, "remote canceled it"),
            ),
        )
    }

    @Test
    fun `submit returned canceled with null message falls back to canceled message`() {
        val kind = TerminalKind.Canceled(CancelOrigin.Gemini, HERMES_CANCELED_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Canceled, null),
            ),
        )
    }

    @Test
    fun `submit failed while not canceled persists failure`() {
        val kind = TerminalKind.Failed("network exploded")
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(JobEffect.PersistTerminal(kind = kind, jobId = null, emitFailureTelemetry = true)),
            ),
            reducer.reduce(JobState.Submitting(cancelOrigin = null), JobEvent.SubmitFailed("network exploded")),
        )
    }

    @Test
    fun `timed out while submitting and not canceled becomes terminal expired`() {
        val kind = TerminalKind.Expired(HERMES_TIMEOUT_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(JobEffect.PersistTerminal(kind = kind, jobId = null, emitFailureTelemetry = true)),
            ),
            reducer.reduce(JobState.Submitting(cancelOrigin = null), JobEvent.TimedOut),
        )
    }

    @Test
    fun `cancel requested while submitting and not yet canceled persists canceled and marks pending`() {
        val kind = TerminalKind.Canceled(CancelOrigin.Gemini, HERMES_CANCELED_MESSAGE)
        assertEquals(
            Transition(
                JobState.Submitting(cancelOrigin = CancelOrigin.Gemini),
                listOf(JobEffect.PersistTerminal(kind = kind, jobId = null, emitFailureTelemetry = false)),
            ),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = null),
                JobEvent.CancelRequested(CancelOrigin.Gemini),
            ),
        )
    }

    @Test
    fun `submitting with no cancel pending absorbs unrelated events preserving state`() {
        val state = JobState.Submitting(cancelOrigin = null)
        val unrelated = listOf(
            JobEvent.Start,
            JobEvent.Resume("job-9", stillWorkingAnnounced = false),
            JobEvent.PollReturned(HermesJobStatus.Running, null, null, null),
            JobEvent.PollFailed("boom", terminal = false),
            JobEvent.StillWorkingDue,
            JobEvent.QueuedAckDelivered,
        )
        for (event in unrelated) {
            assertEquals("event=$event", Transition(state, emptyList()), reducer.reduce(state, event))
        }
    }

    // ---------------------------------------------------------------------
    // Submitting(cancelOrigin = non-null) -- cancel raced the submit
    // ---------------------------------------------------------------------

    @Test
    fun `submit returned while cancel pending adopts jobId, persists canceled, cancels remote - status irrelevant`() {
        val kind = TerminalKind.Canceled(CancelOrigin.Gemini, HERMES_CANCELED_MESSAGE)
        val expected = Transition(
            JobState.Terminal(kind),
            listOf(
                JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = false),
                JobEffect.CancelRemoteJob("job-1"),
            ),
        )
        assertEquals(
            expected,
            reducer.reduce(
                JobState.Submitting(cancelOrigin = CancelOrigin.Gemini),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Queued, null),
            ),
        )
        // Status is irrelevant once a cancel is pending -- even a terminal-flavored
        // status is fully overridden by the canceled adoption row.
        assertEquals(
            expected,
            reducer.reduce(
                JobState.Submitting(cancelOrigin = CancelOrigin.Gemini),
                JobEvent.SubmitReturned("job-1", HermesJobStatus.Failed, "irrelevant"),
            ),
        )
    }

    @Test
    fun `submit failed while cancel pending emits no effects`() {
        val kind = TerminalKind.Canceled(CancelOrigin.Gemini, HERMES_CANCELED_MESSAGE)
        assertEquals(
            Transition(JobState.Terminal(kind), emptyList()),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = CancelOrigin.Gemini),
                JobEvent.SubmitFailed("boom"),
            ),
        )
    }

    @Test
    fun `timed out while cancel pending emits no effects`() {
        val kind = TerminalKind.Canceled(CancelOrigin.Gemini, HERMES_CANCELED_MESSAGE)
        assertEquals(
            Transition(JobState.Terminal(kind), emptyList()),
            reducer.reduce(JobState.Submitting(cancelOrigin = CancelOrigin.Gemini), JobEvent.TimedOut),
        )
    }

    @Test
    fun `cancel escalation gemini then user emits nothing and escalates to user, user then gemini stays user`() {
        // Second cancel while one is already pending: cancelOrigin != null branch
        // always emits emptyList() -- the first cancel already persisted the record.
        assertEquals(
            Transition(JobState.Submitting(cancelOrigin = CancelOrigin.User), emptyList()),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = CancelOrigin.Gemini),
                JobEvent.CancelRequested(CancelOrigin.User),
            ),
        )
        // User is sticky: a later Gemini-origin cancel cannot downgrade it.
        assertEquals(
            Transition(JobState.Submitting(cancelOrigin = CancelOrigin.User), emptyList()),
            reducer.reduce(
                JobState.Submitting(cancelOrigin = CancelOrigin.User),
                JobEvent.CancelRequested(CancelOrigin.Gemini),
            ),
        )
    }

    @Test
    fun `submitting with cancel pending absorbs unrelated events preserving cancel origin`() {
        val state = JobState.Submitting(cancelOrigin = CancelOrigin.Gemini)
        val unrelated = listOf(
            JobEvent.Start,
            JobEvent.Resume("job-9", stillWorkingAnnounced = false),
            JobEvent.PollReturned(HermesJobStatus.Running, null, null, null),
            JobEvent.PollFailed("boom", terminal = false),
            JobEvent.StillWorkingDue,
            JobEvent.QueuedAckDelivered,
        )
        for (event in unrelated) {
            assertEquals("event=$event", Transition(state, emptyList()), reducer.reduce(state, event))
        }
    }

    // ---------------------------------------------------------------------
    // Polling
    // ---------------------------------------------------------------------

    @Test
    fun `poll returned accepted resets failures and reschedules, normalized to queued`() {
        assertEquals(
            Transition(
                polling(failures = 0),
                listOf(
                    JobEffect.PersistActive(status = HermesJobStatus.Queued, jobId = "job-1"),
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 1_000L),
                ),
            ),
            reducer.reduce(
                polling(failures = 3),
                JobEvent.PollReturned(HermesJobStatus.Accepted, null, null, null),
            ),
        )
    }

    @Test
    fun `poll returned queued resets failures and reschedules`() {
        assertEquals(
            Transition(
                polling(failures = 0),
                listOf(
                    JobEffect.PersistActive(status = HermesJobStatus.Queued, jobId = "job-1"),
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 1_000L),
                ),
            ),
            reducer.reduce(
                polling(failures = 2),
                JobEvent.PollReturned(HermesJobStatus.Queued, null, null, null),
            ),
        )
    }

    @Test
    fun `poll returned running resets failures and reschedules`() {
        assertEquals(
            Transition(
                polling(failures = 0),
                listOf(
                    JobEffect.PersistActive(status = HermesJobStatus.Running, jobId = "job-1"),
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 1_000L),
                ),
            ),
            reducer.reduce(
                polling(failures = 1),
                JobEvent.PollReturned(HermesJobStatus.Running, null, null, null),
            ),
        )
    }

    @Test
    fun `poll returned succeeded with answer completes and announces, not ack-gated`() {
        val kind = TerminalKind.Complete(answer = "42", serverElapsedMs = 1234L)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = false),
                    JobEffect.AnnounceCompletion("job-1"),
                ),
            ),
            reducer.reduce(
                polling(ack = false),
                JobEvent.PollReturned(HermesJobStatus.Succeeded, "42", null, 1234L),
            ),
        )
    }

    @Test
    fun `poll returned succeeded with null answer becomes terminal failure`() {
        val kind = TerminalKind.Failed(HERMES_SUCCEEDED_WITHOUT_ANSWER_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = true),
                ),
            ),
            reducer.reduce(
                polling(ack = true),
                JobEvent.PollReturned(HermesJobStatus.Succeeded, null, null, null),
            ),
        )
    }

    @Test
    fun `poll returned failed with message becomes terminal failure`() {
        val kind = TerminalKind.Failed("upstream 500")
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                polling(ack = false),
                JobEvent.PollReturned(HermesJobStatus.Failed, null, "upstream 500", null),
            ),
        )
    }

    @Test
    fun `poll returned failed with null message falls back to unavailable message`() {
        val kind = TerminalKind.Failed(HERMES_UNAVAILABLE_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = true),
                ),
            ),
            reducer.reduce(
                polling(ack = true),
                JobEvent.PollReturned(HermesJobStatus.Failed, null, null, null),
            ),
        )
    }

    @Test
    fun `poll returned expired with message becomes terminal expired`() {
        val kind = TerminalKind.Expired("ttl exceeded")
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = true),
                ),
            ),
            reducer.reduce(
                polling(ack = true),
                JobEvent.PollReturned(HermesJobStatus.Expired, null, "ttl exceeded", null),
            ),
        )
    }

    @Test
    fun `poll returned expired with null message falls back to unavailable message`() {
        val kind = TerminalKind.Expired(HERMES_UNAVAILABLE_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                polling(ack = false),
                JobEvent.PollReturned(HermesJobStatus.Expired, null, null, null),
            ),
        )
    }

    @Test
    fun `poll returned canceled with message becomes terminal canceled gemini origin`() {
        val kind = TerminalKind.Canceled(CancelOrigin.Gemini, "canceled remotely")
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = true),
                ),
            ),
            reducer.reduce(
                polling(ack = true),
                JobEvent.PollReturned(HermesJobStatus.Canceled, null, "canceled remotely", null),
            ),
        )
    }

    @Test
    fun `poll returned canceled with null message falls back to canceled message`() {
        val kind = TerminalKind.Canceled(CancelOrigin.Gemini, HERMES_CANCELED_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = false),
                ),
            ),
            reducer.reduce(
                polling(ack = false),
                JobEvent.PollReturned(HermesJobStatus.Canceled, null, null, null),
            ),
        )
    }

    @Test
    fun `poll failed terminal becomes terminal failure`() {
        val kind = TerminalKind.Failed("fatal parse error")
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = true),
                ),
            ),
            reducer.reduce(polling(ack = true), JobEvent.PollFailed("fatal parse error", terminal = true)),
        )
    }

    // -- Retryable PollFailed backoff. NOTE: the transcribed reducer's actual
    // formula is delay = (pollRetryDelayMs * 2^(failures-1)).coerceAtMost(pollIntervalMs),
    // i.e. 250, 500, 1000, 1000(capped), 1000(capped) for failures 1,2,3,4,8 -- see
    // the deviation note in the task report; this does NOT match the 250/250/500/1000/1000
    // sequence suggested by the task brief's own Step-2 guidance comment.

    @Test
    fun `poll failed retryable at first failure backs off 250ms`() {
        assertEquals(
            Transition(
                polling(failures = 1),
                listOf(
                    JobEffect.NotifyPollFailure(attempt = 1, message = "boom"),
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 250L),
                ),
            ),
            reducer.reduce(polling(failures = 0), JobEvent.PollFailed("boom", terminal = false)),
        )
    }

    @Test
    fun `poll failed retryable at second failure backs off 500ms`() {
        assertEquals(
            Transition(
                polling(failures = 2),
                listOf(
                    JobEffect.NotifyPollFailure(attempt = 2, message = "boom"),
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 500L),
                ),
            ),
            reducer.reduce(polling(failures = 1), JobEvent.PollFailed("boom", terminal = false)),
        )
    }

    @Test
    fun `poll failed retryable at third failure backs off 1000ms`() {
        assertEquals(
            Transition(
                polling(failures = 3),
                listOf(
                    JobEffect.NotifyPollFailure(attempt = 3, message = "boom"),
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 1_000L),
                ),
            ),
            reducer.reduce(polling(failures = 2), JobEvent.PollFailed("boom", terminal = false)),
        )
    }

    @Test
    fun `poll failed retryable at fourth failure is capped at 1000ms`() {
        assertEquals(
            Transition(
                polling(failures = 4),
                listOf(
                    JobEffect.NotifyPollFailure(attempt = 4, message = "boom"),
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 1_000L),
                ),
            ),
            reducer.reduce(polling(failures = 3), JobEvent.PollFailed("boom", terminal = false)),
        )
    }

    @Test
    fun `poll failed retryable at eighth failure is capped at 1000ms`() {
        assertEquals(
            Transition(
                polling(failures = 8),
                listOf(
                    JobEffect.NotifyPollFailure(attempt = 8, message = "boom"),
                    JobEffect.SchedulePoll(jobId = "job-1", delayMs = 1_000L),
                ),
            ),
            reducer.reduce(polling(failures = 7), JobEvent.PollFailed("boom", terminal = false)),
        )
    }

    @Test
    fun `timed out while polling aborts, persists expired, remote-cancels, announces - in that order`() {
        val kind = TerminalKind.Expired(HERMES_TIMEOUT_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(kind),
                listOf(
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(kind = kind, jobId = "job-1", emitFailureTelemetry = true),
                    JobEffect.CancelRemoteJob("job-1"),
                    JobEffect.AnnounceTerminal(jobId = "job-1", ackDelivered = true),
                ),
            ),
            reducer.reduce(polling(ack = true), JobEvent.TimedOut),
        )
    }

    @Test
    fun `still working due with ack delivered announces still working`() {
        assertEquals(
            Transition(polling(ack = true), listOf(JobEffect.AnnounceStillWorking("job-1"))),
            reducer.reduce(polling(ack = true), JobEvent.StillWorkingDue),
        )
    }

    @Test
    fun `still working due without ack delivered is dropped`() {
        assertEquals(
            Transition(polling(ack = false), emptyList()),
            reducer.reduce(polling(ack = false), JobEvent.StillWorkingDue),
        )
    }

    @Test
    fun `queued ack delivered marks ack delivered with no effects`() {
        assertEquals(
            Transition(polling(ack = true, failures = 2), emptyList()),
            reducer.reduce(polling(ack = false, failures = 2), JobEvent.QueuedAckDelivered),
        )
    }

    @Test
    fun `cancel while polling aborts, persists canceled, remote-cancels — in that order`() {
        val expectedKind = TerminalKind.Canceled(CancelOrigin.User, HERMES_CANCELED_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(expectedKind),
                listOf(
                    JobEffect.AbortInFlight,
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(expectedKind, jobId = "job-1", emitFailureTelemetry = false),
                    JobEffect.CancelRemoteJob("job-1"),
                ),
            ),
            reducer.reduce(polling(), JobEvent.CancelRequested(CancelOrigin.User)),
        )
    }

    @Test
    fun `cancel while polling with gemini origin terminates canceled gemini`() {
        val expectedKind = TerminalKind.Canceled(CancelOrigin.Gemini, HERMES_CANCELED_MESSAGE)
        assertEquals(
            Transition(
                JobState.Terminal(expectedKind),
                listOf(
                    JobEffect.AbortInFlight,
                    JobEffect.CancelStillWorkingTimer,
                    JobEffect.PersistTerminal(expectedKind, jobId = "job-1", emitFailureTelemetry = false),
                    JobEffect.CancelRemoteJob("job-1"),
                ),
            ),
            reducer.reduce(polling(), JobEvent.CancelRequested(CancelOrigin.Gemini)),
        )
    }

    @Test
    fun `polling absorbs unrelated events preserving state`() {
        val state = polling(ack = true, failures = 1)
        val unrelated = listOf(
            JobEvent.Start,
            JobEvent.Resume("job-9", stillWorkingAnnounced = false),
            JobEvent.SubmitReturned("job-9", HermesJobStatus.Queued, null),
            JobEvent.SubmitFailed("boom"),
        )
        for (event in unrelated) {
            assertEquals("event=$event", Transition(state, emptyList()), reducer.reduce(state, event))
        }
    }

    // ---------------------------------------------------------------------
    // Totality + absorption sweeps
    // ---------------------------------------------------------------------

    private fun allEvents(): List<JobEvent> = listOf(
        JobEvent.Start,
        JobEvent.Resume("job-9", stillWorkingAnnounced = false),
        JobEvent.SubmitReturned("job-9", HermesJobStatus.Queued, null),
        JobEvent.SubmitFailed("boom"),
        JobEvent.PollReturned(HermesJobStatus.Running, null, null, null),
        JobEvent.PollFailed("boom", terminal = false),
        JobEvent.TimedOut,
        JobEvent.StillWorkingDue,
        JobEvent.QueuedAckDelivered,
        JobEvent.CancelRequested(CancelOrigin.User),
    )

    @Test
    fun `terminal absorbs every event with no effects`() {
        val terminal = JobState.Terminal(TerminalKind.Failed("done"))
        for (event in allEvents()) {
            assertEquals("event=$event", Transition(terminal, emptyList()), reducer.reduce(terminal, event))
        }
    }

    @Test
    fun `reduce is total over every state`() {
        val states = listOf(
            JobState.Created,
            JobState.Submitting(null),
            JobState.Submitting(CancelOrigin.Gemini),
            polling(ack = false),
            polling(ack = true),
            JobState.Terminal(TerminalKind.Complete("a", null)),
        )
        for (state in states) for (event in allEvents()) {
            reducer.reduce(state, event) // must not throw
        }
    }
}
