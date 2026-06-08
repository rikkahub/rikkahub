package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.automation.act.AutomationCore
import me.rerere.automation.backend.FakeBackend
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import me.rerere.automation.cap.Capability
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Lease
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the per-generation `ui_observe` tool factory ([getUiAutomationTools]) — the
 * read-only v1 `:app` surface of #187, built on the already-merged `:automation` kernel (#205).
 *
 * The factory is intentionally Android-free (design I10): it takes an [AutomationCore] over a
 * [FakeBackend], a [CapabilityGuard] over a hand-advanced [TrustClock], and a foreground-package
 * supplier — so the whole contract is exercised here with NO Android, NO device, mirroring
 * SpawnToolTest. The real [me.rerere.rikkahub.service.automation.AccessibilityRuntime] backend is
 * the only a11y-API importer and is covered by an instrumented contract test (NOT a CI gate).
 *
 * Four regressions pinned (the maintainer-mandated CI-runnable surface):
 *  1. ACTIVATION GATING — off ⇒ emptyList(); on ⇒ exactly [ui_observe], needsApproval==false.
 *  2. LEASE WIRING / S2 — a revoked OR clock-expired guard returns a denied Text AND the backend is
 *     never hit (authorize() runs BEFORE AutomationCore.observe()); a healthy guard yields one hit.
 *  3. SNAPSHOT → UIMessagePart.Text MAPPING — the single returned part is a Text whose table carries
 *     stateSeq/foregroundPkg/screenState + each target, NEVER an Image, never the host package, never
 *     password plaintext (delegates to the proven SnapshotProjector).
 *  4. MALFORMED args ⇒ fail-closed denied Text, backend never hit (design P24).
 */
class UiAutomationToolsTest {

    private val target = "com.example.target"
    private val fixedNow = 1_000L
    private val clock = TrustClock { fixedNow }

    /** A guard whose root surface allows [target] for OBSERVE and is still in-lease. */
    private fun healthyGuard(
        surface: Set<String> = setOf(target),
        expiresAt: Long = fixedNow + 60_000L,
        maxSteps: Int = 16,
    ): CapabilityGuard = CapabilityGuard(
        capability = Capability.root(
            sessionId = "conversation-1",
            surface = surface,
            verbs = setOf(Verb.OBSERVE),
            lease = Lease(expiresAt = expiresAt, maxSteps = maxSteps),
        ),
        clock = clock,
    )

