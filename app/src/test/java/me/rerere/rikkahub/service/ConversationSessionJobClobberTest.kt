package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Review mustFix regression (PR #266 gate): the OLD generation job's invokeOnCompletion handler
 * used to write `_generationJob.value = null` unconditionally. A superseded job can finish
 * completing AFTER the replacement job is already registered (cancellation only transitions it
 * to "cancelling" while children are still winding down), so the late handler clobbered the
 * replacement registration — stopGeneration() then read null and the new generation became
 * unstoppable. Completion cleanup must only clear the job's OWN registration.
 */
class ConversationSessionJobClobberTest {

    private fun session(): ConversationSession = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(id = Uuid.random()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = {},
    )

    // OPT-IN: this test deliberately IMPLEMENTS Job (via delegation) to model a generation coroutine
    // whose cancel() doesn't complete synchronously — exactly the inheritance the marker guards. The
    // override is the point of the test, so opt in rather than restructure away the scenario.
    @OptIn(InternalForInheritanceCoroutinesApi::class)
    @Test
    fun `late completion of a superseded job must not clobber the replacement`() {
        val s = session()

        // A job whose cancel() does not complete it synchronously — the real-world shape of a
        // generation coroutine winding down finally-blocks after cancellation. (A plain child
        // Job() can't model this: parent.cancel() propagates and completes it immediately.)
        val backing = Job()
        val oldJob = object : Job by backing {
            override fun cancel(cause: CancellationException?) {
                // still winding down — completion arrives later via `backing`
            }
        }
        s.setJob(oldJob)

        val replacement = Job()
        s.setJob(replacement) // cancels oldJob; it stays incomplete (winding down)

        backing.cancel() // old job NOW completes — its handler runs after the replacement is live

        assertSame(
            "a superseded job's completion handler must not clear the replacement job",
            replacement,
            s.getJob(),
        )
    }

    @Test
    fun `a job that completes while still current clears its own registration`() {
        val s = session()

        val job = Job()
        s.setJob(job)
        job.cancel()

        assertSame(null, s.getJob())
    }
}
