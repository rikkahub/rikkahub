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
import me.rerere.automation.act.AlwaysConfirm
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
import me.rerere.automation.cap.Surface
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.common.android.redactAndTruncate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            surface = Surface.Scoped(surface),
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

    private fun mixedWindowTree(stateSeq: Long = 0L): RawTree = RawTree(
        stateSeq = stateSeq,
        foregroundPkg = target,
        windows = listOf(
            RawWindow(
                pkg = target,
                root = RawNode(
                    text = "granted-text",
                    contentDescription = "grant-key",
                    resourceId = "com.example.target:id/granted",
                    className = "android.widget.EditText",
                    editable = true,
                    visible = true,
                    hasArea = true,
                ),
            ),
            RawWindow(
                pkg = "com.example.foreign-allowed",
                root = RawNode(
                    text = "FORBIDDEN_TEXT",
                    contentDescription = "forbidden-key",
                    resourceId = "com.example.foreign-allowed:id/forbidden",
                    className = "android.widget.EditText",
                    editable = true,
                    visible = true,
                    hasArea = true,
                ),
            ),
            RawWindow(
                pkg = "com.example.foreign",
                systemWindow = true,
                root = RawNode(
                    text = "SYSTEM_DIALOG_TEXT",
                    contentDescription = "system-key",
                    resourceId = "com.android.packageinstaller:id/system_dialog",
                    className = "android.widget.EditText",
                    editable = true,
                    visible = true,
                    hasArea = true,
                ),
            ),
        ),
    )

    /**
     * The full tool list the factory exposes (ui_observe + the act tools when a guard is present).
     * Activation is now a function of the guard alone — `ChatService` mints one only for an active,
     * usable grant (standing-grant-gated-by-switch OR a per-run grant), so the factory takes no
     * assistant (finding 1).
     */
    private fun allTools(
        guard: CapabilityGuard?,
        backend: FakeBackend,
        foregroundPkg: String? = target,
    ) = getUiAutomationTools(
        guard = guard,
        core = AutomationCore(backend),
        foregroundPkg = { foregroundPkg },
        // #198 slice 11: AlwaysConfirm so the slice-8/9/10 assertions are unchanged — every act here is
        // non-dangerous (scroll/global/set_text, or a benign "OK" tap), so the confirm is never consulted.
        confirm = AlwaysConfirm,
    )

    /** Just the ui_observe tool — the behavior tests below exercise observe in isolation. */
    private fun observeTool(
        guard: CapabilityGuard?,
        backend: FakeBackend,
        foregroundPkg: String? = target,
    ) = allTools(guard, backend, foregroundPkg).first { it.name == UI_OBSERVE_TOOL_NAME }

    // --- 1. ACTIVATION GATING ---

    @Test
    fun `factory returns empty list when there is no guard`() {
        val backend = FakeBackend(targetTree())
        val tools = allTools(
            guard = null,
            backend = backend,
        )
        assertTrue("a null guard means no authority ⇒ empty surface", tools.isEmpty())
    }

    @Test
    fun `factory exposes the tools for any guard ChatService already minted`() {
        // The guard is the single source of truth for activation here — its mere existence means
        // ChatService already applied the master switch and derived usable authority, so the tools
        // MUST surface. Gating on uiAutomationEnabled in addition to the guard would split that source
        // of truth and could hide tools for an otherwise valid guard.
        val backend = FakeBackend(targetTree())
        val tools = allTools(
            guard = healthyGuard(),
            backend = backend,
        )
        assertTrue(
            "a minted guard must surface the automation tools because activation already happened",
            tools.isNotEmpty(),
        )
    }

    @Test
    fun `factory exposes the observe plus nav act tools when a guard is present`() {
        val backend = FakeBackend(targetTree())
        val tools = allTools(
            guard = healthyGuard(),
            backend = backend,
        )
        // #198 slice 8 widened the surface from read-only ui_observe to ui_observe + the nav act
        // verbs; slice 9 adds the input sink ui_set_text. The factory exposes the tools whenever
        // automation is on + a guard exists; per-verb authority is enforced at execute-time by the
        // guard, NOT by tool presence (a guard granting only OBSERVE still surfaces ui_scroll, which
        // then denies on the verb branch).
        assertEquals(
            listOf("ui_observe", "ui_scroll", "ui_global", "ui_set_text", "ui_tap"),
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
        val tool = observeTool(guard, backend)

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount) // S2: authorize() ran before AutomationCore.observe()
        val text = parts.single() as UIMessagePart.Text
        assertTrue("denied result must explain the deny", text.text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `expired lease yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        val guard = healthyGuard(expiresAt = fixedNow - 1L) // already past expiry under the trust clock
        val tool = observeTool(guard, backend)

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `surface-not-allowed yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        // Default-empty surface = deny-all (S1): observing the foreground app is not authorized.
        val guard = healthyGuard(surface = emptySet())
        val tool = observeTool(guard, backend)

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `healthy guard captures exactly one snapshot and returns it as text`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(healthyGuard(), backend)

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals("exactly one backend capture per observe", 1, backend.snapshotCount)
        assertTrue("the single part must be Text, never Image", parts.single() is UIMessagePart.Text)
    }

    // --- 3. SNAPSHOT → TEXT MAPPING (self-sufficient, leak-free) ---

    @Test
    fun `rendered snapshot carries the table header and never leaks password text or an image`() {
        val backend = FakeBackend(targetTree(stateSeq = 7L))
        val tool = observeTool(healthyGuard(), backend)

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
        // The eyes-open binding internals (windowId / structuralPath / structuralFingerprint) are NEVER
        // model-facing — the renderer emits only tid/role/flags/text/form/key. The structural
        // fingerprint is a 64-char lowercase-hex SHA-256, so its absence is the unambiguous proof that
        // no binding internal leaked into the rendered table (review round 3 should-fix).
        assertFalse("the structural fingerprint must never render", Regex("[0-9a-f]{64}").containsMatchIn(text))
        assertFalse("the windowId axis label must never render", text.contains("windowId"))
        assertFalse("the structuralPath axis must never render", text.contains("structuralPath"))
        assertTrue("no part may be an image", parts.none { it is UIMessagePart.Image })
    }

    @Test
    fun `ui_observe drops foreign app content but keeps a system dialog in rendered output`() {
        val backend = FakeBackend(mixedWindowTree(stateSeq = 12L))
        val text = runBlocking {
            allTools(healthyGuard(surface = setOf(target)), backend)
                .first { it.name == UI_OBSERVE_TOOL_NAME }
                .execute(buildJsonObject { })
                .let { it.single() as UIMessagePart.Text }
                .text
        }

        // The granted window's editable field renders its value: a UI-automation agent must read back
        // field contents to verify its own input. Only a password field is masked (none here).
        assertTrue("granted editable value must render", text.contains("granted-text"))
        assertTrue("granted form key must remain visible", text.contains("form=com.example.target:id/granted"))

        assertFalse("foreign text must never render", text.contains("FORBIDDEN_TEXT"))
        assertFalse("foreign semantic key must never render", text.contains("forbidden-key"))
        assertFalse("foreign form key must never render", text.contains("form=com.example.foreign-allowed:id/forbidden"))
        assertFalse("foreign view id must never render", text.contains("com.example.foreign-allowed:id/forbidden"))

        // The system dialog stays observable + addressable; an editable system node renders its value
        // too (it is not a password) — the allowlist, not the editable flag, is what gates disclosure.
        assertTrue("system dialog editable value must render", text.contains("SYSTEM_DIALOG_TEXT"))
        assertTrue("system dialog semantic key must remain visible", text.contains("system-key"))
        // The resourceId reaches the model ONLY through the form= key — the raw viewId axis is internal
        // and is never rendered, so asserting the form= prefix (not the bare id) is the correct contract.
        assertTrue("system dialog form key must remain visible", text.contains("form=com.android.packageinstaller:id/system_dialog"))
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
            guard = guard,
            core = AutomationCore(backend),
            foregroundPkg = { "me.rerere.rikkahub" },
            confirm = AlwaysConfirm,
        ).first { it.name == UI_OBSERVE_TOOL_NAME }

        val text = (runBlocking { tool.execute(buildJsonObject { }) }.single() as UIMessagePart.Text).text

        assertTrue("must surface the host-foreground pause state", text.contains("FOREGROUND_IS_HOST"))
        assertEquals("host snapshot must not leak host content", false, text.contains("chat"))
    }

    // --- 4. MALFORMED args ⇒ fail-closed ---

    @Test
    fun `a non-object argument fails closed without touching the backend`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(healthyGuard(), backend)

        // ui_observe takes an empty object; a JsonNull (or any non-object) is malformed and must
        // fail closed at the guard (P24), never reaching AutomationCore.
        val parts = runBlocking { tool.execute(JsonNull) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `a primitive argument fails closed without touching the backend`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(healthyGuard(), backend)

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
            val tool = observeTool(guard, backend)

            val entered = CompletableDeferred<Unit>().also { backend.snapshotEntered = it }
            val job: Job = launch(Dispatchers.Default) {
                tool.execute(buildJsonObject { })
            }
            // Deterministic: snapshotRawTree() completes `snapshotEntered` the instant it is reached
            // (after launch→authorize→guardInFlight), BEFORE it parks on the gate — so this waits for
            // the capture to be genuinely in-flight with NO scheduler-dependent yield-count race. The
            // old `repeat(50){yield()}` flaked here on CI (the Dispatchers.Default coroutine had not
            // reached the park when the fixed yields elapsed). Mirrors the act path's performEntered.
            entered.await()
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
        val tool = observeTool(guard, backend)

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
            guard = guard,
            core = AutomationCore(backend),
            foregroundPkg = { target }, // authorize-time foreground is the authorized one
            confirm = AlwaysConfirm,
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
            surface = Surface.Scoped(surface),
            // slice 9 adds SET_TEXT + the TYPE_INTO input sink; slice 10 adds TAP (no sink — a general
            // tap is verb-gated only) alongside the slice-8 nav authority, so the act guard ADMITs
            // every act tool's authorize on the happy path.
            verbs = setOf(Verb.OBSERVE, Verb.SCROLL, Verb.GLOBAL, Verb.SET_TEXT, Verb.TAP),
            sinkBudget = setOf(Sink.GLOBAL_NAV, Sink.TYPE_INTO),
            lease = Lease(expiresAt = expiresAt, maxSteps = maxSteps),
        ),
        clock = clock,
    )

    /** A foreground tree with a single editable text field (tid 0) carrying [text] plus a stable
     * resourceId (→ formKey, projector sets it only for editable nodes) and contentDescription
     * (→ semanticKey), used to ground ui_set_text. */
    private fun editableTree(stateSeq: Long = 0L, text: String = "hello"): RawTree = RawTree(
        stateSeq = stateSeq,
        foregroundPkg = target,
        windows = listOf(
            RawWindow(
                pkg = target,
                root = RawNode(
                    text = text,
                    className = "android.widget.EditText",
                    resourceId = "com.example.target:id/field",
                    contentDescription = "name-field",
                    editable = true,
                ),
            ),
        ),
    )

    /** ui_set_text args: { selector: { ... }, text: "..." }. */
    private fun setTextArgs(selector: kotlinx.serialization.json.JsonObject, text: String) = buildJsonObject {
        put("selector", selector)
        put("text", text)
    }

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
        guard = guard,
        core = AutomationCore(backend),
        foregroundPkg = { foregroundPkg },
        confirm = AlwaysConfirm,
    )

    private fun List<me.rerere.ai.core.Tool>.byName(name: String) = first { it.name == name }

    /** ui_scroll args: { selector: { tid: N }, direction: "forward"|"backward" }. */
    private fun scrollArgs(tid: Int, direction: String) = buildJsonObject {
        put("selector", buildJsonObject { put("tid", tid) })
        put("direction", direction)
    }

    /** A foreground tree with a single clickable element (tid 0), used to ground ui_tap. */
    private fun clickableTree(stateSeq: Long = 0L): RawTree = RawTree(
        stateSeq = stateSeq,
        foregroundPkg = target,
        windows = listOf(
            RawWindow(
                pkg = target,
                root = RawNode(
                    text = "OK",
                    className = "android.widget.Button",
                    clickable = true,
                ),
            ),
        ),
    )

    /** ui_tap args: { selector: { ... } } (selector-only — a general tap takes no direction/text). */
    private fun tapArgs(selector: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("selector", selector)
    }

    // --- 7a. FACTORY GATING — the act tools obey the same guard-only gate as ui_observe ---

    @Test
    fun `a null guard exposes no act tools either`() {
        val backend = FakeBackend(scrollableTree())
        val tools = getUiAutomationTools(
            guard = null,
            core = AutomationCore(backend),
            foregroundPkg = { target },
            confirm = AlwaysConfirm,
        )
        assertTrue("no guard ⇒ no authority ⇒ no automation tools at all", tools.isEmpty())
    }

    // (Presence + needsApproval==false for all three tools is asserted by
    // `factory exposes the observe plus nav act tools when a guard is present` above. The guard
    // source-of-truth invariant is pinned by
    // `factory exposes the tools for any guard ChatService already minted`.)

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
        val guard = actGuard()
        val global = actTools(guard, backend).byName(UI_GLOBAL_TOOL_NAME)

        val parts = runBlocking { global.execute(buildJsonObject { put("direction", "back") }) }

        assertTrue("no perform without a grounded snapshot", backend.performed.isEmpty())
        assertTrue("a well-formed ungrounded act is not an admission decision", guard.audit.entries().isEmpty())
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
        val action = backend.performed.single() as PerformAction.Node
        assertEquals("the dispatched verb must be a scroll-forward", NodeActionKind.SCROLL_FORWARD, action.kind)
        assertEquals("the dispatch is scoped to the foreground app", setOf(target), action.allowedPackages)
        assertTrue("the dispatch must be bound to a structural fingerprint, not a tid", action.binding.structuralFingerprint.isNotEmpty())
        assertEquals("pre-dispatch settle + post-dispatch settle (spec §6 step 8)", settleBefore + 2, backend.settleCount)
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

        val back = backend.performed.single() as PerformAction.Node
        assertEquals(NodeActionKind.SCROLL_BACKWARD, back.kind)
        assertEquals(setOf(target), back.allowedPackages)
        assertTrue("dispatch must be bound to a structural fingerprint", back.binding.structuralFingerprint.isNotEmpty())
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
    fun `benign stateSeq churn after grounding still dispatches ui_scroll`() {
        val backend = FakeBackend(scrollableTree(stateSeq = 0L))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        // A stateSeq bump that leaves the structural tree unchanged is BENIGN churn (e.g. non-active
        // SystemUI noise). The eyes-open binding still matches exactly one node, so the scroll must
        // DISPATCH (the old seq+hash gate would have stale-refused it — the regression this fixes).
        backend.injectTransition()
        val parts = runBlocking { tools.byName(UI_SCROLL_TOOL_NAME).execute(scrollArgs(0, "forward")) }

        assertEquals("benign churn must still dispatch the scroll", 1, backend.performed.size)
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("the result must be the fresh re-rendered snapshot", text.contains("stateSeq="))
        assertTrue("the result must still name the foreground app", text.contains(target))
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

    // --- 7h. UNGROUNDED + MALFORMED is still AUDITED (#221): the unfixed act tools gated on
    // `grounded ?: return ACT_REOBSERVE_MESSAGE` BEFORE the malformed check, so a malformed act issued
    // before any ui_observe left NO ledger entry — a P24/P25 "every malformed act audited" gap (inert,
    // since an ungrounded act cannot dispatch, but inconsistent with ui_observe's malformed branch).
    // The fix runs the malformed checks before the grounding gate in ALL act tools; these pin that an
    // ungrounded+malformed act writes exactly one DENY entry with a null targetPkg (no grounded target)
    // — one test per act tool to keep the four symmetric.

    private fun assertUngroundedMalformedAudited(
        guard: CapabilityGuard,
        backend: FakeBackend,
        verb: Verb,
        rawArgs: String,
    ) {
        val entries = guard.audit.entries()
        assertEquals("an ungrounded malformed act must append exactly one ledger entry", 1, entries.size)
        val entry = entries.single()
        assertEquals(Decision.DENY, entry.decision)
        assertEquals(DenyReason.MALFORMED, entry.reason)
        assertEquals("the ledger must record the attempted verb", verb, entry.verb)
        assertNull("with no grounding there is no truthful act target", entry.targetPkg)
        // P25 (#221): the ungrounded path must store the redacted form, NEVER the raw args — pin it
        // directly here, not just on the grounded malformed tests, so a regression that wrote raw args
        // into the ledger on the ungrounded path would fail this test.
        assertEquals("the ledger must store the redacted form, never raw args", redactAndTruncate(rawArgs), entry.redactedArgs)
        assertTrue("an ungrounded malformed act must never perform", backend.performed.isEmpty())
    }

    @Test
    fun `a malformed ui_scroll before any observe still audits one DENY`() {
        val backend = FakeBackend(scrollableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)

        // NO grounding observe first: the unfixed tool returned ACT_REOBSERVE_MESSAGE before the
        // malformed branch, leaving the ledger empty.
        runBlocking { tools.byName(UI_SCROLL_TOOL_NAME).execute(JsonNull) }

        assertUngroundedMalformedAudited(guard, backend, Verb.SCROLL, JsonNull.toString())
    }

    @Test
    fun `a malformed ui_global before any observe still audits one DENY`() {
        val backend = FakeBackend(scrollableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)

        val args = buildJsonObject { put("direction", "sideways") }
        runBlocking { tools.byName(UI_GLOBAL_TOOL_NAME).execute(args) }

        assertUngroundedMalformedAudited(guard, backend, Verb.GLOBAL, args.toString())
    }

    @Test
    fun `a malformed ui_set_text before any observe still audits one DENY`() {
        val backend = FakeBackend(editableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)

        runBlocking { tools.byName(UI_SET_TEXT_TOOL_NAME).execute(JsonNull) }

        assertUngroundedMalformedAudited(guard, backend, Verb.SET_TEXT, JsonNull.toString())
    }

    @Test
    fun `a malformed ui_tap before any observe still audits one DENY`() {
        val backend = FakeBackend(clickableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)

        val args = tapArgs(buildJsonObject { })
        runBlocking { tools.byName(UI_TAP_TOOL_NAME).execute(args) }

        assertUngroundedMalformedAudited(guard, backend, Verb.TAP, args.toString())
    }

    // A WELL-FORMED act before any observe must KEEP the re-observe steer un-audited: it is not a
    // malformed admission attempt, just a sequencing error the model can self-correct. Pins that the
    // #221 reorder did not over-widen the audit to the plain ungrounded path.
    @Test
    fun `a well-formed ui_scroll before any observe steers to re-observe without an audit entry`() {
        val backend = FakeBackend(scrollableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)

        val parts = runBlocking { tools.byName(UI_SCROLL_TOOL_NAME).execute(scrollArgs(0, "forward")) }

        assertTrue("a well-formed ungrounded act is not an admission decision", guard.audit.entries().isEmpty())
        assertTrue(backend.performed.isEmpty())
        assertTrue(
            "must tell the model to observe first",
            (parts.single() as UIMessagePart.Text).text.contains("observe", ignoreCase = true),
        )
    }

    // --- 8. ui_set_text (#198 slice 9): the input sink (Verb.SET_TEXT + Sink.TYPE_INTO) over the
    // proven act kernel. Mirrors the ui_scroll suite: grounding gate, happy path (one
    // PerformAction.SetText recorded, Acted re-grounds), P9 no-op, by-formKey resolution, S2 deny
    // (no leak), StaleState on a seq bump, and malformed (non-object + missing text) fail-closed +
    // AUDITED. As with the nav tools, the real ACTION_SET_TEXT dispatch parity is slice 12, not here.

    @Test
    fun `ui_set_text before any observe returns a re-observe text and never performs`() {
        val backend = FakeBackend(editableTree())
        val guard = actGuard()
        val setText = actTools(guard, backend).byName("ui_set_text")

        val parts = runBlocking {
            setText.execute(setTextArgs(buildJsonObject { put("tid", 0) }, "x"))
        }

        assertTrue("no perform without a grounded snapshot", backend.performed.isEmpty())
        assertTrue("a well-formed ungrounded act is not an admission decision", guard.audit.entries().isEmpty())
        assertTrue(
            "must tell the model to observe first",
            (parts.single() as UIMessagePart.Text).text.contains("observe", ignoreCase = true),
        )
    }

    @Test
    fun `ui_set_text after observe performs one node set-text, settles, and re-grounds`() {
        val backend = FakeBackend(editableTree(stateSeq = 5L, text = "hello"))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val settleBefore = backend.settleCount

        // The requested text DIFFERS from the field's projected text, so it dispatches (not a no-op).
        val parts = runBlocking {
            tools.byName("ui_set_text").execute(setTextArgs(buildJsonObject { put("tid", 0) }, "world"))
        }

        assertEquals("exactly one perform", 1, backend.performed.size)
        val action = backend.performed.single() as PerformAction.SetText
        assertEquals("the dispatched payload is the requested text", "world", action.text)
        assertEquals(setOf(target), action.allowedPackages)
        assertTrue("dispatch must be bound to a structural fingerprint", action.binding.structuralFingerprint.isNotEmpty())
        assertFalse("a set_text binding must not require a visible-text match", action.binding.requireVisibleTextMatch)
        assertEquals("pre-resolve settle + post-dispatch settle (spec §6 step 9)", settleBefore + 2, backend.settleCount)
        // Acted re-grounds: FakeBackend bumps stateSeq on perform, so the re-snapshot reads stateSeq=6.
        assertTrue(
            "the result must be the fresh re-rendered snapshot",
            (parts.single() as UIMessagePart.Text).text.contains("stateSeq=6"),
        )
    }

    @Test
    fun `ui_set_text equal to the current field text is a no-op and never performs`() {
        val backend = FakeBackend(editableTree(stateSeq = 3L, text = "hello"))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val settleBefore = backend.settleCount

        // The requested text EQUALS the field's projected text ⇒ P9 no-op: success, but no dispatch.
        val parts = runBlocking {
            tools.byName("ui_set_text").execute(setTextArgs(buildJsonObject { put("tid", 0) }, "hello"))
        }

        assertTrue("a no-op set_text must never perform", backend.performed.isEmpty())
        // The pre-resolve settle (spec §6 step 9) still runs before the P9 check; the no-op then skips
        // dispatch, so exactly one settle ran for the act.
        assertEquals("P9 no-op runs the pre-resolve settle only", settleBefore + 1, backend.settleCount)
        // The no-op returns the UNCHANGED grounding re-rendered (stateSeq stays 3, not bumped).
        assertTrue(
            "the result must be the unchanged grounding",
            (parts.single() as UIMessagePart.Text).text.contains("stateSeq=3"),
        )
    }

    @Test
    fun `ui_set_text resolves a field by its formKey`() {
        val backend = FakeBackend(editableTree(stateSeq = 1L, text = "old"))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        runBlocking {
            tools.byName("ui_set_text").execute(
                setTextArgs(buildJsonObject { put("formKey", "com.example.target:id/field") }, "new"),
            )
        }

        val byForm = backend.performed.single() as PerformAction.SetText
        assertEquals("a by-formKey set_text dispatches the requested text", "new", byForm.text)
        assertEquals(setOf(target), byForm.allowedPackages)
        assertTrue("dispatch must be bound to a structural fingerprint", byForm.binding.structuralFingerprint.isNotEmpty())
    }

    // The by-formKey set_text above only works if the model can LEARN a field's formKey from the
    // observe table — otherwise the {formKey:...} selector is an advertised-but-unreachable axis. This
    // pins the reachability half: the ui_observe render of an editable field must surface its formKey
    // (the projector sets it only for editable nodes). On the unfixed renderer (formKey never emitted)
    // this FAILS — the model could never produce the by-formKey selector the test above hand-feeds.
    @Test
    fun `ui_observe renders an editable field's formKey so the model can address it`() {
        val backend = FakeBackend(editableTree(stateSeq = 1L, text = "old"))
        val tools = actTools(actGuard(), backend)

        val parts = runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val text = (parts.single() as UIMessagePart.Text).text

        assertTrue(
            "the observe table must surface the editable field's formKey so {formKey:...} is reachable",
            text.contains("form=com.example.target:id/field"),
        )
    }

    @Test
    fun `revoked guard denies ui_set_text before the backend and never leaks the deny reason`() {
        val backend = FakeBackend(editableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val performsAfterObserve = backend.performed.size

        guard.revoke()
        val parts = runBlocking {
            tools.byName("ui_set_text").execute(setTextArgs(buildJsonObject { put("tid", 0) }, "x"))
        }

        assertEquals("a revoked guard must never perform", performsAfterObserve, backend.performed.size)
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("denied result must explain the refusal", text.contains("denied", ignoreCase = true))
        assertFalse("must not leak the deny reason", text.contains("GUARD"))
        assertFalse("must not leak the deny reason", text.contains("REVOKED"))
    }

    @Test
    fun `benign stateSeq churn after grounding still dispatches ui_set_text`() {
        val backend = FakeBackend(editableTree(stateSeq = 0L, text = "old"))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        // Benign churn (seq bump, same field): the binding still matches ⇒ the set_text dispatches.
        backend.injectTransition()
        val parts = runBlocking {
            tools.byName("ui_set_text").execute(setTextArgs(buildJsonObject { put("tid", 0) }, "new"))
        }

        assertEquals("benign churn must still dispatch the set_text", 1, backend.performed.size)
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("the result must be the fresh re-rendered snapshot", text.contains("stateSeq="))
    }

    @Test
    fun `ui_set_text with a non-object argument fails closed and audits one DENY with SET_TEXT`() {
        val backend = FakeBackend(editableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val entriesAfterObserve = guard.audit.entries().size

        runBlocking { tools.byName("ui_set_text").execute(JsonNull) }

        assertTrue("malformed args must never perform", backend.performed.isEmpty())
        val newEntries = guard.audit.entries().drop(entriesAfterObserve)
        assertEquals("a malformed set_text must append exactly one ledger entry", 1, newEntries.size)
        val entry = newEntries.single()
        assertEquals(Decision.DENY, entry.decision)
        assertEquals(DenyReason.MALFORMED, entry.reason)
        assertEquals("the ledger must record SET_TEXT for a ui_set_text attempt", Verb.SET_TEXT, entry.verb)
        assertEquals(redactAndTruncate(JsonNull.toString()), entry.redactedArgs)
    }

    @Test
    fun `ui_set_text with a missing text arg fails closed and audits one DENY with SET_TEXT`() {
        val backend = FakeBackend(editableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val entriesAfterObserve = guard.audit.entries().size

        // A well-formed object + a resolvable selector but NO text payload is malformed: a set_text
        // with no text is meaningless, so it must fail closed (never dispatch an empty/garbage write).
        runBlocking {
            tools.byName("ui_set_text").execute(buildJsonObject { put("selector", buildJsonObject { put("tid", 0) }) })
        }

        assertTrue("a set_text missing its text must never perform", backend.performed.isEmpty())
        val newEntries = guard.audit.entries().drop(entriesAfterObserve)
        assertEquals("a missing-text set_text must append exactly one ledger entry", 1, newEntries.size)
        val entry = newEntries.single()
        assertEquals(DenyReason.MALFORMED, entry.reason)
        assertEquals(Verb.SET_TEXT, entry.verb)
    }

    // A NON-STRING text is the garbage-write case (#198 input sink fail-closed). The unfixed tool read
    // the payload with `(args["text"] as? JsonPrimitive)?.contentOrNull`, which COERCES any primitive
    // ({"text":123} -> "123", true -> "true") and DISPATCHED it as a write instead of routing to the
    // malformed branch. On the unfixed code this FAILS (a SetText for "123" is performed, no DENY is
    // audited); the isString gate makes it fail closed + audit, exactly like a missing text.
    @Test
    fun `ui_set_text with a non-string text fails closed and audits one DENY with SET_TEXT`() {
        val backend = FakeBackend(editableTree(stateSeq = 5L, text = "hello"))
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val entriesAfterObserve = guard.audit.entries().size

        // A resolvable selector (tid 0) but a NUMBER text: contentOrNull would coerce it to "123" and
        // write that; the isString guard must reject it as malformed (never coerce a non-string write).
        runBlocking {
            tools.byName("ui_set_text").execute(
                buildJsonObject {
                    put("selector", buildJsonObject { put("tid", 0) })
                    put("text", 123)
                },
            )
        }

        assertTrue("a non-string text must never perform (no coerced write)", backend.performed.isEmpty())
        val newEntries = guard.audit.entries().drop(entriesAfterObserve)
        assertEquals("a non-string-text set_text must append exactly one ledger entry", 1, newEntries.size)
        val entry = newEntries.single()
        assertEquals(Decision.DENY, entry.decision)
        assertEquals(DenyReason.MALFORMED, entry.reason)
        assertEquals("the ledger must record SET_TEXT for a coerced-text attempt", Verb.SET_TEXT, entry.verb)
    }

    // The selector's string fields (formKey/semanticKey/text) had the same coercion bug in
    // parseSelector: `(obj["formKey"] as? JsonPrimitive)?.contentOrNull` turned {"formKey":123} into a
    // ByFormKey("123") instead of failing closed. The fix gates each on isString. On the unfixed code
    // this FAILS (the coerced "123" formKey resolves to nothing -> AMBIGUOUS, but it is treated as a
    // valid selector rather than a malformed arg); the gate routes it to the malformed DENY.
    @Test
    fun `ui_set_text with a non-string selector field fails closed and audits one DENY with SET_TEXT`() {
        val backend = FakeBackend(editableTree(stateSeq = 5L, text = "hello"))
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val entriesAfterObserve = guard.audit.entries().size

        // A NUMBER formKey: parseSelector must NOT coerce it into a ByFormKey("123") selector — a
        // non-string selector field is malformed and returns null, so the tool audits a malformed DENY.
        runBlocking {
            tools.byName("ui_set_text").execute(
                buildJsonObject {
                    put("selector", buildJsonObject { put("formKey", 123) })
                    put("text", "world")
                },
            )
        }

        assertTrue("a non-string selector field must never perform", backend.performed.isEmpty())
        val newEntries = guard.audit.entries().drop(entriesAfterObserve)
        assertEquals("a non-string selector field must append exactly one ledger entry", 1, newEntries.size)
        val entry = newEntries.single()
        assertEquals(Decision.DENY, entry.decision)
        assertEquals(DenyReason.MALFORMED, entry.reason)
        assertEquals(Verb.SET_TEXT, entry.verb)
    }

    // An EMPTY-STRING text must stay VALID — clearing a field is a legitimate set_text the act path's
    // P9 no-op handles. This pins that the isString fix did not over-tighten "" into a malformed arg
    // (the guard is isString, not isNotEmpty). With the field currently non-empty ("hello"), setting it
    // to "" is a real change, so it dispatches one SetText("").
    @Test
    fun `ui_set_text with an empty-string text is valid and clears the field`() {
        val backend = FakeBackend(editableTree(stateSeq = 5L, text = "hello"))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        runBlocking {
            tools.byName("ui_set_text").execute(setTextArgs(buildJsonObject { put("tid", 0) }, ""))
        }

        val clear = backend.performed.single() as PerformAction.SetText
        assertEquals("an empty-string text clears the field", "", clear.text)
        assertEquals(setOf(target), clear.allowedPackages)
        assertTrue("dispatch must be bound to a structural fingerprint", clear.binding.structuralFingerprint.isNotEmpty())
    }

    // --- 9. ui_tap (#198 slice 10): the general tap (Verb.TAP, no sink — Act.Targeted + CLICK) over
    // the proven act kernel. Mirrors the ui_scroll suite but selector-only: grounding gate, happy path
    // (one PerformAction.Node CLICK recorded, Acted re-grounds), S2 deny (no leak), StaleState on a seq
    // bump, and malformed (non-object + unparseable selector) fail-closed + AUDITED with Verb.TAP. The
    // system-UI-non-actionable HEADLINE lives in the :automation PBT over FakeBackend (the tool test
    // cannot inject a system window through the foregroundPkg supplier cleanly). As with the nav tools,
    // the real ACTION_CLICK dispatch parity is slice 12, not here.

    @Test
    fun `ui_tap before any observe returns a re-observe text and never performs`() {
        val backend = FakeBackend(clickableTree())
        val guard = actGuard()
        val tap = actTools(guard, backend).byName("ui_tap")

        val parts = runBlocking { tap.execute(tapArgs(buildJsonObject { put("tid", 0) })) }

        assertTrue("no perform without a grounded snapshot", backend.performed.isEmpty())
        assertTrue("a well-formed ungrounded act is not an admission decision", guard.audit.entries().isEmpty())
        assertTrue(
            "must tell the model to observe first",
            (parts.single() as UIMessagePart.Text).text.contains("observe", ignoreCase = true),
        )
    }

    @Test
    fun `ui_tap after observe performs one node click, settles, and re-grounds`() {
        val backend = FakeBackend(clickableTree(stateSeq = 5L))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val settleBefore = backend.settleCount

        val parts = runBlocking {
            tools.byName("ui_tap").execute(tapArgs(buildJsonObject { put("tid", 0) }))
        }

        assertEquals("exactly one perform", 1, backend.performed.size)
        val action = backend.performed.single() as PerformAction.Node
        assertEquals("the dispatched verb must be a CLICK", NodeActionKind.CLICK, action.kind)
        assertEquals(setOf(target), action.allowedPackages)
        assertTrue("dispatch must be bound to a structural fingerprint, not a tid", action.binding.structuralFingerprint.isNotEmpty())
        assertTrue("a tap binding requires the visible-text match", action.binding.requireVisibleTextMatch)
        assertEquals("pre-dispatch settle + post-dispatch settle (spec §6 step 8)", settleBefore + 2, backend.settleCount)
        // Acted re-grounds: FakeBackend bumps stateSeq on perform, so the re-snapshot reads stateSeq=6.
        assertTrue(
            "the result must be the fresh re-rendered snapshot",
            (parts.single() as UIMessagePart.Text).text.contains("stateSeq=6"),
        )
    }

    @Test
    fun `revoked guard denies ui_tap before the backend and never leaks the deny reason`() {
        val backend = FakeBackend(clickableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val performsAfterObserve = backend.performed.size

        guard.revoke()
        val parts = runBlocking {
            tools.byName("ui_tap").execute(tapArgs(buildJsonObject { put("tid", 0) }))
        }

        assertEquals("a revoked guard must never perform", performsAfterObserve, backend.performed.size)
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("denied result must explain the refusal", text.contains("denied", ignoreCase = true))
        assertFalse("must not leak the deny reason", text.contains("GUARD"))
        assertFalse("must not leak the deny reason", text.contains("REVOKED"))
    }

    @Test
    fun `benign stateSeq churn after grounding still dispatches ui_tap`() {
        val backend = FakeBackend(clickableTree(stateSeq = 0L))
        val tools = actTools(actGuard(), backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }

        // Benign churn (seq bump, same button): the binding still matches ⇒ the tap dispatches.
        backend.injectTransition()
        val parts = runBlocking {
            tools.byName("ui_tap").execute(tapArgs(buildJsonObject { put("tid", 0) }))
        }

        assertEquals("benign churn must still dispatch the tap", 1, backend.performed.size)
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("the result must be the fresh re-rendered snapshot", text.contains("stateSeq="))
    }

    @Test
    fun `ui_tap with a non-object argument fails closed and audits one DENY with TAP`() {
        val backend = FakeBackend(clickableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val entriesAfterObserve = guard.audit.entries().size

        runBlocking { tools.byName("ui_tap").execute(JsonNull) }

        assertTrue("malformed args must never perform", backend.performed.isEmpty())
        val newEntries = guard.audit.entries().drop(entriesAfterObserve)
        assertEquals("a malformed tap must append exactly one ledger entry", 1, newEntries.size)
        val entry = newEntries.single()
        assertEquals(Decision.DENY, entry.decision)
        assertEquals(DenyReason.MALFORMED, entry.reason)
        assertEquals("the ledger must record TAP for a ui_tap attempt", Verb.TAP, entry.verb)
        assertEquals(redactAndTruncate(JsonNull.toString()), entry.redactedArgs)
    }

    @Test
    fun `a malformed ui_tap selector writes one DENY audit entry with TAP`() {
        val backend = FakeBackend(clickableTree())
        val guard = actGuard()
        val tools = actTools(guard, backend)
        runBlocking { tools.byName(UI_OBSERVE_TOOL_NAME).execute(buildJsonObject { }) }
        val entriesAfterObserve = guard.audit.entries().size

        // A well-formed object but an unparseable selector (empty selector object) is still malformed.
        runBlocking {
            tools.byName("ui_tap").execute(tapArgs(buildJsonObject { }))
        }

        val newEntries = guard.audit.entries().drop(entriesAfterObserve)
        assertEquals("an unparseable selector must append one ledger entry", 1, newEntries.size)
        val entry = newEntries.single()
        assertEquals(DenyReason.MALFORMED, entry.reason)
        assertEquals(Verb.TAP, entry.verb)
        assertTrue(backend.performed.isEmpty())
    }
}