    /** Foreground app + a clickable button and a password field, to assert masking in projection. */
    private fun targetTree(stateSeq: Long = 0L): RawTree = RawTree(
        stateSeq = stateSeq,
        foregroundPkg = target,
        windows = listOf(
            RawWindow(
                pkg = target,
                root = RawNode(
                    className = "android.widget.FrameLayout",
                    children = listOf(
                        RawNode(
                            text = "Sign in",
                            className = "android.widget.Button",
                            clickable = true,
                        ),
                        RawNode(
                            text = "hunter2",
                            className = "android.widget.EditText",
                            editable = true,
                            password = true,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun observeTool(
        assistant: Assistant,
        guard: CapabilityGuard?,
        backend: FakeBackend,
        foregroundPkg: String? = target,
    ) = getUiAutomationTools(
        assistant = assistant,
        guard = guard,
        core = AutomationCore(backend),
        foregroundPkg = { foregroundPkg },
    )

    // --- 1. ACTIVATION GATING ---

    @Test
    fun `factory returns empty list when ui automation is disabled`() {
        val backend = FakeBackend(targetTree())
        val tools = observeTool(
            assistant = Assistant(uiAutomationEnabled = false),
            guard = healthyGuard(),
            backend = backend,
        )
        assertTrue("disabled assistant must expose no automation tools", tools.isEmpty())
    }

    @Test
    fun `factory returns empty list when there is no guard even if enabled`() {
        val backend = FakeBackend(targetTree())
        val tools = observeTool(
            assistant = Assistant(uiAutomationEnabled = true),
            guard = null,
            backend = backend,
        )
        assertTrue("a null guard means no authority ⇒ empty surface", tools.isEmpty())
    }

    @Test
    fun `factory exposes exactly the read-only ui_observe tool when enabled`() {
        val backend = FakeBackend(targetTree())
        val tools = observeTool(
            assistant = Assistant(uiAutomationEnabled = true),
            guard = healthyGuard(),
            backend = backend,
        )
        assertEquals(listOf("ui_observe"), tools.map { it.name })
        // needsApproval is forced false: the in-chat approval gate is unreachable while another app
        // is foreground (design constraint 1).
        assertFalse("ui_observe must not request approval", tools.single().needsApproval)
    }

    // --- 2. LEASE WIRING / S2 (guard called BEFORE the backend) ---

    @Test
    fun `revoked guard yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        val guard = healthyGuard().also { it.revoke() }
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount) // S2: authorize() ran before AutomationCore.observe()
        val text = parts.single() as UIMessagePart.Text
        assertTrue("denied result must explain the deny", text.text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `expired lease yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        val guard = healthyGuard(expiresAt = fixedNow - 1L) // already past expiry under the trust clock
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `surface-not-allowed yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        // Default-empty surface = deny-all (S1): observing the foreground app is not authorized.
        val guard = healthyGuard(surface = emptySet())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `healthy guard captures exactly one snapshot and returns it as text`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals("exactly one backend capture per observe", 1, backend.snapshotCount)
        assertTrue("the single part must be Text, never Image", parts.single() is UIMessagePart.Text)
    }

    // --- 3. SNAPSHOT → TEXT MAPPING (self-sufficient, leak-free) ---

    @Test
    fun `rendered snapshot carries the table header and never leaks password text or an image`() {
        val backend = FakeBackend(targetTree(stateSeq = 7L))
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        // Mandatory text channel: providers drop tool-output images (gate A1), so the snapshot must be
        // a single self-sufficient Text and never an Image.
        assertEquals(1, parts.size)
        val text = (parts.single() as UIMessagePart.Text).text

        assertTrue("must report stateSeq", text.contains("7"))
        assertTrue("must report the foreground package", text.contains(target))
        assertTrue("must report a screen state", text.contains("READY"))
        assertTrue("the clickable button label must appear", text.contains("Sign in"))
        // The password field's plaintext must NEVER reach the model (SnapshotProjector masks it).
        assertFalse("password plaintext must never be rendered", text.contains("hunter2"))
        assertFalse(
            "host package must never appear in the projection",
            text.contains("me.rerere.rikkahub"),
        )
        assertTrue("no part may be an image", parts.none { it is UIMessagePart.Image })
    }

    @Test
    fun `host-foreground snapshot renders the pause state with no targets`() {
        // When rikkahub itself is foreground, the projector returns FOREGROUND_IS_HOST + no targets:
        // the agent must pause/re-ground rather than act on host UI (P12). Surface must include the
        // host so the guard admits the observe (we are explicitly observing our own foreground here).
        val hostTree = RawTree(
            stateSeq = 3L,
            foregroundPkg = "me.rerere.rikkahub",
            windows = listOf(RawWindow(pkg = "me.rerere.rikkahub", root = RawNode(text = "chat"))),
        )
        val backend = FakeBackend(hostTree)
        val guard = healthyGuard(surface = setOf("me.rerere.rikkahub"))
        val tool = getUiAutomationTools(
            assistant = Assistant(uiAutomationEnabled = true),
            guard = guard,
            core = AutomationCore(backend),
            foregroundPkg = { "me.rerere.rikkahub" },
        ).single()

        val text = (runBlocking { tool.execute(buildJsonObject { }) }.single() as UIMessagePart.Text).text

        assertTrue("must surface the host-foreground pause state", text.contains("FOREGROUND_IS_HOST"))
        assertEquals("host snapshot must not leak host content", false, text.contains("chat"))
    }

    // --- 4. MALFORMED args ⇒ fail-closed ---

    @Test
    fun `a non-object argument fails closed without touching the backend`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend).single()

        // ui_observe takes an empty object; a JsonNull (or any non-object) is malformed and must
        // fail closed at the guard (P24), never reaching AutomationCore.
        val parts = runBlocking { tool.execute(JsonNull) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `a primitive argument fails closed without touching the backend`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend).single()

        val parts = runBlocking { tool.execute(JsonPrimitive("not-an-object")) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    // --- 5. I9: revoke() cancels an in-flight observe (gate finding "revoke cancels in-flight") ---

    /**
     * THE finding-3 regression. The unfixed factory called `core.observe()` directly after an ADMIT,
     * so a kill-switch `revoke()` (which only denies FUTURE authorize) could not abort a capture
     * already parked in the backend. The fix routes the capture through
     * `CapabilityGuard.guardInFlight`, so revoking the guard while the observe is parked cancels the
     * owning coroutine. Asserts the capture coroutine is cancelled rather than completing with a
     * snapshot.
     */
    @Test
    fun `revoke cancels an in-flight observe instead of letting it complete`() {
        runBlocking {
            val backend = FakeBackend(targetTree())
            backend.armGate() // the next snapshotRawTree() parks until the owning coroutine is cancelled
            val guard = healthyGuard()
            val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend).single()

            val started = CompletableDeferred<Unit>()
            val job: Job = launch(Dispatchers.Default) {
                started.complete(Unit)
                tool.execute(buildJsonObject { })
            }
            started.await()
            // Give execute() ample scheduler turns to pass authorize() and park inside the backend
            // gate (snapshotCount stays 0 until the gated await returns). The gate guarantees it
            // cannot finish on its own, so this only needs to outlast the launch→authorize→park hop.
            repeat(50) { yield() }
            check(backend.snapshotCount == 0) { "precondition: capture must still be parked at the gate" }

            guard.revoke() // the kill-switch trips while the capture is in flight

            // On the FIXED factory, revoke() cancels the parked capture via the owning Job, so the
            // join completes promptly. On the UNFIXED factory (direct core.observe(), nothing
            // registered on the token) revoke() cannot reach the parked coroutine and the join would
            // hang forever — the bounded wait turns that into a deterministic failure, not a flaky
            // CI timeout.
            withTimeout(5_000) { job.join() }

            assertTrue("revoke must cancel the parked capture, not let it finish", job.isCancelled)
            assertEquals("the gated snapshot must never complete", 0, backend.snapshotCount)
        }
    }

    /**
     * Companion to finding 3: a `revoke()` that fires AFTER authorize() admits but BEFORE the backend
     * call must deny (onAlreadyRevoked), never capture — closing the authorize→observe window. Modeled
     * by revoking the guard, then invoking execute: authorize() denies on REVOKED here, but the
     * guarded-in-flight re-check is the production safety net for the same-instant race.
     */
    @Test
    fun `a revoke between authorize and observe denies without capturing`() {
        val backend = FakeBackend(targetTree())
        val guard = healthyGuard()
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend).single()

        guard.revoke()
        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals("a revoked guard must never reach the backend", 0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    // --- 6. TOCTOU: the captured snapshot is bound to the authorized target ---

    /**
     * THE finding-4 regression. The factory reads the foreground package once to authorize, then the
     * backend captures whatever is foreground at capture time. If the foreground app switched in
     * between, the unfixed factory returned a snapshot for an app the guard never admitted —
     * unauthorized screen disclosure. The fix re-asserts `snapshot.foregroundPkg == authorizedPkg`
     * after capture and denies on mismatch. Here the guard authorizes `target` (the foreground at
     * authorize-time) but the backend's tree reports a DIFFERENT foreground package.
     */
    @Test
    fun `a foreground switch between authorize and capture is denied not disclosed`() {
        // Authorized target is `target`; surface allows it AND the app it switched to, so the deny can
        // ONLY come from the captured-target binding, not from the surface check.
        val switchedTo = "com.evil.other"
        val capturedAfterSwitch = RawTree(
            stateSeq = 9L,
            foregroundPkg = switchedTo, // the app changed after we authorized `target`
            windows = listOf(
                RawWindow(
                    pkg = switchedTo,
                    root = RawNode(text = "secret balance 1234", className = "android.widget.TextView"),
                ),
            ),
        )
        val backend = FakeBackend(capturedAfterSwitch)
        val guard = healthyGuard(surface = setOf(target, switchedTo))
        val tool = getUiAutomationTools(
            assistant = Assistant(uiAutomationEnabled = true),
            guard = guard,
            core = AutomationCore(backend),
            foregroundPkg = { target }, // authorize-time foreground is the authorized one
        ).single()

        val text = (runBlocking { tool.execute(buildJsonObject { }) }.single() as UIMessagePart.Text).text

        assertTrue("a target mismatch must deny", text.contains("denied", ignoreCase = true))
        assertFalse("the unauthorized app's content must never be disclosed", text.contains("secret balance"))
    }
}
