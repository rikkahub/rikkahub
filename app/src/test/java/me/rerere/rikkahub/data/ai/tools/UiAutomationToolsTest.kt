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
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.automation.act.AutomationCore
import me.rerere.automation.backend.FakeBackend
import me.rerere.automation.backend.GlobalNav
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.backend.PerformAction
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import me.rerere.automation.cap.Capability
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Decision
import me.rerere.automation.cap.DenyReason
import me.rerere.automation.cap.Lease
import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.common.android.redactAndTruncate
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the per-generation UI-automation tool factory ([getUiAutomationTools]) — the
 * `:app` surface of #187 (`ui_observe`) and #198 slice 8 (the nav act verbs `ui_scroll`/`ui_global`),
 * built on the already-merged `:automation` kernel (#205, #211).
 *
 * The factory is intentionally Android-free (design I10): it takes an [AutomationCore] over a
 * [FakeBackend], a [CapabilityGuard] over a hand-advanced [TrustClock], and a foreground-package
 * supplier — so the whole contract is exercised here with NO Android, NO device, mirroring
 * SpawnToolTest. The real [me.rerere.rikkahub.service.automation.AccessibilityRuntime] backend is the
 * only a11y-API importer; its perform/awaitSettle/tid-walk parity is the slice-12 instrumented suite
 * (NOT a CI gate) — intentionally not faked here.
 *
 * Regressions pinned (the maintainer-mandated CI-runnable surface):
 *  1. ACTIVATION GATING — off / null-guard ⇒ emptyList(); on ⇒ [ui_observe, ui_scroll, ui_global],
 *     needsApproval==false on all.
 *  2. LEASE WIRING / S2 — a revoked OR clock-expired guard returns a denied Text AND the backend is
 *     never hit (authorize runs BEFORE the backend); a healthy guard yields one observe hit.
 *  3. SNAPSHOT → UIMessagePart.Text MAPPING — the returned part is a Text whose table carries
 *     stateSeq/foregroundPkg/screenState + each target, NEVER an Image, never the host package, never
 *     password plaintext (delegates to the proven SnapshotProjector).
 *  4. MALFORMED args ⇒ fail-closed denied Text, backend never hit (design P24).
 *  5–7. ACT PATH (#198 slice 8) — grounding gate (no perform before observe), the happy path (the
 *     right PerformAction recorded, settle ran, Acted re-grounds), S2 (revoked ⇒ never performs),
 *     deny-reason suppression, StaleState on a post-grounding seq bump, and malformed-args fail-closed.
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

    /** The full tool list the factory exposes (ui_observe + the act tools when enabled). */
    private fun allTools(
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

    /** Just the ui_observe tool — the behavior tests below exercise observe in isolation. */
    private fun observeTool(
        assistant: Assistant,
        guard: CapabilityGuard?,
        backend: FakeBackend,
        foregroundPkg: String? = target,
    ) = allTools(assistant, guard, backend, foregroundPkg).first { it.name == UI_OBSERVE_TOOL_NAME }

    // --- 1. ACTIVATION GATING ---

    @Test
    fun `factory returns empty list when ui automation is disabled`() {
        val backend = FakeBackend(targetTree())
        val tools = allTools(
            assistant = Assistant(uiAutomationEnabled = false),
            guard = healthyGuard(),
            backend = backend,
        )
        assertTrue("disabled assistant must expose no automation tools", tools.isEmpty())
    }

    @Test
    fun `factory returns empty list when there is no guard even if enabled`() {
        val backend = FakeBackend(targetTree())
        val tools = allTools(
            assistant = Assistant(uiAutomationEnabled = true),
            guard = null,
            backend = backend,
        )
        assertTrue("a null guard means no authority ⇒ empty surface", tools.isEmpty())
    }

    @Test
    fun `factory exposes the observe plus nav act tools when enabled`() {
        val backend = FakeBackend(targetTree())
        val tools = allTools(
            assistant = Assistant(uiAutomationEnabled = true),
            guard = healthyGuard(),
            backend = backend,
        )
        // #198 slice 8 widened the surface from read-only ui_observe to ui_observe + the nav act
        // verbs. The factory exposes the tools whenever automation is on + a guard exists; per-verb
        // authority is enforced at execute-time by the guard, NOT by tool presence (a guard granting
        // only OBSERVE still surfaces ui_scroll, which then denies on the verb branch).
        assertEquals(
            listOf("ui_observe", "ui_scroll", "ui_global"),
            tools.map { it.name },
        )
        // needsApproval is forced false on every tool: the in-chat approval gate is unreachable while
        // another app is foreground (design constraint 1).
        assertTrue("no automation tool may request approval", tools.none { it.needsApproval })
    }

    // --- 2. LEASE WIRING / S2 (guard called BEFORE the backend) ---

    @Test
    fun `revoked guard yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        val guard = healthyGuard().also { it.revoke() }
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend)

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount) // S2: authorize() ran before AutomationCore.observe()
        val text = parts.single() as UIMessagePart.Text
        assertTrue("denied result must explain the deny", text.text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `expired lease yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        val guard = healthyGuard(expiresAt = fixedNow - 1L) // already past expiry under the trust clock
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend)

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `surface-not-allowed yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        // Default-empty surface = deny-all (S1): observing the foreground app is not authorized.
        val guard = healthyGuard(surface = emptySet())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend)

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `healthy guard captures exactly one snapshot and returns it as text`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend)

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals("exactly one backend capture per observe", 1, backend.snapshotCount)
        assertTrue("the single part must be Text, never Image", parts.single() is UIMessagePart.Text)
    }

    // --- 3. SNAPSHOT → TEXT MAPPING (self-sufficient, leak-free) ---

    @Test
    fun `rendered snapshot carries the table header and never leaks password text or an image`() {
        val backend = FakeBackend(targetTree(stateSeq = 7L))
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend)

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
        ).first { it.name == UI_OBSERVE_TOOL_NAME }

        val text = (runBlocking { tool.execute(buildJsonObject { }) }.single() as UIMessagePart.Text).text

        assertTrue("must surface the host-foreground pause state", text.contains("FOREGROUND_IS_HOST"))
        assertEquals("host snapshot must not leak host content", false, text.contains("chat"))
    }

    // --- 4. MALFORMED args ⇒ fail-closed ---

    @Test
    fun `a non-object argument fails closed without touching the backend`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend)

        // ui_observe takes an empty object; a JsonNull (or any non-object) is malformed and must
        // fail closed at the guard (P24), never reaching AutomationCore.
        val parts = runBlocking { tool.execute(JsonNull) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `a primitive argument fails closed without touching the backend`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend)

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
            val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend)

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
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend)

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
        ).first { it.name == UI_OBSERVE_TOOL_NAME }

        val text = (runBlocking { tool.execute(buildJsonObject { }) }.single() as UIMessagePart.Text).text

        assertTrue("a target mismatch must deny", text.contains("denied", ignoreCase = true))
        assertFalse("the unauthorized app's content must never be disclosed", text.contains("secret balance"))
    }

    // --- 7. ACT PATH (#198 slice 8): ui_scroll + ui_global on the proven act kernel ---
    //
    // These exercise the CI-runnable half of slice 8: the :app act-tool glue over an AutomationCore
    // backed by a FakeBackend + a CapabilityGuard over a hand-advanced TrustClock — no Android, no
    // device, mirroring the ui_observe tests above. The real AccessibilityRuntime.perform/awaitSettle/
    // tid-walk parity (event→stateSeq, node→snapshot projection order, performGlobalAction/
    // ACTION_SCROLL dispatch, online settle timing) needs the a11y runtime and is the slice-12
    // instrumented suite — intentionally NOT covered here.
    //
    // Authority is NEVER in args (I2): the act tools derive verb/sink from the variant, and call
    // core.act(guard, grounded, act) which authorizes internally (S2) and runs guardInFlight (P20) —
    // the tool layer must NOT re-authorize or double-guard (would double-audit / break P25).

    /** A guard whose root surface allows [target] for the act verbs (OBSERVE+SCROLL+GLOBAL) with the
     * GLOBAL_NAV sink in budget, still in-lease — so the act authorize ADMITs. Surface allowing the
     * target is the test's choice (the production root keeps surface empty); here we need an ADMIT to
     * drive the happy path. */
    private fun actGuard(
        surface: Set<String> = setOf(target),
        expiresAt: Long = fixedNow + 60_000L,
        maxSteps: Int = 16,
    ): CapabilityGuard = CapabilityGuard(
        capability = Capability.root(
            sessionId = "conversation-1",
            surface = surface,
            verbs = setOf(Verb.OBSERVE, Verb.SCROLL, Verb.GLOBAL),
            sinkBudget = setOf(Sink.GLOBAL_NAV),
            lease = Lease(expiresAt = expiresAt, maxSteps = maxSteps),
        ),
        clock = clock,
    )

    /** A foreground tree with a single scrollable list target (tid 0), used to ground ui_scroll. */
    private fun scrollableTree(stateSeq: Long = 0L): RawTree = RawTree(
        stateSeq = stateSeq,
        foregroundPkg = target,
        windows = listOf(
            RawWindow(
                pkg = target,
                root = RawNode(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true,
                ),
            ),
        ),
    )

    /** Build the full automation tool set (ui_observe + ui_scroll + ui_global) over [backend]. */
    private fun actTools(
        guard: CapabilityGuard?,
        backend: FakeBackend,
        foregroundPkg: String? = target,
    ) = getUiAutomationTools(
        assistant = Assistant(uiAutomationEnabled = true),
        guard = guard,
        core = AutomationCore(backend),
        foregroundPkg = { foregroundPkg },
    )

    private fun List<me.rerere.ai.core.Tool>.byName(name: String) = first { it.name == name }

    /** ui_scroll args: { selector: { tid: N }, direction: "forward"|"backward" }. */
    private fun scrollArgs(tid: Int, direction: String) = buildJsonObject {
        put("selector", buildJsonObject { put("tid", tid) })
        put("direction", direction)
    }

    // --- 7a. FACTORY GATING — the act tools obey the same default-OFF gate as ui_observe ---

    @Test
    fun `disabled assistant exposes no act tools either`() {
        val backend = FakeBackend(scrollableTree())
        val tools = getUiAutomationTools(
            assistant = Assistant(uiAutomationEnabled = false),
            guard = actGuard(),
            core = AutomationCore(backend),
            foregroundPkg = { target },
        )
        assertTrue("disabled assistant must expose no automation tools at all", tools.isEmpty())
    }

    // (Presence + needsApproval==false for all three tools is asserted by
    // `factory exposes the observe plus nav act tools when enabled` above.)

    // --- 7b. GROUNDING — an act before any ui_observe must not touch the backend ---

    @Test
    fun `ui_scroll before any observe returns a re-observe text and never performs`() {
        val backend = FakeBackend(scrollableTree())
        val scroll = actTools(actGuard(), backend).byName(UI_SCROLL_TOOL_NAME)

        val parts = runBlocking { scroll.execute(scrollArgs(0, "forward")) }

        assertTrue("no perform without a grounded snapshot", backend.performed.isEmpty())
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("must tell the model to observe first", text.contains("observe", ignoreCase = true))
    }

    @Test
    fun `ui_global before any observe returns a re-observe text and never performs`() {
        val backend = FakeBackend(scrollableTree())
        val global = actTools(actGuard(), backend).byName(UI_GLOBAL_TOOL_NAME)

        val parts = runBlocking { global.execute(buildJsonObject { put("direction", "back") }) }

        assertTrue("no perform without a grounded snapshot", backend.performed.isEmpty())
        assertTrue(
            "must tell the model to observe first",
            (parts.single() as UIMessagePart.Text).text.contains("observe", ignoreCase = true),
        )
    }

    // --- 7c. HAPPY PATH — observe grounds, then the act dispatches the right PerformAction ---

    @Test
    fun `ui_scroll after observe performs one node scroll-forward, settles, and re-grounds`() {
        val backend = FakeBackend(scrollableTree(stateSeq = 5L))
        val tools = actTools(actGuard(), backend)
        val observe = tools.byName(UI_OBSERVE_TOOL_NAME)
        val scroll = tools.byName(UI_SCROLL_TOOL_NAME)

        // 1. observe grounds the snapshot (the act tools read the last grounded snapshot).
        runBlocking { observe.execute(buildJsonObject { }) }
        val settleBefore = backend.settleCount

        // 2. ui_scroll(ByTid 0 / forward) dispatches exactly one node scroll on the grounded seq.
        val parts = runBlocking { scroll.execute(scrollArgs(0, "forward")) }

        assertEquals("exactly one perform", 1, backend.performed.size)
        assertEquals(
            "the dispatched action must be a node scroll-forward on the grounded (stateSeq=5, tid=0)",
            PerformAction.Node(stateSeq = 5L, tid = 0, kind = NodeActionKind.SCROLL_FORWARD),
            backend.performed.single(),
        )
        assertEquals("settle must run exactly once for the act", settleBefore + 1, backend.settleCount)
        // ActOutcome.Acted re-grounds: the returned Text is the FRESH re-rendered snapshot (FakeBackend
        // bumps stateSeq on perform, so the re-snapshot reads stateSeq=6).
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("the result must be the fresh re-rendered snapshot", text.contains("stateSeq=6"))
    }

    @Test
    fun `ui_scroll backward maps to SCROLL_BACKWARD`() {
        val backend = FakeBackend(scrollableTree(stateSeq = 2L))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        runBlocking { tools.byName(UI_SCROLL_TOOL_NAME).execute(scrollArgs(0, "backward")) }

        assertEquals(
            PerformAction.Node(stateSeq = 2L, tid = 0, kind = NodeActionKind.SCROLL_BACKWARD),
            backend.performed.single(),
        )
    }

    @Test
    fun `ui_global back performs a global BACK and re-grounds`() {
        val backend = FakeBackend(scrollableTree(stateSeq = 4L))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        val parts = runBlocking {
            tools.byName(UI_GLOBAL_TOOL_NAME).execute(buildJsonObject { put("direction", "back") })
        }

        assertEquals(
            "ui_global(back) must dispatch a global BACK",
            PerformAction.Global(GlobalNav.BACK),
            backend.performed.single(),
        )
        assertTrue(
            "the result must be the fresh re-rendered snapshot",
            (parts.single() as UIMessagePart.Text).text.contains("stateSeq=5"),
        )
    }

    @Test
    fun `ui_global home and recents map to their nav targets`() {
        val homeBackend = FakeBackend(scrollableTree())
        val homeTools = actTools(actGuard(), homeBackend)
        runBlocking { homeTools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        runBlocking { homeTools.byName(UI_GLOBAL_TOOL_NAME).execute(buildJsonObject { put("direction", "home") }) }
        assertEquals(PerformAction.Global(GlobalNav.HOME), homeBackend.performed.single())

        val recentsBackend = FakeBackend(scrollableTree())
        val recentsTools = actTools(actGuard(), recentsBackend)
        runBlocking { recentsTools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        runBlocking { recentsTools.byName(UI_GLOBAL_TOOL_NAME).execute(buildJsonObject { put("direction", "recents") }) }
        assertEquals(PerformAction.Global(GlobalNav.RECENTS), recentsBackend.performed.single())
    }

    // --- 7d. S2 / DENY-LEAK — a revoked guard denies before the backend AND never leaks the reason ---

    @Test
    fun `revoked guard denies ui_scroll before the backend and never leaks the deny reason`() {
        val backend = FakeBackend(scrollableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        // Ground first (so the deny can ONLY come from the act's authorize, not the missing snapshot).
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val performsAfterObserve = backend.performed.size

        guard.revoke()
        val parts = runBlocking { tools.byName(UI_SCROLL_TOOL_NAME).execute(scrollArgs(0, "forward")) }

        // core.act authorizes BEFORE the backend (S2): a revoked guard never reaches perform.
        assertEquals("a revoked guard must never perform", performsAfterObserve, backend.performed.size)
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("denied result must explain the refusal", text.contains("denied", ignoreCase = true))
        // The internal ActDenyReason enum (GUARD/AMBIGUOUS/REVOKED) must NEVER reach the model.
        assertFalse("must not leak the deny reason", text.contains("GUARD"))
        assertFalse("must not leak the deny reason", text.contains("REVOKED"))
        assertFalse("must not leak the deny reason", text.contains("AMBIGUOUS"))
    }

    // --- 7e. STALE — grounding moved under the act (seq bump) ⇒ StaleState ⇒ vague re-observe text ---

    @Test
    fun `a stateSeq bump after grounding makes ui_scroll stale and never performs`() {
        val backend = FakeBackend(scrollableTree(stateSeq = 0L))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        // The screen changed under us (a window-content event) AFTER the grounding snapshot — the act
        // assert must reject the now-stale grounding (seq mismatch) and never dispatch.
        backend.injectTransition()
        val parts = runBlocking { tools.byName(UI_SCROLL_TOOL_NAME).execute(scrollArgs(0, "forward")) }

        assertTrue("a stale grounding must never perform", backend.performed.isEmpty())
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("stale result must steer the model to re-observe", text.contains("observe", ignoreCase = true))
        assertFalse("must not leak the deny reason", text.contains("STALE"))
    }

    // --- 7f. MALFORMED — non-object args fail closed, never building an Act, never performing ---

    @Test
    fun `ui_scroll with a non-object argument fails closed without performing`() {
        val backend = FakeBackend(scrollableTree())
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        val parts = runBlocking { tools.byName(UI_SCROLL_TOOL_NAME).execute(JsonNull) }

        assertTrue("malformed args must never perform", backend.performed.isEmpty())
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `ui_global with a primitive argument fails closed without performing`() {
        val backend = FakeBackend(scrollableTree())
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        val parts = runBlocking { tools.byName(UI_GLOBAL_TOOL_NAME).execute(JsonPrimitive("back")) }

        assertTrue("malformed args must never perform", backend.performed.isEmpty())
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    // --- 7g. MALFORMED args are AUDITED (P25): a malformed act is an admission decision and must
    // leave exactly one redacted DENY ledger entry, exactly as ui_observe does. The unfixed act tools
    // short-circuited with ACT_DENIED_MESSAGE BEFORE guard.authorize, so a prompt-injection-shaped
    // garbage act left NO trace in the audit trail — these pin that gap closed.

    @Test
    fun `a malformed ui_scroll writes exactly one redacted DENY audit entry`() {
        val backend = FakeBackend(scrollableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        // Ground first; the observe's own ADMIT is the only entry so far. Measuring the DELTA isolates
        // the act's audit from the grounding observe's.
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val entriesAfterObserve = guard.audit.entries().size

        // A non-object arg is malformed: the act must be refused AND audited (one new DENY).
        runBlocking { tools.byName(UI_SCROLL_TOOL_NAME).execute(JsonNull) }

        val newEntries = guard.audit.entries().drop(entriesAfterObserve)
        assertEquals("a malformed act must append exactly one ledger entry", 1, newEntries.size)
        val entry = newEntries.single()
        assertEquals("the malformed act decision must be DENY", Decision.DENY, entry.decision)
        assertEquals("the fail-closed reason must be MALFORMED", DenyReason.MALFORMED, entry.reason)
        assertEquals("the ledger must record the attempted verb", Verb.SCROLL, entry.verb)
        // P25: the raw JSON is NEVER stored — only length-only redacted metadata (proven in the
        // kernel's redaction tests; here we just confirm the act path stores the redacted form, not raw).
        assertEquals(redactAndTruncate(JsonNull.toString()), entry.redactedArgs)
        assertTrue("malformed act must still never perform", backend.performed.isEmpty())
    }

    @Test
    fun `a malformed ui_scroll selector writes one DENY audit entry`() {
        val backend = FakeBackend(scrollableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val entriesAfterObserve = guard.audit.entries().size

        // A well-formed object but an unparseable selector (empty selector object) is still malformed.
        runBlocking {
            tools.byName(UI_SCROLL_TOOL_NAME).execute(
                buildJsonObject {
                    put("selector", buildJsonObject { })
                    put("direction", "forward")
                },
            )
        }

        val newEntries = guard.audit.entries().drop(entriesAfterObserve)
        assertEquals("an unparseable selector must append one ledger entry", 1, newEntries.size)
        assertEquals(DenyReason.MALFORMED, newEntries.single().reason)
        assertTrue(backend.performed.isEmpty())
    }

    @Test
    fun `a malformed ui_global writes exactly one redacted DENY audit entry with the GLOBAL verb`() {
        val backend = FakeBackend(scrollableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val entriesAfterObserve = guard.audit.entries().size

        // An unknown direction is malformed for ui_global.
        runBlocking {
            tools.byName(UI_GLOBAL_TOOL_NAME).execute(buildJsonObject { put("direction", "sideways") })
        }

        val newEntries = guard.audit.entries().drop(entriesAfterObserve)
        assertEquals("a malformed global act must append exactly one ledger entry", 1, newEntries.size)
        val entry = newEntries.single()
        assertEquals(Decision.DENY, entry.decision)
        assertEquals(DenyReason.MALFORMED, entry.reason)
        assertEquals("the ledger must record GLOBAL for a ui_global attempt", Verb.GLOBAL, entry.verb)
        assertTrue(backend.performed.isEmpty())
    }
}
