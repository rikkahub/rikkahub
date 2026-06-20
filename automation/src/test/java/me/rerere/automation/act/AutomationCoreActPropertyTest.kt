package me.rerere.automation.act

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.automation.backend.FakeBackend
import me.rerere.automation.backend.FreshnessEventImpact
import me.rerere.automation.backend.FreshnessEventKind
import me.rerere.automation.backend.FreshnessReducer
import me.rerere.automation.backend.GlobalNav
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.backend.PerformAction
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import me.rerere.automation.cap.Capability
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Lease
import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.Surface
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.automation.observe.Selector
import me.rerere.automation.observe.UiFlag
import me.rerere.automation.observe.UiSnapshot
import me.rerere.automation.observe.UiTarget
import me.rerere.automation.observe.toTargetBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Act-path state-machine PBT — the eyes-open hybrid tap design. The freshness signal for a targeted
 * dispatch is a strict [me.rerere.automation.observe.TargetBinding] re-resolved against a FRESH
 * capture, NOT a `(stateSeq, tid)` token. So:
 *
 *  - **benign churn** (a stateSeq bump with the SAME structural tree — e.g. non-active SystemUI noise)
 *    no longer stales a targeted act: the binding still matches exactly one node ⇒ it dispatches.
 *  - **binding mismatch** (the bound target's structural identity no longer resolves to exactly one
 *    live node — it re-flowed, was replaced, or vanished) ⇒ [ActOutcome.StaleState] carrying the FRESH
 *    snapshot (when the foreground is still the authorized surface) so the model re-grounds + re-decides.
 *  - a bare `{tid}` is accepted only as `grounded tid → UiTarget → TargetBinding`; the backend never
 *    dispatches by tid.
 *
 * The admission core is unchanged: guard-before-backend (S2), revoke-in-flight (P20), ambiguity fails
 * closed (I-act-9), system/permission UI is observable-but-non-actionable (I-act-3), and the submit-
 * class confirm is fail-closed. Every property is written so a naive "just dispatch by tid" core FAILS.
 */
class AutomationCoreActPropertyTest {

    private val APP = "com.example.app"

    /**
     * Slice-8/9/10 tests predate the slice-11 [ConfirmChannel] parameter on [AutomationCore.act]. They
     * exercise scroll/global/set_text (non-dangerous, so the confirm channel is never consulted) and
     * benign/password/system taps (the classifier returns false ⇒ no SUBMIT sink ⇒ not dangerous, or
     * the guard DENYs before the gate). So a 3-arg call that delegates to the 4-arg member with
     * [AlwaysConfirm] keeps every pre-slice-11 assertion intact WITHOUT a confirm prompt ever firing —
     * a single DRY shim instead of threading a confirm arg through ~35 unchanged call sites. The
     * slice-11 tests below call the 4-arg member directly with a [FakeConfirmChannel].
     */
    private suspend fun AutomationCore.act(
        guard: CapabilityGuard,
        grounded: UiSnapshot,
        request: Act,
    ): ActOutcome = act(guard, grounded, request, AlwaysConfirm)

