package me.rerere.rikkahub.data.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Regression for #88: a file export must NOT be cancelled by leaving the screen.
 *
 * The bug: [rememberExporter] passed `rememberCoroutineScope()` to [ExporterState], so
 * [ExporterState.writeToUri] launched the write as a CHILD of the composition scope. Navigating
 * away mid-export cancelled that scope, the write never flushed, and the user's chosen file was left
 * empty — silent data loss with no error surfaced.
 *
 * These tests drive the REAL production launch seam [launchOwnedWrite] — the exact path
 * `writeToUri` now takes — to pin the scope-ownership invariant: a write launched on an
 * AppScope-like owner survives caller cancellation, and a write launched on a caller-child owner
 * does not. Revert `rememberExporter` to `rememberCoroutineScope()` and the owner it hands to
 * `launchOwnedWrite` becomes a caller-child scope — the behaviour the first test proves is lossy.
 *
 * Runs on the JVM (testDebugUnitTest) without a ContentResolver or Android: the Android-coupled
 * `openOutputStream` is supplied here as a plain [ByteArrayOutputStream] write action, while the
 * scope-launch decision under test stays in production code.
 */
class ExportHooksOwnershipTest {

    private val content = "{\"hello\":\"world\"}".toByteArray()

    /**
     * Pre-fix model: the owner IS the caller scope (what `rememberCoroutineScope()` yields). On
     * navigation-away the composition scope is already cancelled by the time the write is launched
     * onto it, so the production launcher schedules the IO onto a dead scope and the file stays
     * empty. Cancelling before the launch (rather than racing a cancel against it) deterministically
     * models disposal-then-write and pins the loss to the SCOPE choice, not to dispatcher timing.
     */
    @Test
    fun `write owned by the caller scope is lost when that scope is cancelled`() = runBlocking {
        val caller = CoroutineScope(Job() + Dispatchers.Unconfined)
        val sink = ByteArrayOutputStream()

        caller.cancel() // user navigated away: the composition scope is already dead
        val writeJob = launchOwnedWrite(caller, Dispatchers.Unconfined) { sink.write(content) }
        writeJob.join()

        assertEquals("caller-owned write must drop on caller cancellation", 0, sink.size())
    }

    /**
     * Post-fix model: the owner is an independent AppScope stand-in (SupervisorJob, outlives
     * composition). Cancelling the caller that merely triggered the action does not touch it, so the
     * write launched onto the live owner completes.
     */
    @Test
    fun `write owned by AppScope survives caller cancellation`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) // AppScope stand-in
        val caller = CoroutineScope(Job() + Dispatchers.Unconfined)
        val sink = ByteArrayOutputStream()

        caller.cancel() // user navigated away
        // writeToUri launches on the injected AppScope; the caller only triggered it.
        val writeJob = launchOwnedWrite(owner, Dispatchers.Unconfined) { sink.write(content) }
        writeJob.join()

        assertArrayEquals(
            "AppScope-owned write must complete despite caller cancellation",
            content,
            sink.toByteArray()
        )
    }
}