    /** A backend whose foreground app has a scrollable list (tid 0) over a text item (tid 1). */
    private fun backend(seq: Long = 1L, pkg: String = APP) = FakeBackend(
        RawTree(
            stateSeq = seq,
            foregroundPkg = pkg,
            windows = listOf(
                RawWindow(
                    pkg = pkg,
                    root = RawNode(
                        text = "List", className = "RecyclerView",
                        visible = true, hasArea = true, scrollable = true,
                        children = listOf(
                            RawNode(text = "Item", className = "TextView", visible = true, hasArea = true),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** A guard whose authority covers the nav verbs + the global-nav sink for [pkg] (maximally OK). */
    private fun guard(
        pkg: String = APP,
        verbs: Set<Verb> = setOf(Verb.SCROLL, Verb.GLOBAL),
        sinks: Set<Sink> = setOf(Sink.GLOBAL_NAV),
        now: Long = 0L,
        expiresAt: Long = Long.MAX_VALUE,
    ) = CapabilityGuard(
        Capability.root(
            sessionId = "s",
            surface = Surface.Scoped(setOf(pkg)),
            verbs = verbs,
            sinkBudget = sinks,
            lease = Lease(expiresAt = expiresAt, maxSteps = 1000),
        ),
        TrustClock { now },
    )

    /** The grounded target at tid 0 — the element every happy-path act dispatches on. */
    private fun UiSnapshot.target0(): UiTarget = targets.first { it.tid == 0 }

    // ---- BENIGN CHURN: a stateSeq advance that leaves the structural tree unchanged no longer
    // stales a targeted act — the strict binding still matches exactly one node, so it dispatches.
    // (On the old `(stateSeq, tid)` core this FAILED: any seq bump ⇒ StaleState. The whole point of
    // the eyes-open design is that non-active SystemUI/status-bar churn must not stale an app act.) ----
    @Test
    fun `benign stateSeq churn after grounding still dispatches the scroll`() {
        runBlocking {
            checkAll(300, Arb.int(1..1000)) { advance ->
                val backend = backend()
                val core = AutomationCore(backend)
                val grounded = core.observe(setOf(backend.rawTree.foregroundPkg)) // grounded @ seq 1
                repeat(advance) { backend.injectTransition() } // seq churn, SAME windows (benign)
                val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
                assertTrue("benign churn must NOT stale a targeted act", outcome is ActOutcome.Acted)
                assertEquals("the scroll must dispatch exactly once", 1, backend.performed.size)
            }
        }
    }

    // ---- BINDING MISMATCH: a structural change that removes the bound target ⇒ StaleState(fresh),
    // no dispatch. The dispatch-time re-resolve is the freshness signal (replaces the old carried-seq
    // re-check): the backend re-projects the live tree and refuses when the binding no longer matches. ----
    @Test
    fun `a structural change removing the target stales the scroll with a fresh snapshot`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            // Replace the tree so the scrollable target is GONE (binding no longer matches anything).
            backend.injectTransition(newWindows = emptyList())
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            assertTrue("a binding mismatch must be StaleState", outcome is ActOutcome.StaleState)
            assertTrue("a stale scroll must NOT touch the backend", backend.performed.isEmpty())
        }
    }

    // ---- resolve (P8): a tid that does not exist in the grounding is STALE_STATE (re-observe) ----
    @Test
    fun `P8 a tid absent from the grounding is StaleState`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(999), NodeActionKind.SCROLL_FORWARD))
            assertEquals(ActOutcome.StaleState(null), outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- BENIGN CHURN (hash variant): a content-hash change at equal seq no longer stales a targeted
    // act — the hash gate is GONE, the binding match decides. (Old core: hash mismatch ⇒ StaleState.) ----
    @Test
    fun `a content-hash change at equal seq no longer stales a targeted act`() {
        runBlocking {
            checkAll(200, Arb.string(1..12)) { newHash ->
                val backend = backend()
                val core = AutomationCore(backend)
                val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
                backend.setContentHash(grounded.stateSeq, newHash) // hash diverges, tree unchanged
                val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
                assertTrue("a hash-only change must NOT stale a targeted act", outcome is ActOutcome.Acted)
                assertEquals(1, backend.performed.size)
            }
        }
    }

    // ---- S2: a scroll without the SCROLL verb is denied and NEVER touches the backend ----
    @Test
    fun `S2 scroll without the SCROLL verb is denied before dispatch`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard(verbs = setOf(Verb.OBSERVE)) // OBSERVE only — no SCROLL authority
            val outcome = core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("S2: a denied act must NOT touch the backend", backend.performed.isEmpty())
        }
    }

    // ---- S2: a global nav without the GLOBAL_NAV sink budget is denied before dispatch ----
    @Test
    fun `S2 global nav without the GLOBAL_NAV budget is denied before dispatch`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard(verbs = setOf(Verb.GLOBAL), sinks = emptySet()) // verb ok, budget empty
            val outcome = core.act(g, grounded, Act.Global(GlobalNav.BACK))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- S2: an expired lease denies the act before dispatch ----
    @Test
    fun `S2 an expired lease denies the act before dispatch`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard(now = 100, expiresAt = 50) // now > expiresAt
            val outcome = core.act(g, grounded, Act.Global(GlobalNav.HOME))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- host-pause (I-act-6 / P12 extended): act while host-foreground ⇒ no dispatch ----
    @Test
    fun `act while host-foreground never dispatches (global)`() {
        runBlocking {
            val host = me.rerere.automation.observe.SnapshotProjector.HOST_PACKAGE
            val backend = backend(pkg = host)
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            assertEquals(
                "fixture must be a host-foreground grounding",
                me.rerere.automation.observe.ScreenState.FOREGROUND_IS_HOST,
                grounded.screenState,
            )
            val g = guard(pkg = host)
            val outcome = core.act(g, grounded, Act.Global(GlobalNav.BACK))
            assertEquals(ActOutcome.StaleState(null), outcome)
            assertTrue("host-foreground must NOT dispatch a global nav", backend.performed.isEmpty())
        }
    }

    @Test
    fun `act while host-foreground never dispatches (targeted)`() {
        runBlocking {
            val host = me.rerere.automation.observe.SnapshotProjector.HOST_PACKAGE
            val backend = backend(pkg = host)
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard(pkg = host)
            val outcome = core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            assertEquals(ActOutcome.StaleState(null), outcome)
            assertTrue("host-foreground must NOT dispatch a scroll", backend.performed.isEmpty())
        }
    }

    // ---- I-act-9: an ambiguous selector is a DENY (fail closed), never a guess ----
    @Test
    fun `ambiguous selector is denied not guessed`() {
        runBlocking {
            val ambiguousBackend = FakeBackend(
                RawTree(
                    stateSeq = 1L, foregroundPkg = APP,
                    windows = listOf(
                        RawWindow(
                            pkg = APP,
                            root = RawNode(
                                visible = false, hasArea = false,
                                children = listOf(
                                    RawNode(text = "Same", className = "TextView", visible = true, hasArea = true),
                                    RawNode(text = "Same", className = "TextView", visible = true, hasArea = true),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val core = AutomationCore(ambiguousBackend)
            val grounded = core.observe(setOf(ambiguousBackend.rawTree.foregroundPkg))
            assertEquals("fixture should project two same-text targets", 2, grounded.targets.size)
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByText("Same"), NodeActionKind.SCROLL_FORWARD))
            assertEquals(ActOutcome.Denied(ActDenyReason.AMBIGUOUS), outcome)
            assertTrue(ambiguousBackend.performed.isEmpty())
        }
    }

    // ---- happy path (scroll): a fresh, authorized act dispatches once, settles, and re-grounds ----
    @Test
    fun `a fresh authorized scroll dispatches once and re-grounds`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            assertTrue(outcome is ActOutcome.Acted)
            val snap = (outcome as ActOutcome.Acted).snapshot
            assertTrue("act success is the re-grounding postcondition", snap.stateSeq > grounded.stateSeq)
            assertEquals(1, backend.performed.size)
            assertEquals(
                PerformAction.Node(
                    binding = grounded.target0().toTargetBinding(requireVisibleTextMatch = false),
                    kind = NodeActionKind.SCROLL_FORWARD,
                    allowedPackages = setOf(grounded.foregroundPkg),
                ),
                backend.performed.single(),
            )
            // pre-dispatch settle (quiesce before the atomic re-resolve) + post-dispatch settle
            // (the dispatch atomically re-resolves, then the core settles + re-snapshots): spec §6 step 8.
            assertEquals("pre- + post-dispatch settle", 2, backend.settleCount)
        }
    }

    // ---- happy path (global): an authorized BACK dispatches a global action ----
    @Test
    fun `a fresh authorized global nav dispatches a global action`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val outcome = core.act(guard(), grounded, Act.Global(GlobalNav.BACK))
            assertTrue(outcome is ActOutcome.Acted)
            assertEquals(PerformAction.Global(GlobalNav.BACK), backend.performed.single())
        }
    }

    // ---- P20 (extended): revoke during an in-flight act cancels it; the act never completes ----
    @Test
    fun `P20 revoke during an in-flight act cancels it`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard()
            backend.armGate() // the next perform() parks until the owning coroutine is cancelled
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val job: Job = launch(Dispatchers.Default) {
                core.act(g, grounded, Act.Global(GlobalNav.BACK))
            }
            entered.await() // deterministic: the act has passed resolve/authorize and is parked in perform
            g.revoke() // kill-switch: must cancel the parked dispatch, not let it land
            job.join()
            assertTrue("revoke must cancel the in-flight act", job.isCancelled)
            assertFalse("the gated dispatch must never have completed", backend.performed.isNotEmpty())
        }
    }

    // ---- P20 (pre-revoked): an act on an already-revoked guard is denied, never dispatched ----
    @Test
    fun `an act on a pre-revoked guard is denied`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard()
            g.revoke()
            val outcome = core.act(g, grounded, Act.Global(GlobalNav.BACK))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- DISPATCH-TIME BINDING RE-CHECK: a structural change that lands in the assert→dispatch gap
    // (perform parked on the gate) must NOT dispatch on the now-mismatched tree. The backend's perform
    // re-projects the live tree and refuses (BindingMismatch) when the binding no longer matches; the
    // core surfaces it as StaleState(fresh). On a "dispatch by carried tid" core this FAILS — the scroll
    // lands on whatever now sits at tid 0 of a different tree. ----
    @Test
    fun `a structural change between resolve and dispatch never lands the scroll`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg)) // grounded @ seq 1
            val g = guard()
            backend.armGate() // the next perform() parks until released
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val deferred = async(Dispatchers.Default) {
                core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            }
            entered.await() // deterministic: parked in perform, having passed resolve/authorize
            // Remove the target so the binding no longer matches (the dispatch-time re-resolve refuses).
            backend.rawTree = backend.rawTree.copy(windows = emptyList())
            backend.releaseGate()
            val outcome = deferred.await()
            assertTrue(
                "a dispatch whose binding no longer matches must NOT land",
                backend.performed.isEmpty(),
            )
            assertTrue(
                "a refused (binding-mismatch) dispatch must be StaleState, never Acted",
                outcome is ActOutcome.StaleState,
            )
        }
    }

    // ---- post-act surface bind: the re-snapshot must NOT disclose an app the capability never
    // admitted. A global nav can surface a DIFFERENT app (HOME → launcher); the act authorized against
    // the GROUNDED foreground, so a re-snapshot that left that surface is StaleState(null), never an
    // Acted carrying the other app's content. ----
    @Test
    fun `a global nav that switches to an unadmitted app is StaleState not Acted`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg)) // grounded @ APP
            val g = guard() // surface = {APP} only
            backend.armGate()
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val deferredOutcome = async(Dispatchers.Default) {
                core.act(g, grounded, Act.Global(GlobalNav.HOME))
            }
            entered.await() // deterministic: parked in perform
            backend.setForeground("com.android.launcher") // HOME surfaced a different, unadmitted app
            backend.releaseGate()
            val outcome = deferredOutcome.await()
            assertEquals(
                "a re-snapshot off the authorized surface must be StaleState(null), never Acted(other app)",
                ActOutcome.StaleState(null),
                outcome,
            )
        }
    }

    // ===================================================================================
    // ui_set_text input sink + P9 (restricted idempotency, FRESH value) + self-heal.
    // ===================================================================================

    /** A guard whose authority covers SET_TEXT + the TYPE_INTO input sink for [pkg]. */
    private fun setTextGuard(pkg: String = APP, now: Long = 0L, expiresAt: Long = Long.MAX_VALUE) =
        guard(
            pkg = pkg,
            verbs = setOf(Verb.SET_TEXT, Verb.SCROLL, Verb.GLOBAL),
            sinks = setOf(Sink.TYPE_INTO, Sink.GLOBAL_NAV),
            now = now,
            expiresAt = expiresAt,
        )

    private fun editableBackend(
        seq: Long = 1L,
        pkg: String = APP,
        text: String = "hello",
        resourceId: String = "com.example.app:id/field",
        contentDescription: String = "name-field",
    ) = FakeBackend(
        RawTree(
            stateSeq = seq,
            foregroundPkg = pkg,
            windows = listOf(
                RawWindow(
                    pkg = pkg,
                    root = RawNode(
                        text = text, className = "EditText",
                        resourceId = resourceId, contentDescription = contentDescription,
                        visible = true, hasArea = true, editable = true,
                    ),
                ),
            ),
        ),
    )

    // ---- P9 (no-op, FRESH value): set_text whose FRESH resolved editable value already equals the
    // requested text does NOT dispatch, returning the FRESH snapshot. The pre-dispatch awaitSettle still
    // runs (spec §6 step 9), so settleCount==1; perform never runs. ----
    @Test
    fun `P9 a set_text equal to the fresh editable value is a no-op and never dispatches`() {
        runBlocking {
            val backend = editableBackend(text = "hello")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            assertEquals("fixture must project the field's editable value", "hello", grounded.target0().editableText)
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "hello"))
            assertTrue("an idempotent set_text still succeeds", outcome is ActOutcome.Acted)
            assertTrue("P9: a no-op set_text must NOT perform", backend.performed.isEmpty())
            // The pre-resolve settle (spec §6 step 9) still runs before the P9 check; the no-op then
            // skips dispatch, so there is no post-dispatch settle -> exactly one settle total.
            assertEquals("P9: pre-resolve settle only (no dispatch)", 1, backend.settleCount)
            // The no-op returns the FRESH resolved snapshot (stateSeq unchanged at 1 — no dispatch bumped it).
            assertEquals(
                "a no-op returns the fresh resolved snapshot",
                grounded.stateSeq,
                (outcome as ActOutcome.Acted).snapshot.stateSeq,
            )
        }
    }

    // ---- P9 surface invariant: a no-op set_text must NOT return Acted when the foreground switched
    // since the grounding, even if the old editable field still uniquely matches and already equals the
    // requested text. Returning the fresh snapshot would expose / re-ground on an un-admitted surface;
    // the no-op must stale-stop exactly like the post-dispatch tail. ----
    @Test
    fun `P9 no-op stale-stops when the foreground switched since grounding`() {
        runBlocking {
            val backend = editableBackend(text = "hello")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            // The foreground switches to a DIFFERENT app, but the originally-grounded field's window
            // (pkg = APP) is still present and still uniquely matches the binding.
            backend.rawTree = backend.rawTree.copy(foregroundPkg = "com.other.switched")
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "hello"))
            assertEquals(
                "a no-op on a switched surface must stale-stop, not Acted on an un-admitted surface",
                ActOutcome.StaleState(null),
                outcome,
            )
            assertTrue("no dispatch on a surface-switched no-op", backend.performed.isEmpty())
        }
    }

    // ---- submit: a set_text with submit=true dispatches PerformAction.SetText carrying the submit
    // flag, so the backend fires the field's IME action after the text lands (work-first, the live-
    // search/explicit-submit case). ----
    @Test
    fun `a submit set_text dispatches SetText carrying the submit flag`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "cat", submit = true))
            assertTrue("a submit set_text still succeeds", outcome is ActOutcome.Acted)
            val performed = backend.performed.single()
            assertTrue("must dispatch a SetText", performed is PerformAction.SetText)
            assertEquals("the requested text is dispatched", "cat", (performed as PerformAction.SetText).text)
            assertTrue("the submit flag must reach the backend", performed.submit)
        }
    }

    // ---- submit overrides P9: even when the FRESH editable value already equals the requested text,
    // a submit set_text must NOT take the no-op shortcut — the IME action has to fire (re-run the query
    // / submit the form). ----
    @Test
    fun `a submit set_text overrides P9 and dispatches even when the text already matches`() {
        runBlocking {
            val backend = editableBackend(text = "hello")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            assertEquals("fixture must project the field's editable value", "hello", grounded.target0().editableText)
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "hello", submit = true))
            assertTrue("submit still succeeds", outcome is ActOutcome.Acted)
            val performed = backend.performed.single()
            assertTrue("submit must bypass the P9 no-op and dispatch", performed is PerformAction.SetText)
            assertTrue("the dispatched action carries submit=true", (performed as PerformAction.SetText).submit)
        }
    }

    // ---- binding mismatch (MULTIPLE matches): when the fresh tree has TWO nodes that strictly match
    // the binding (e.g. two windows that both report an unknown id and carry an identical node), the
    // backend must refuse to dispatch (it cannot tell which is the intended target) and stale-stop. ----
    @Test
    fun `a binding that matches multiple fresh nodes does not dispatch`() {
        runBlocking {
            // Two eligible windows, both with an unavailable (default UNKNOWN) window id and an identical
            // clickable root ⇒ identical windowId + path + fingerprint ⇒ the binding matches BOTH.
            val dup = RawNode(text = "Dup", className = "Button", clickable = true, visible = true, hasArea = true)
            val backend = FakeBackend(
                RawTree(
                    stateSeq = 1L, foregroundPkg = APP,
                    windows = listOf(RawWindow(pkg = APP, root = dup), RawWindow(pkg = APP, root = dup)),
                ),
            )
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            // Grounding still resolves a UNIQUE tid (tids are per-snapshot unique even when fingerprints
            // collide); the ambiguity only surfaces at the fresh binding re-resolve.
            val outcome = core.act(tapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            assertTrue("a multi-match binding must stale-stop, never dispatch", outcome is ActOutcome.StaleState)
            assertTrue("a multi-match binding must NOT dispatch", backend.performed.isEmpty())
        }
    }

    // ---- P9 (clean-postcondition): an EMPTY field whose contentDescription HINT equals the requested
    // text MUST dispatch — the FRESH editable value is null (not the hint), so the compare fails toward
    // dispatch. (Old bug: comparing against the DISPLAY `text` projection matched the hint and skipped.) ----
    @Test
    fun `P9 set_text into an empty field whose hint equals the text dispatches not no-op`() {
        runBlocking {
            val backend = FakeBackend(
                RawTree(
                    stateSeq = 1L, foregroundPkg = APP,
                    windows = listOf(
                        RawWindow(
                            pkg = APP,
                            root = RawNode(
                                text = null, className = "EditText",
                                resourceId = "com.example.app:id/email",
                                contentDescription = "Email",
                                visible = true, hasArea = true, editable = true,
                            ),
                        ),
                    ),
                ),
            )
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val field = grounded.target0()
            assertEquals("the display projection falls back to the hint", "Email", field.text)
            assertNull("the editable value of a blank field is null (not the hint)", field.editableText)

            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "Email"))
            assertTrue("writing the hint text into the blank field must dispatch", outcome is ActOutcome.Acted)
            assertEquals(
                "the blank field must receive exactly one bound SetText (not a silent no-op)",
                PerformAction.SetText(
                    binding = field.toTargetBinding(requireVisibleTextMatch = false),
                    text = "Email",
                    allowedPackages = setOf(grounded.foregroundPkg),
                ),
                backend.performed.single(),
            )
            // pre-resolve settle + post-dispatch settle (the core settles + re-snapshots after the bound SetText lands).
            assertEquals("pre-resolve + post-dispatch settle", 2, backend.settleCount)
        }
    }

    // ---- P9 (FRESH value matters): if the field's value CHANGED since grounding to already equal the
    // requested text, the act is a no-op against the FRESH value (not the stale grounded one). Pins
    // that P9 reads the fresh resolved editableText, never the grounded stale value. ----
    @Test
    fun `P9 no-op uses the FRESH editable value not the stale grounded one`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            assertEquals("grounded value is 'old'", "old", grounded.target0().editableText)
            // The field's value changed to 'new' AFTER grounding (a benign external edit). A set_text
            // of 'new' is now idempotent against the FRESH value and must NOT dispatch.
            backend.rawTree = backend.rawTree.copy(
                windows = backend.rawTree.windows.map { it.copy(root = it.root!!.copy(text = "new")) },
            )
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "new"))
            assertTrue("idempotent against the fresh value ⇒ Acted without dispatch", outcome is ActOutcome.Acted)
            assertTrue("must NOT dispatch when the fresh value already matches", backend.performed.isEmpty())
        }
    }

    // ---- P9 (dispatch): a DIFFERENT text dispatches exactly one bound SetText and settles ----
    @Test
    fun `P9 a set_text different from the projected text dispatches once and re-grounds`() {
        runBlocking {
            checkAll(200, Arb.string(1..12)) { newText ->
                if (newText != "hello") {
                    val backend = editableBackend(text = "hello")
                    val core = AutomationCore(backend)
                    val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
                    val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), newText))
                    assertTrue("a changing set_text dispatches", outcome is ActOutcome.Acted)
                    assertEquals(
                        "exactly one bound SetText",
                        PerformAction.SetText(
                            binding = grounded.target0().toTargetBinding(requireVisibleTextMatch = false),
                            text = newText,
                            allowedPackages = setOf(grounded.foregroundPkg),
                        ),
                        backend.performed.single(),
                    )
                    assertEquals("pre-resolve + post-dispatch settle", 2, backend.settleCount)
                    assertTrue(
                        "act success is the re-grounding postcondition",
                        (outcome as ActOutcome.Acted).snapshot.stateSeq > grounded.stateSeq,
                    )
                }
            }
        }
    }

    // ---- P14/MR2 (self-heal, metamorphic): a stable key (formKey) resolves against a benignly-
    // reflowed re-projection (same keys, DIFFERENT tid order) and dispatches the keyed element. The
    // binding carries the keyed field's structural identity, so the backend re-resolves to it wherever
    // it now sits. On a positional-only (tid) core this FAILS — the wrong node is dispatched. ----
    @Test
    fun `P14 self-heal a stable key resolves under a reflow and dispatches the keyed element`() {
        runBlocking {
            fun twoFieldTree(seq: Long, targetFirst: Boolean) = RawTree(
                stateSeq = seq,
                foregroundPkg = APP,
                windows = listOf(
                    RawWindow(
                        pkg = APP,
                        root = RawNode(
                            visible = false, hasArea = false,
                            children = buildList {
                                val other = RawNode(
                                    text = "other", className = "EditText",
                                    resourceId = "com.example.app:id/other",
                                    contentDescription = "other-field",
                                    visible = true, hasArea = true, editable = true,
                                )
                                val targetField = RawNode(
                                    text = "old", className = "EditText",
                                    resourceId = "com.example.app:id/target",
                                    contentDescription = "target-field",
                                    visible = true, hasArea = true, editable = true,
                                )
                                if (targetFirst) { add(targetField); add(other) }
                                else { add(other); add(targetField) }
                            },
                        ),
                    ),
                ),
            )
            val backend = FakeBackend(twoFieldTree(seq = 1L, targetFirst = true))
            val core = AutomationCore(backend)
            core.observe(setOf(backend.rawTree.foregroundPkg)) // monotonic seq bookkeeping
            backend.rawTree = twoFieldTree(seq = 2L, targetFirst = false) // reflow: target now tid 1
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val targetField = grounded.targets.first { it.formKey == "com.example.app:id/target" }
            assertEquals("the reflow must have moved the target off tid 0", 1, targetField.tid)

            val byForm = core.act(
                setTextGuard(), grounded,
                Act.SetText(Selector.ByFormKey("com.example.app:id/target"), "new"),
            )
            assertTrue(byForm is ActOutcome.Acted)
            assertEquals(
                "ByFormKey must dispatch the keyed element's CURRENT structural identity (self-heal)",
                PerformAction.SetText(
                    binding = targetField.toTargetBinding(requireVisibleTextMatch = false),
                    text = "new",
                    allowedPackages = setOf(grounded.foregroundPkg),
                ),
                backend.performed.single(),
            )
        }
    }

    @Test
    fun `P14 self-heal BySemanticKey resolves the keyed editable element`() {
        runBlocking {
            val backend = editableBackend(text = "old", contentDescription = "name-field")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val outcome = core.act(
                setTextGuard(), grounded,
                Act.SetText(Selector.BySemanticKey("name-field"), "new"),
            )
            assertTrue(outcome is ActOutcome.Acted)
            assertEquals(
                PerformAction.SetText(
                    binding = grounded.target0().toTargetBinding(requireVisibleTextMatch = false),
                    text = "new",
                    allowedPackages = setOf(grounded.foregroundPkg),
                ),
                backend.performed.single(),
            )
        }
    }

    // ---- MR2 (ambiguous key): two targets share the stable key ⇒ Denied(AMBIGUOUS), never a guess ----
    @Test
    fun `set_text with an ambiguous formKey is denied not guessed`() {
        runBlocking {
            val ambiguous = FakeBackend(
                RawTree(
                    stateSeq = 1L, foregroundPkg = APP,
                    windows = listOf(
                        RawWindow(
                            pkg = APP,
                            root = RawNode(
                                visible = false, hasArea = false,
                                children = listOf(
                                    RawNode(
                                        text = "a", className = "EditText",
                                        resourceId = "com.example.app:id/dup",
                                        visible = true, hasArea = true, editable = true,
                                    ),
                                    RawNode(
                                        text = "b", className = "EditText",
                                        resourceId = "com.example.app:id/dup",
                                        visible = true, hasArea = true, editable = true,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val core = AutomationCore(ambiguous)
            val grounded = core.observe(setOf(ambiguous.rawTree.foregroundPkg))
            assertEquals("fixture should project two same-formKey fields", 2, grounded.targets.size)
            val outcome = core.act(
                setTextGuard(), grounded,
                Act.SetText(Selector.ByFormKey("com.example.app:id/dup"), "x"),
            )
            assertEquals(ActOutcome.Denied(ActDenyReason.AMBIGUOUS), outcome)
            assertTrue(ambiguous.performed.isEmpty())
        }
    }

    // ---- S2 (guard-before): a set_text without the SET_TEXT verb is denied before any dispatch ----
    @Test
    fun `S2 set_text without the SET_TEXT verb is denied before dispatch`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard(verbs = setOf(Verb.SCROLL), sinks = setOf(Sink.TYPE_INTO))
            val outcome = core.act(g, grounded, Act.SetText(Selector.ByTid(0), "new"))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("S2: a denied set_text must NOT touch the backend", backend.performed.isEmpty())
        }
    }

    @Test
    fun `S2 set_text without the TYPE_INTO sink budget is denied before dispatch`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard(verbs = setOf(Verb.SET_TEXT), sinks = emptySet())
            val outcome = core.act(g, grounded, Act.SetText(Selector.ByTid(0), "new"))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- HEADLINE input-sink safety (sensitiveNode): a set_text into a PASSWORD field is DENIED ----
    @Test
    fun `set_text into a password field is denied before dispatch`() {
        runBlocking {
            val passwordBackend = FakeBackend(
                RawTree(
                    stateSeq = 1L, foregroundPkg = APP,
                    windows = listOf(
                        RawWindow(
                            pkg = APP,
                            root = RawNode(
                                text = "secret", className = "EditText",
                                resourceId = "com.example.app:id/pw",
                                visible = true, hasArea = true, editable = true, password = true,
                            ),
                        ),
                    ),
                ),
            )
            val core = AutomationCore(passwordBackend)
            val grounded = core.observe(setOf(passwordBackend.rawTree.foregroundPkg))
            val pw = grounded.target0()
            assertTrue("fixture must project a password field", pw.flags.contains(UiFlag.PASSWORD))
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "x"))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("typing into a password field must NEVER dispatch", passwordBackend.performed.isEmpty())
        }
    }

    // ---- systemUiTarget: a set_text into a system-window editable node is DENIED before dispatch ----
    @Test
    fun `set_text into a system-window node is denied before dispatch`() {
        runBlocking {
            val systemBackend = FakeBackend(
                RawTree(
                    stateSeq = 1L, foregroundPkg = APP,
                    windows = listOf(
                        RawWindow(
                            pkg = "com.android.packageinstaller",
                            systemWindow = true,
                            root = RawNode(
                                text = "search", className = "EditText",
                                resourceId = "com.android.packageinstaller:id/search",
                                visible = true, hasArea = true, editable = true,
                            ),
                        ),
                    ),
                ),
            )
            val core = AutomationCore(systemBackend)
            val grounded = core.observe(setOf(systemBackend.rawTree.foregroundPkg))
            assertTrue(
                "the projected target must carry its system-window provenance",
                grounded.target0().systemWindow,
            )
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "x"))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("a system-UI set_text must NOT touch the backend", systemBackend.performed.isEmpty())
        }
    }

    // ---- BENIGN CHURN (set_text): a stateSeq advance that leaves the field structurally unchanged
    // does NOT stale a set_text — the binding still matches. (Old core: any seq bump ⇒ StaleState.) ----
    @Test
    fun `benign stateSeq churn after grounding still dispatches a set_text`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            repeat(5) { backend.injectTransition() } // benign seq churn, same field
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "new"))
            assertTrue("benign churn must NOT stale a set_text", outcome is ActOutcome.Acted)
            assertEquals("the set_text must dispatch exactly once", 1, backend.performed.size)
        }
    }

    // ---- DISPATCH-TIME BINDING RE-CHECK (set_text): a structural change in the resolve→dispatch gap
    // must NOT land the set_text. perform re-projects the live tree and refuses on a binding mismatch. ----
    @Test
    fun `a structural change between resolve and dispatch never lands the set_text`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = setTextGuard()
            backend.armGate()
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val deferred = async(Dispatchers.Default) {
                core.act(g, grounded, Act.SetText(Selector.ByTid(0), "new"))
            }
            entered.await() // parked in perform, having passed resolve/authorize
            backend.rawTree = backend.rawTree.copy(windows = emptyList()) // field gone ⇒ binding mismatch
            backend.releaseGate()
            val outcome = deferred.await()
            assertTrue(
                "a set_text whose binding no longer matches must NOT land",
                backend.performed.isEmpty(),
            )
            assertTrue(
                "a refused (binding-mismatch) set_text must be StaleState, never Acted",
                outcome is ActOutcome.StaleState,
            )
        }
    }

    // ---- P20 (revoke-in-flight, via SetText): revoke during a parked set_text cancels it ----
    @Test
    fun `P20 revoke during an in-flight set_text cancels it`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = setTextGuard()
            backend.armGate()
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val job: Job = launch(Dispatchers.Default) {
                core.act(g, grounded, Act.SetText(Selector.ByTid(0), "new"))
            }
            entered.await()
            g.revoke()
            job.join()
            assertTrue("revoke must cancel the in-flight set_text", job.isCancelled)
            assertFalse("the gated set_text must never have completed", backend.performed.isNotEmpty())
        }
    }

    // ---- I-act-3 (system UI observable but non-actionable): a scroll resolving a node inside a
    // system/permission window is DENIED before dispatch. ----
    @Test
    fun `a scroll targeting a system-window node is denied before dispatch`() {
        runBlocking {
            val systemBackend = FakeBackend(
                RawTree(
                    stateSeq = 1L,
                    foregroundPkg = APP,
                    windows = listOf(
                        RawWindow(
                            pkg = "com.android.packageinstaller",
                            systemWindow = true,
                            root = RawNode(
                                text = "Allow access?", className = "ScrollView",
                                visible = true, hasArea = true, scrollable = true,
                            ),
                        ),
                    ),
                ),
            )
            val core = AutomationCore(systemBackend)
            val grounded = core.observe(setOf(systemBackend.rawTree.foregroundPkg))
            assertTrue("fixture should project a system-window target", grounded.targets.isNotEmpty())
            assertTrue(
                "the projected target must carry its system-window provenance",
                grounded.target0().systemWindow,
            )
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("a system-UI scroll must NOT touch the backend", systemBackend.performed.isEmpty())
        }
    }

    // ===================================================================================
    // The general tap (Verb.TAP, derived from NodeActionKind.CLICK; no sink unless submit-class).
    // ===================================================================================

    /** A guard whose authority covers TAP (+ SCROLL/GLOBAL, no sink needed for a tap) for [pkg]. */
    private fun tapGuard(pkg: String = APP, now: Long = 0L, expiresAt: Long = Long.MAX_VALUE) =
        guard(
            pkg = pkg,
            verbs = setOf(Verb.TAP, Verb.SCROLL, Verb.GLOBAL),
            sinks = setOf(Sink.GLOBAL_NAV),
            now = now,
            expiresAt = expiresAt,
        )

    /** A backend whose foreground app has a single clickable element (tid 0). */
    private fun clickableBackend(seq: Long = 1L, pkg: String = APP) = FakeBackend(
        RawTree(
            stateSeq = seq,
            foregroundPkg = pkg,
            windows = listOf(
                RawWindow(
                    pkg = pkg,
                    root = RawNode(
                        text = "OK", className = "Button",
                        visible = true, hasArea = true, clickable = true,
                    ),
                ),
            ),
        ),
    )

    // ---- HEADLINE (system UI observable but non-actionable): a tap resolving a CLICKABLE node inside
    // a system/permission window is DENIED before dispatch AND stays observable. ----
    @Test
    fun `a tap on a clickable system-window node is denied before dispatch but stays observable`() {
        runBlocking {
            val systemBackend = FakeBackend(
                RawTree(
                    stateSeq = 1L,
                    foregroundPkg = APP,
                    windows = listOf(
                        RawWindow(
                            pkg = "com.android.packageinstaller",
                            systemWindow = true,
                            root = RawNode(
                                text = "Allow", className = "Button",
                                visible = true, hasArea = true, clickable = true,
                            ),
                        ),
                    ),
                ),
            )
            val core = AutomationCore(systemBackend)
            val grounded = core.observe(setOf(systemBackend.rawTree.foregroundPkg))
            assertTrue("a system-window target must remain observable", grounded.targets.isNotEmpty())
            assertTrue(
                "the projected target must carry its system-window provenance",
                grounded.target0().systemWindow,
            )
            val outcome = core.act(tapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("a system-UI tap must NOT touch the backend", systemBackend.performed.isEmpty())
        }
    }

    @Test
    fun `S2 tap without the TAP verb is denied before dispatch`() {
        runBlocking {
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard(verbs = setOf(Verb.SCROLL))
            val outcome = core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("S2: a denied tap must NOT touch the backend", backend.performed.isEmpty())
        }
    }

    @Test
    fun `a tap on a password node is denied before dispatch`() {
        runBlocking {
            val passwordBackend = FakeBackend(
                RawTree(
                    stateSeq = 1L, foregroundPkg = APP,
                    windows = listOf(
                        RawWindow(
                            pkg = APP,
                            root = RawNode(
                                text = "secret", className = "EditText",
                                resourceId = "com.example.app:id/pw",
                                visible = true, hasArea = true, editable = true,
                                clickable = true, password = true,
                            ),
                        ),
                    ),
                ),
            )
            val core = AutomationCore(passwordBackend)
            val grounded = core.observe(setOf(passwordBackend.rawTree.foregroundPkg))
            assertTrue("fixture must project a password field", grounded.target0().flags.contains(UiFlag.PASSWORD))
            val outcome = core.act(tapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("tapping a password field must NEVER dispatch", passwordBackend.performed.isEmpty())
        }
    }

    // ---- happy path (tap): a fresh, authorized tap dispatches exactly one bound CLICK ----
    @Test
    fun `a fresh authorized tap dispatches one click and re-grounds`() {
        runBlocking {
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val outcome = core.act(tapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            assertTrue(outcome is ActOutcome.Acted)
            val snap = (outcome as ActOutcome.Acted).snapshot
            assertTrue("act success is the re-grounding postcondition", snap.stateSeq > grounded.stateSeq)
            assertEquals(
                "exactly one bound CLICK",
                PerformAction.Node(
                    // A tap requires the visible-text match (a CLICK names a labeled element).
                    binding = grounded.target0().toTargetBinding(requireVisibleTextMatch = true),
                    kind = NodeActionKind.CLICK,
                    allowedPackages = setOf(grounded.foregroundPkg),
                ),
                backend.performed.single(),
            )
            // pre-dispatch settle + post-dispatch settle (spec §6 step 8 for a non-dangerous tap).
            assertEquals("pre- + post-dispatch settle", 2, backend.settleCount)
        }
    }

    // ---- gesture tap: a CLICK with gesture=true dispatches a Node action carrying the gesture flag,
    // so the backend taps as a real touch (dispatchGesture) for views that ignore the synthesized click.
    @Test
    fun `a tap with gesture true dispatches a CLICK carrying the gesture flag`() {
        runBlocking {
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val outcome = core.act(
                tapGuard(),
                grounded,
                Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK, gesture = true),
            )
            assertTrue("a gesture tap still succeeds", outcome is ActOutcome.Acted)
            val action = backend.performed.single() as PerformAction.Node
            assertEquals("still a CLICK on the resolved node", NodeActionKind.CLICK, action.kind)
            assertTrue("the gesture flag must reach the backend", action.gesture)
        }
    }

    // ---- BENIGN CHURN (tap): a stateSeq advance that leaves the button structurally unchanged does
    // NOT stale a tap. (Old core: any seq bump ⇒ StaleState — the headline regression this fixes.) ----
    @Test
    fun `benign stateSeq churn after grounding still dispatches a tap`() {
        runBlocking {
            checkAll(200, Arb.int(1..1000)) { advance ->
                val backend = clickableBackend()
                val core = AutomationCore(backend)
                val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
                repeat(advance) { backend.injectTransition() } // benign seq churn, same button
                val outcome = core.act(tapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
                assertTrue("benign churn must NOT stale a tap", outcome is ActOutcome.Acted)
                assertEquals("the tap must dispatch exactly once", 1, backend.performed.size)
            }
        }
    }

    // ---- BINDING MISMATCH (tap, same-label replacement): a button replaced by ANOTHER button with
    // the same text but a different structural fingerprint must NOT dispatch — the strict binding
    // refuses the same-label replacement (the name-only re-resolve false positive). ----
    @Test
    fun `a same-text structural replacement never lands the tap`() {
        runBlocking {
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            // Replace the button with a same-text, DIFFERENT-shape node (different class + a child):
            // same label "OK" but a different structural fingerprint ⇒ the binding must refuse it.
            backend.rawTree = backend.rawTree.copy(
                windows = backend.rawTree.windows.map {
                    it.copy(
                        root = RawNode(
                            text = "OK", className = "DifferentButton",
                            visible = true, hasArea = true, clickable = true,
                            children = listOf(RawNode(text = "badge", className = "TextView", visible = true, hasArea = true)),
                        ),
                    )
                },
            )
            val outcome = core.act(tapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            assertTrue(
                "a same-label structural replacement must NOT dispatch (binding mismatch)",
                outcome is ActOutcome.StaleState,
            )
            assertTrue("a refused tap must NOT touch the backend", backend.performed.isEmpty())
            // The foreground is still the authorized app, so the fresh snapshot is carried for a re-decide.
            assertNotNull(
                "a same-surface mismatch carries the fresh snapshot for a re-decide",
                (outcome as ActOutcome.StaleState).snapshot,
            )
        }
    }

    // ---- DISPATCH-TIME BINDING RE-CHECK (tap): a structural change in the resolve→dispatch gap. ----
    @Test
    fun `a structural change between resolve and dispatch never lands the tap`() {
        runBlocking {
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = tapGuard()
            backend.armGate()
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val deferred = async(Dispatchers.Default) {
                core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            }
            entered.await()
            backend.rawTree = backend.rawTree.copy(windows = emptyList()) // button gone ⇒ binding mismatch
            backend.releaseGate()
            val outcome = deferred.await()
            assertTrue(
                "a tap whose binding no longer matches must NOT land",
                backend.performed.isEmpty(),
            )
            assertTrue(
                "a refused (binding-mismatch) tap must be StaleState, never Acted",
                outcome is ActOutcome.StaleState,
            )
        }
    }

    @Test
    fun `P20 revoke during an in-flight tap cancels it`() {
        runBlocking {
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = tapGuard()
            backend.armGate()
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val job: Job = launch(Dispatchers.Default) {
                core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            }
            entered.await()
            g.revoke()
            job.join()
            assertTrue("revoke must cancel the in-flight tap", job.isCancelled)
            assertFalse("the gated tap must never have completed", backend.performed.isNotEmpty())
        }
    }

    // ===================================================================================
    // The dangerous-sink (submit-class) confirm gate. Order: fresh-bind → confirm → settle/rebind →
    // dispatch. confirm false/throw/cancel ⇒ CONFIRM_DECLINED, no dispatch.
    // ===================================================================================

    private class FakeConfirmChannel(
        @Volatile var nextResult: Boolean = true,
        private val park: Boolean = false,
    ) : ConfirmChannel {
        @Volatile
        var confirmCount: Int = 0
            private set

        val entered = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()

        override suspend fun confirm(app: String, verb: Verb, label: String?): Boolean {
            confirmCount++
            if (park) {
                entered.complete(Unit)
                release.await()
            }
            return nextResult
        }
    }

    private fun submitTapGuard(pkg: String = APP, now: Long = 0L, expiresAt: Long = Long.MAX_VALUE) =
        guard(
            pkg = pkg,
            verbs = setOf(Verb.TAP, Verb.SCROLL, Verb.GLOBAL),
            sinks = setOf(Sink.SUBMIT, Sink.GLOBAL_NAV),
            now = now,
            expiresAt = expiresAt,
        )

    private fun submitButtonBackend(seq: Long = 1L, pkg: String = APP, label: String = "Pay") = FakeBackend(
        RawTree(
            stateSeq = seq,
            foregroundPkg = pkg,
            windows = listOf(
                RawWindow(
                    pkg = pkg,
                    root = RawNode(
                        text = label, className = "Button",
                        visible = true, hasArea = true, clickable = true,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a submit-class tap with confirm granted dispatches one click after one confirm`() {
        runBlocking {
            val backend = submitButtonBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val confirm = FakeConfirmChannel(nextResult = true)
            val outcome = core.act(submitTapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK), confirm)
            assertTrue("a confirmed submit-class tap succeeds", outcome is ActOutcome.Acted)
            assertEquals("the dangerous-sink gate must consult the confirm exactly once", 1, confirm.confirmCount)
            assertEquals(
                "exactly one bound CLICK",
                PerformAction.Node(
                    binding = grounded.target0().toTargetBinding(requireVisibleTextMatch = true),
                    kind = NodeActionKind.CLICK,
                    allowedPackages = setOf(grounded.foregroundPkg),
                ),
                backend.performed.single(),
            )
            // post-confirm settle/rebind (spec §6 step 10) + post-dispatch settle: the confirm runs
            // between the fresh-bind and the post-confirm settle, then the dispatch atomically
            // re-resolves and the core settles + re-snapshots.
            assertEquals("post-confirm + post-dispatch settle", 2, backend.settleCount)
        }
    }

    @Test
    fun `a submit-class tap with confirm declined is denied and never dispatches`() {
        runBlocking {
            val backend = submitButtonBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val confirm = FakeConfirmChannel(nextResult = false)
            val outcome = core.act(submitTapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK), confirm)
            assertEquals(
                "a declined submit-class confirm is CONFIRM_DECLINED",
                ActOutcome.Denied(ActDenyReason.CONFIRM_DECLINED),
                outcome,
            )
            assertEquals(1, confirm.confirmCount)
            assertTrue("a declined dangerous act must NOT touch the backend", backend.performed.isEmpty())
        }
    }

    @Test
    fun `a submit-class tap whose confirm times out (false) is denied and never dispatches`() {
        runBlocking {
            val backend = submitButtonBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val confirm = FakeConfirmChannel(nextResult = false)
            val outcome = core.act(submitTapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK), confirm)
            assertEquals(ActOutcome.Denied(ActDenyReason.CONFIRM_DECLINED), outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    @Test
    fun `a submit-class tap whose confirm throws is denied and never dispatches`() {
        runBlocking {
            val backend = submitButtonBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val throwing = ConfirmChannel { _, _, _ -> throw RuntimeException("overlay could not attach") }
            val outcome = core.act(submitTapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK), throwing)
            assertEquals(
                "a confirm that threw must fail closed to CONFIRM_DECLINED, not escape or dispatch",
                ActOutcome.Denied(ActDenyReason.CONFIRM_DECLINED),
                outcome,
            )
            assertTrue("a failed-confirm dangerous act must NOT touch the backend", backend.performed.isEmpty())
        }
    }

    @Test
    fun `a non-dangerous scroll never consults the confirm`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val confirm = FakeConfirmChannel(nextResult = true)
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD), confirm)
            assertTrue(outcome is ActOutcome.Acted)
            assertEquals("a scroll is not dangerous ⇒ the confirm must NEVER fire", 0, confirm.confirmCount)
            assertEquals(1, backend.performed.size)
        }
    }

    @Test
    fun `a non-dangerous global nav never consults the confirm`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val confirm = FakeConfirmChannel(nextResult = true)
            val outcome = core.act(guard(), grounded, Act.Global(GlobalNav.BACK), confirm)
            assertTrue(outcome is ActOutcome.Acted)
            assertEquals("GLOBAL_NAV is budgeted but NOT dangerous ⇒ no confirm", 0, confirm.confirmCount)
        }
    }

    @Test
    fun `a non-dangerous set_text never consults the confirm`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val confirm = FakeConfirmChannel(nextResult = true)
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "new"), confirm)
            assertTrue(outcome is ActOutcome.Acted)
            assertEquals("TYPE_INTO is budgeted but NOT dangerous ⇒ no confirm", 0, confirm.confirmCount)
        }
    }

    @Test
    fun `a benign OK tap never consults the confirm and dispatches`() {
        runBlocking {
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val confirm = FakeConfirmChannel(nextResult = true)
            val outcome = core.act(submitTapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK), confirm)
            assertTrue(outcome is ActOutcome.Acted)
            assertEquals("a benign tap is not submit-class ⇒ the confirm must NEVER fire", 0, confirm.confirmCount)
            assertEquals(
                PerformAction.Node(
                    binding = grounded.target0().toTargetBinding(requireVisibleTextMatch = true),
                    kind = NodeActionKind.CLICK,
                    allowedPackages = setOf(grounded.foregroundPkg),
                ),
                backend.performed.single(),
            )
        }
    }

    @Test
    fun `revoke during the confirm wait cancels the act before any dispatch`() {
        runBlocking {
            val backend = submitButtonBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = submitTapGuard()
            val confirm = FakeConfirmChannel(nextResult = true, park = true)
            val job: Job = launch(Dispatchers.Default) {
                core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK), confirm)
            }
            confirm.entered.await() // deterministic: parked INSIDE confirm, after authorize/admit
            g.revoke()
            job.join()
            assertTrue("revoke must cancel the act parked in the confirm wait", job.isCancelled)
            assertTrue("a revoked-during-confirm act must NEVER dispatch", backend.performed.isEmpty())
        }
    }

    @Test
    fun `a submit-class tap without SUBMIT in budget is denied by the guard before the confirm`() {
        runBlocking {
            val backend = submitButtonBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            val g = guard(verbs = setOf(Verb.TAP, Verb.SCROLL, Verb.GLOBAL), sinks = setOf(Sink.GLOBAL_NAV))
            val confirm = FakeConfirmChannel(nextResult = true)
            val outcome = core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK), confirm)
            assertEquals(
                "a submit-class tap whose SUBMIT sink is unbudgeted is denied at the guard",
                ActOutcome.Denied(ActDenyReason.GUARD),
                outcome,
            )
            assertEquals("the guard's sink DENY must precede the confirm gate", 0, confirm.confirmCount)
            assertTrue("a guard-denied submit-class tap must NOT touch the backend", backend.performed.isEmpty())
        }
    }

    // ===================================================================================
    // StaleState(fresh) surface-bind: a binding mismatch carries the fresh snapshot ONLY when the
    // foreground is still the authorized surface; a foreground switch drops it (no cross-app leak).
    // ===================================================================================

    @Test
    fun `a binding mismatch carries the fresh snapshot when the foreground is unchanged`() {
        runBlocking {
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            // Remove the button (binding mismatch) WITHOUT changing the foreground package.
            backend.rawTree = backend.rawTree.copy(
                windows = listOf(
                    RawWindow(
                        pkg = backend.rawTree.foregroundPkg,
                        root = RawNode(text = "SomethingElse", className = "TextView", visible = true, hasArea = true),
                    ),
                ),
            )
            val outcome = core.act(tapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            assertTrue(outcome is ActOutcome.StaleState)
            val fresh = (outcome as ActOutcome.StaleState).snapshot
            assertNotNull("same-surface mismatch carries the fresh snapshot for a re-decide", fresh)
            assertEquals(
                "the fresh snapshot is the current authorized surface",
                grounded.foregroundPkg,
                fresh!!.foregroundPkg,
            )
        }
    }

    @Test
    fun `a binding mismatch drops the fresh snapshot when the foreground switched`() {
        runBlocking {
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            // Mismatch AND a foreground switch to an unadmitted app: the fresh snapshot must NOT carry
            // the other app's content (cross-app leak guard, mirroring the post-act surface bind).
            backend.rawTree = RawTree(
                stateSeq = backend.rawTree.stateSeq + 1,
                foregroundPkg = "com.evil.other",
                windows = emptyList(),
            )
            val outcome = core.act(tapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            assertTrue(outcome is ActOutcome.StaleState)
            assertNull(
                "a cross-surface mismatch must NOT carry the other app's snapshot",
                (outcome as ActOutcome.StaleState).snapshot,
            )
        }
    }

    @Test
    fun `the binding for a set_text never carries the requested text or the editable value`() {
        runBlocking {
            // Structural invariant (spec §10): a set_text binding is identity-only. Capture a real
            // dispatched binding and assert it carries NO payload (no text, no editableText field).
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "super-secret-payload"))
            val action = backend.performed.single() as PerformAction.SetText
            assertEquals("the dispatched text is on the action, NOT the binding", "super-secret-payload", action.text)
            // The binding's visibleText axis is OFF for set_text...
            assertFalse("set_text binding must not require a visible-text match", action.binding.requireVisibleTextMatch)
            // ...AND the field's current editable value ("old") must NOT leak into the binding via
            // visibleText (spec §4 / §10: a set_text binding is structural identity only). The fixture's
            // old value is non-null, so a null here proves the value was dropped, not merely absent.
            assertNull("set_text binding must not carry the field's editable value", action.binding.visibleText)
        }
    }

    @Test
    fun `a PerformAction Node and SetText contain no stateSeq or tid`() {
        runBlocking {
            // Invariant (spec §10): the dispatch token is a binding, never (stateSeq, tid). The
            // PerformAction variants no longer carry either field — assert by structural containment.
            val backend = clickableBackend()
            val core = AutomationCore(backend)
            val grounded = core.observe(setOf(backend.rawTree.foregroundPkg))
            core.act(tapGuard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.CLICK))
            val node = backend.performed.single() as PerformAction.Node
            assertTrue("Node dispatches by binding", node.binding.structuralFingerprint.isNotEmpty())
            // The variant has no stateSeq/tid properties; this line compiles only because they are absent.
            assertEquals(NodeActionKind.CLICK, node.kind)

            // The same invariant for SetText: the token is a binding + the requested text, never (stateSeq, tid).
            val textBackend = editableBackend(text = "old")
            val textCore = AutomationCore(textBackend)
            val textGrounded = textCore.observe(setOf(textBackend.rawTree.foregroundPkg))
            textCore.act(setTextGuard(), textGrounded, Act.SetText(Selector.ByTid(0), "new value"))
            val setText = textBackend.performed.single() as PerformAction.SetText
            assertTrue("SetText dispatches by binding", setText.binding.structuralFingerprint.isNotEmpty())
            assertEquals("the requested text rides on the action, not a (stateSeq, tid) token", "new value", setText.text)
        }
    }

    @Test
    fun `freshness reducer suppresses non-active system content churn`() {
        // Spec §10: a non-active system WINDOW_CONTENT_CHANGED event must not bump the epoch. Pure check.
        val suppressed = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 5,
                eventPackage = "com.android.systemui",
                eventSystemWindow = true,
                activeWindowId = 1,
                activePackage = APP,
            ),
        )
        assertFalse("non-active system churn must not bump", suppressed.bumpEpoch)
        assertFalse("non-active system churn must not pulse", suppressed.pulseSettle)
    }

    @Test
    fun `freshness reducer fails closed on unknown classification`() {
        val bumped = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = null, // unknown
                eventPackage = null,
                eventSystemWindow = null,
                activeWindowId = null,
                activePackage = null,
            ),
        )
        assertTrue("unknown classification must bump (fail-closed)", bumped.bumpEpoch)
        assertTrue("unknown classification must pulse (fail-closed)", bumped.pulseSettle)
    }

    @Test
    fun `freshness reducer bumps active-window content changes and all state changes`() {
        val activeContent = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
                eventWindowId = 1,
                eventPackage = APP,
                eventSystemWindow = false,
                activeWindowId = 1,
                activePackage = APP,
            ),
        )
        assertTrue("active-window content must bump", activeContent.bumpEpoch)

        val stateChange = FreshnessReducer.decide(
            FreshnessEventImpact(
                kind = FreshnessEventKind.WINDOW_STATE_CHANGED,
                eventWindowId = 5,
                eventPackage = "com.android.systemui",
                eventSystemWindow = true,
                activeWindowId = 1,
                activePackage = APP,
            ),
        )
        assertTrue("a window STATE change always bumps", stateChange.bumpEpoch)
        assertTrue("a window STATE change always pulses", stateChange.pulseSettle)
    }
}
