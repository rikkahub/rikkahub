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
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.automation.observe.Selector
import me.rerere.automation.observe.UiFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Act-path state-machine PBT (#198 slice 8, design §1 — the act SM on the proven OCap kernel). Every
 * property is written so a naive "just dispatch it" act FAILS it; the real fail-closed
 * [AutomationCore.act] passes. All run over [FakeBackend] + a hand-pinned [TrustClock]; the act
 * vocabulary is the lowest-risk nav (scroll / global). The TOCTOU core is MR3 / P8 / assert-both; the
 * admission core is guard-before (S2) / revoke-in-flight (P20); ambiguity fails closed (I-act-9).
 */
class AutomationCoreActPropertyTest {

    private val APP = "com.example.app"

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
            surface = setOf(pkg),
            verbs = verbs,
            sinkBudget = sinks,
            lease = Lease(expiresAt = expiresAt, maxSteps = 1000),
        ),
        TrustClock { now },
    )

    // ---- MR3: raising stateSeq after the snapshot ⇒ act(old grounding) NEVER dispatches ----
    @Test
    fun `MR3 a stateSeq advance after grounding never dispatches`() {
        runBlocking {
            checkAll(300, Arb.int(1..1000)) { advance ->
                val backend = backend()
                val core = AutomationCore(backend)
                val grounded = core.observe() // grounded @ seq 1
                repeat(advance) { backend.injectTransition() } // the screen moved under the model
                val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
                assertEquals(ActOutcome.StaleState, outcome)
                assertTrue("MR3: a stale act must NOT touch the backend", backend.performed.isEmpty())
            }
        }
    }

    // ---- P8: a tid only valid for its snapshot's seq ⇒ acting on a stale grounding is STALE_STATE ----
    @Test
    fun `P8 acting on a tid from a superseded snapshot is StaleState`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe()
            backend.injectTransition() // a new state arrived; the grounded tids are now invalid
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            assertEquals(ActOutcome.StaleState, outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- P8 (resolve): a tid that does not exist in the grounding is STALE_STATE (re-observe) ----
    @Test
    fun `P8 a tid absent from the grounding is StaleState`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe()
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(999), NodeActionKind.SCROLL_FORWARD))
            assertEquals(ActOutcome.StaleState, outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- assert-both: seq EQUAL but content hash changed (dropped event) ⇒ STALE_STATE ----
    @Test
    fun `assert-both a content-hash change at equal seq is StaleState`() {
        runBlocking {
            checkAll(200, Arb.string(1..12)) { newHash ->
                val backend = backend()
                val core = AutomationCore(backend)
                val grounded = core.observe() // captures windowContentHash for seq 1
                // A dropped WINDOW_STATE event: content changed but the seq stayed equal.
                if (newHash != grounded.windowContentHash) {
                    backend.setContentHash(grounded.stateSeq, newHash)
                    val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
                    assertEquals("seq-equal but hash-changed must be STALE", ActOutcome.StaleState, outcome)
                    assertTrue(backend.performed.isEmpty())
                }
            }
        }
    }

    // ---- S2: a scroll without the SCROLL verb is denied and NEVER touches the backend ----
    @Test
    fun `S2 scroll without the SCROLL verb is denied before dispatch`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe()
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
            val grounded = core.observe()
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
            val grounded = core.observe()
            val g = guard(now = 100, expiresAt = 50) // now > expiresAt
            val outcome = core.act(g, grounded, Act.Global(GlobalNav.HOME))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- host-pause (I-act-6 / P12 extended): act while host-foreground ⇒ no dispatch ----
    // The grounding's screenState is FOREGROUND_IS_HOST, so the act must refuse BEFORE authorize and
    // never touch the backend — for BOTH Act.Global (skips resolve) and Act.Targeted. Crucially the
    // guard's surface INCLUDES the host here, so the refusal can ONLY come from the host-pause check,
    // proving I-act-6 does not silently piggyback on the surface-empty DENY (the named-invariant model).
    @Test
    fun `act while host-foreground never dispatches (global)`() {
        runBlocking {
            val host = me.rerere.automation.observe.SnapshotProjector.HOST_PACKAGE
            val backend = backend(pkg = host)
            val core = AutomationCore(backend)
            val grounded = core.observe()
            assertEquals(
                "fixture must be a host-foreground grounding",
                me.rerere.automation.observe.ScreenState.FOREGROUND_IS_HOST,
                grounded.screenState,
            )
            // Surface admits the host, so a naive act WOULD pass authorize and dispatch.
            val g = guard(pkg = host)
            val outcome = core.act(g, grounded, Act.Global(GlobalNav.BACK))
            assertEquals(ActOutcome.StaleState, outcome)
            assertTrue("host-foreground must NOT dispatch a global nav", backend.performed.isEmpty())
        }
    }

    // Targeted companion: a host grounding projects NO targets (the projector strips host windows),
    // so for a targeted act host-pause and the resolve-NotFound check AGREE on StaleState — this is a
    // same-outcome consistency check, not the independent regression (the `(global)` case above, which
    // skips resolve, is the one that FAILS on the unfixed code). Pinned so a future change that lets a
    // targeted act reach dispatch on a host grounding is caught.
    @Test
    fun `act while host-foreground never dispatches (targeted)`() {
        runBlocking {
            val host = me.rerere.automation.observe.SnapshotProjector.HOST_PACKAGE
            val backend = backend(pkg = host)
            val core = AutomationCore(backend)
            val grounded = core.observe()
            val g = guard(pkg = host)
            val outcome = core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            assertEquals(ActOutcome.StaleState, outcome)
            assertTrue("host-foreground must NOT dispatch a scroll", backend.performed.isEmpty())
        }
    }

    // ---- I-act-9: an ambiguous selector is a DENY (fail closed), never a guess ----
    @Test
    fun `ambiguous selector is denied not guessed`() {
        runBlocking {
            // Two visible text targets share the same label: ByText must refuse rather than pick one.
            val ambiguousBackend = FakeBackend(
                RawTree(
                    stateSeq = 1L, foregroundPkg = APP,
                    windows = listOf(
                        RawWindow(
                            pkg = APP,
                            // non-target container (invisible, no area/id/text) so only the two leaves project
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
            val grounded = core.observe()
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
            val grounded = core.observe()
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            assertTrue(outcome is ActOutcome.Acted)
            val snap = (outcome as ActOutcome.Acted).snapshot
            assertTrue("act success is the re-grounding postcondition", snap.stateSeq > grounded.stateSeq)
            assertEquals(1, backend.performed.size)
            assertEquals(
                PerformAction.Node(stateSeq = grounded.stateSeq, tid = 0, kind = NodeActionKind.SCROLL_FORWARD),
                backend.performed.single(),
            )
            assertEquals("settle runs exactly once per act", 1, backend.settleCount)
        }
    }

    // ---- happy path (global): an authorized BACK dispatches a global action ----
    @Test
    fun `a fresh authorized global nav dispatches a global action`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe()
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
            val grounded = core.observe()
            val g = guard()
            backend.armGate() // the next perform() parks until the owning coroutine is cancelled
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val job: Job = launch(Dispatchers.Default) {
                core.act(g, grounded, Act.Global(GlobalNav.BACK))
            }
            entered.await() // deterministic: the act has passed resolve/assert/authorize and is parked in perform
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
            val grounded = core.observe()
            val g = guard()
            g.revoke()
            val outcome = core.act(g, grounded, Act.Global(GlobalNav.BACK))
            // A revoked capability fails the guard's REVOKED branch at authorize ⇒ Denied(GUARD),
            // never reaching the in-flight block.
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- I-act-1 / MR3 (dispatch-time TOCTOU): a WINDOW_STATE/CONTENT event that lands AFTER the
    // core's assert but BEFORE the node dispatch must NOT scroll the (now newer) tree. The core's
    // pre-dispatch assert is necessary but not load-bearing on its own — the backend re-verifies the
    // carried PerformAction.Node.stateSeq at dispatch (atomically with its walk). Reproduced here by
    // parking perform on the gate, advancing the seq while parked, then releasing: the carried seq no
    // longer matches the backend's live seq, so the dispatch must no-op. On the unfixed code (carried
    // stateSeq ignored at dispatch) this FAILS — the scroll lands on a tree the core never asserted.
    @Test
    fun `MR3 a stateSeq advance between assert and dispatch never lands the scroll`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe() // grounded @ seq 1
            val g = guard()
            backend.armGate() // the next perform() parks until released
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val deferred = async(Dispatchers.Default) {
                core.act(g, grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            }
            entered.await() // deterministic: parked in perform, having passed resolve/assert/authorize
            backend.injectTransition() // a content event arrives in the assert→dispatch gap (seq 1→2)
            backend.releaseGate()
            val outcome = deferred.await()
            assertTrue(
                "a node dispatch whose carried stateSeq is stale must NOT land (I-act-1/MR3)",
                backend.performed.isEmpty(),
            )
            // F4: the backend's no-dispatch false must surface as StaleState, NOT a falsely-reported
            // Acted — core.act honors perform()'s refusal instead of settling + re-snapshotting anyway.
            assertEquals(
                "a refused (stale) dispatch must be StaleState, never Acted (F4)",
                ActOutcome.StaleState,
                outcome,
            )
        }
    }

    // ---- post-act surface bind (gate finding): the re-snapshot must NOT disclose an app the
    // capability never admitted. A global nav can surface a DIFFERENT app (HOME → launcher); the act
    // authorized against the GROUNDED foreground, so a re-snapshot that left that surface is
    // StaleState (the model must re-observe, and ui_observe re-authorizes the new surface), never an
    // Acted carrying the other app's content. Reproduced by switching the foreground mid-act. On the
    // unfixed code (re-snapshot returned unconditionally) this FAILS — the launcher leaks as Acted.
    @Test
    fun `a global nav that switches to an unadmitted app is StaleState not Acted`() {
        runBlocking {
            val backend = backend()
            val core = AutomationCore(backend)
            val grounded = core.observe() // grounded @ APP
            val g = guard() // surface = {APP} only
            backend.armGate()
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val deferredOutcome = async(Dispatchers.Default) {
                core.act(g, grounded, Act.Global(GlobalNav.HOME))
            }
            entered.await() // deterministic: parked in perform (Global skips the node seq re-check)
            backend.setForeground("com.android.launcher") // HOME surfaced a different, unadmitted app
            backend.releaseGate()
            val outcome = deferredOutcome.await()
            assertEquals(
                "a re-snapshot off the authorized surface must be StaleState, never Acted(other app)",
                ActOutcome.StaleState,
                outcome,
            )
        }
    }

    // ===================================================================================
    // #198 slice 9 — ui_set_text input sink + P9 (restricted idempotency) + self-heal (P14/MR2).
    // The new act verb is Verb.SET_TEXT over Sink.TYPE_INTO (the input sink). Each property below is
    // written so a naive "always dispatch the set_text" core FAILS it; the fail-closed core passes.
    // ===================================================================================

    /** A guard whose authority covers SET_TEXT + the TYPE_INTO input sink for [pkg] (maximally OK). */
    private fun setTextGuard(pkg: String = APP, now: Long = 0L, expiresAt: Long = Long.MAX_VALUE) =
        guard(
            pkg = pkg,
            verbs = setOf(Verb.SET_TEXT, Verb.SCROLL, Verb.GLOBAL),
            sinks = setOf(Sink.TYPE_INTO, Sink.GLOBAL_NAV),
            now = now,
            expiresAt = expiresAt,
        )

    /**
     * A backend with a single editable text field (tid 0) carrying [text] plus a stable resourceId
     * (→ formKey, set by the projector only for editable nodes) and contentDescription (→ semanticKey).
     * So Selector.ByTid / ByFormKey / BySemanticKey all resolve it, and the projected [UiTarget.text]
     * is the P9 postcondition source.
     */
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

    // ---- P9 (no-op): set_text whose target already shows the requested text does NOT dispatch ----
    // The postcondition (field == requested text) already holds, so core.act returns Acted WITHOUT
    // touching the backend (no perform, no settle, no re-snapshot). On a naive "always dispatch" core
    // this FAILS — a redundant set_text is recorded and the screen is needlessly settled.
    @Test
    fun `P9 a set_text equal to the projected text is a no-op and never dispatches`() {
        runBlocking {
            val backend = editableBackend(text = "hello")
            val core = AutomationCore(backend)
            val grounded = core.observe()
            assertEquals("fixture must project the field's text", "hello", grounded.targets.first().text)
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "hello"))
            assertTrue("an idempotent set_text still succeeds", outcome is ActOutcome.Acted)
            assertTrue("P9: a no-op set_text must NOT touch the backend", backend.performed.isEmpty())
            assertEquals("P9: a no-op set_text must NOT settle", 0, backend.settleCount)
            assertEquals(
                "a no-op returns the unchanged grounding (no re-snapshot)",
                grounded.stateSeq,
                (outcome as ActOutcome.Acted).snapshot.stateSeq,
            )
        }
    }

    // ---- P9 (clean-postcondition): an EMPTY field whose contentDescription HINT equals the requested
    // text MUST dispatch, never no-op. UiTarget.text is a DISPLAY projection (node.text ?:
    // contentDescription), so a blank field (node.text == null) with contentDescription == "Email"
    // projects text = "Email"; comparing the P9 no-op against `text` would match set_text("Email") and
    // skip the dispatch, leaving the field EMPTY while the model believes the write landed (data loss,
    // design §3 "clean postconditions only" violated). The no-op must compare the editable VALUE
    // (UiTarget.editableText, null for an empty field), so this dispatches exactly one SetText. On the
    // unfixed code (compare against `text`) this FAILS — performed is empty and the field stays blank.
    @Test
    fun `P9 set_text into an empty field whose hint equals the text dispatches not no-op`() {
        runBlocking {
            // node.text = null (empty field) but contentDescription = "Email" (a label/hint). The
            // projector still makes it an editable target (editable + hasText via contentDescription).
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
            val grounded = core.observe()
            val field = grounded.targets.first()
            assertEquals("the display projection falls back to the hint", "Email", field.text)
            assertEquals("the editable value of a blank field is null (not the hint)", null, field.editableText)

            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "Email"))
            assertTrue("writing the hint text into the blank field must dispatch", outcome is ActOutcome.Acted)
            assertEquals(
                "the blank field must receive exactly one SetText (not a silent no-op)",
                PerformAction.SetText(stateSeq = grounded.stateSeq, tid = 0, text = "Email"),
                backend.performed.single(),
            )
            assertEquals("a real write settles exactly once", 1, backend.settleCount)
        }
    }

    // ---- P9 (dispatch): a DIFFERENT text dispatches exactly one PerformAction.SetText and settles ----
    @Test
    fun `P9 a set_text different from the projected text dispatches once and re-grounds`() {
        runBlocking {
            checkAll(200, Arb.string(1..12)) { newText ->
                if (newText != "hello") {
                    val backend = editableBackend(text = "hello")
                    val core = AutomationCore(backend)
                    val grounded = core.observe()
                    val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), newText))
                    assertTrue("a changing set_text dispatches", outcome is ActOutcome.Acted)
                    assertEquals(
                        "exactly one PerformAction.SetText on the grounded (stateSeq, tid)",
                        PerformAction.SetText(stateSeq = grounded.stateSeq, tid = 0, text = newText),
                        backend.performed.single(),
                    )
                    assertEquals("settle runs exactly once per dispatched act", 1, backend.settleCount)
                    assertTrue(
                        "act success is the re-grounding postcondition",
                        (outcome as ActOutcome.Acted).snapshot.stateSeq > grounded.stateSeq,
                    )
                }
            }
        }
    }

    // ---- P14/MR2 (self-heal, metamorphic): a stable key (formKey / semanticKey) resolves against a
    // benignly-reflowed re-projection (same keys, DIFFERENT tid order) and still dispatches the right
    // element. A bare positional ByTid would scroll the WRONG node after the reflow; a stable key
    // re-resolves to the CURRENT snapshot's tid. The seq+hash assert (step 2) still runs afterward, so
    // this is NOT a TOCTOU bypass — the metamorphic relation is "re-projection with the same key ⇒ the
    // act lands on the element bearing that key, wherever it now sits". On a positional-only core FAILS.
    @Test
    fun `P14 self-heal a stable key resolves under a reflow and dispatches the keyed element`() {
        runBlocking {
            // Two editable fields; the TARGET field carries the stable keys. We then re-ground a tree
            // where the field order is swapped (its tid changes 1→0): the key must still find it.
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
            // Ground with the target as tid 0, then reflow so the target is now tid 1 at a new seq.
            val backend = FakeBackend(twoFieldTree(seq = 1L, targetFirst = true))
            val core = AutomationCore(backend)
            core.observe() // monotonic seq bookkeeping; grounded below is the reflowed one
            backend.rawTree = twoFieldTree(seq = 2L, targetFirst = false)
            val grounded = core.observe()
            val targetTid = grounded.targets.first { it.formKey == "com.example.app:id/target" }.tid
            assertEquals("the reflow must have moved the target off tid 0", 1, targetTid)

            // ByFormKey heals to the current tid (1), dispatching there — not at positional 0.
            val byForm = core.act(
                setTextGuard(), grounded,
                Act.SetText(Selector.ByFormKey("com.example.app:id/target"), "new"),
            )
            assertTrue(byForm is ActOutcome.Acted)
            assertEquals(
                "ByFormKey must dispatch the keyed element's CURRENT tid (self-heal)",
                PerformAction.SetText(stateSeq = grounded.stateSeq, tid = targetTid, text = "new"),
                backend.performed.single(),
            )
        }
    }

    @Test
    fun `P14 self-heal BySemanticKey resolves the keyed editable element`() {
        runBlocking {
            val backend = editableBackend(text = "old", contentDescription = "name-field")
            val core = AutomationCore(backend)
            val grounded = core.observe()
            val outcome = core.act(
                setTextGuard(), grounded,
                Act.SetText(Selector.BySemanticKey("name-field"), "new"),
            )
            assertTrue(outcome is ActOutcome.Acted)
            assertEquals(
                PerformAction.SetText(stateSeq = grounded.stateSeq, tid = 0, text = "new"),
                backend.performed.single(),
            )
        }
    }

    // ---- MR2 (ambiguous key): two targets share the stable key ⇒ Denied(AMBIGUOUS), never a guess ----
    @Test
    fun `set_text with an ambiguous formKey is denied not guessed`() {
        runBlocking {
            // Two editable fields sharing the SAME resourceId ⇒ same formKey ⇒ ByFormKey is ambiguous.
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
            val grounded = core.observe()
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
            val grounded = core.observe()
            // Verb omitted (only SCROLL granted), but TYPE_INTO sink IS in budget — so the ONLY thing
            // that can refuse is the verb branch, proving the verb gate is reached before dispatch.
            val g = guard(verbs = setOf(Verb.SCROLL), sinks = setOf(Sink.TYPE_INTO))
            val outcome = core.act(g, grounded, Act.SetText(Selector.ByTid(0), "new"))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("S2: a denied set_text must NOT touch the backend", backend.performed.isEmpty())
        }
    }

    // ---- S2 (sink-in-budget): a set_text whose TYPE_INTO sink is NOT in budget is denied ----
    @Test
    fun `S2 set_text without the TYPE_INTO sink budget is denied before dispatch`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe()
            // SET_TEXT verb granted, but the TYPE_INTO sink budget is empty: the sink-in-budget branch
            // must DENY (a write verb laundering a sink that was never budgeted).
            val g = guard(verbs = setOf(Verb.SET_TEXT), sinks = emptySet())
            val outcome = core.act(g, grounded, Act.SetText(Selector.ByTid(0), "new"))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue(backend.performed.isEmpty())
        }
    }

    // ---- HEADLINE input-sink safety (sensitiveNode): a set_text into a PASSWORD field is DENIED,
    // never dispatched and never a no-op short-circuit. The provenance is UiFlag.PASSWORD on the
    // projected target; core maps it to AuthRequest.sensitiveNode and the guard DENYs (I8/P18). On a
    // core that forgets to set sensitiveNode on the SetText path, the password field is typed into —
    // the single worst input-sink failure (writing into a credential field). This pins it closed.
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
            val grounded = core.observe()
            val pw = grounded.targets.first()
            assertTrue("fixture must project a password field", pw.flags.contains(UiFlag.PASSWORD))
            // Surface admits APP, SET_TEXT verb + TYPE_INTO sink are granted: the ONLY refusal source
            // is the sensitive-node DENY branch, proving the headline safety check is reached.
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
            val grounded = core.observe()
            assertTrue(
                "the projected target must carry its system-window provenance",
                grounded.targets.first().systemWindow,
            )
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "x"))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("a system-UI set_text must NOT touch the backend", systemBackend.performed.isEmpty())
        }
    }

    // ---- MR3 (shared SM, via SetText): a stateSeq advance after grounding ⇒ set_text NEVER dispatches.
    // The TOCTOU assert is shared with scroll/global — exercising it through the new variant proves the
    // input sink inherits the same freshness guarantee, not a separate (possibly weaker) path.
    @Test
    fun `MR3 a stateSeq advance after grounding never dispatches a set_text`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe()
            backend.injectTransition() // the screen moved under the model
            val outcome = core.act(setTextGuard(), grounded, Act.SetText(Selector.ByTid(0), "new"))
            assertEquals(ActOutcome.StaleState, outcome)
            assertTrue("MR3: a stale set_text must NOT touch the backend", backend.performed.isEmpty())
        }
    }

    // ---- dispatch-time stale re-check (carried stateSeq mismatch ⇒ SetText not recorded). Mirrors the
    // scroll MR3-at-dispatch test: an event lands in the assert→dispatch gap; the carried SetText
    // stateSeq no longer matches the live seq, so FakeBackend.perform refuses (false) ⇒ StaleState. ----
    @Test
    fun `MR3 a stateSeq advance between assert and dispatch never lands the set_text`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe()
            val g = setTextGuard()
            backend.armGate()
            val entered = CompletableDeferred<Unit>().also { backend.performEntered = it }
            val deferred = async(Dispatchers.Default) {
                core.act(g, grounded, Act.SetText(Selector.ByTid(0), "new"))
            }
            entered.await() // parked in perform, having passed resolve/assert/authorize
            backend.injectTransition() // a content event arrives in the assert→dispatch gap
            backend.releaseGate()
            val outcome = deferred.await()
            assertTrue(
                "a set_text whose carried stateSeq is stale must NOT land (I-act-1/MR3)",
                backend.performed.isEmpty(),
            )
            assertEquals(
                "a refused (stale) dispatch must be StaleState, never Acted (F4)",
                ActOutcome.StaleState,
                outcome,
            )
        }
    }

    // ---- P20 (revoke-in-flight, via SetText): revoke during a parked set_text cancels it ----
    @Test
    fun `P20 revoke during an in-flight set_text cancels it`() {
        runBlocking {
            val backend = editableBackend(text = "old")
            val core = AutomationCore(backend)
            val grounded = core.observe()
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
    // system/permission window must be DENIED before dispatch. The provenance travels on
    // UiTarget.systemWindow (set by the projector from RawWindow.systemWindow) and the core maps it
    // to AuthRequest.systemUiTarget, so the guard's system-UI DENY branch is reachable. On the
    // unfixed code (systemUiTarget never set) this FAILS — the system-window scroll authorizes and
    // dispatches, making the guard's system-UI branch dead code (#187's "grant UI non-actionable" gate).
    @Test
    fun `a scroll targeting a system-window node is denied before dispatch`() {
        runBlocking {
            // A system/permission window (e.g. a runtime-permission grant dialog) with a scrollable node.
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
            val grounded = core.observe()
            assertTrue("fixture should project a system-window target", grounded.targets.isNotEmpty())
            assertTrue(
                "the projected target must carry its system-window provenance",
                grounded.targets.first().systemWindow,
            )
            // The surface admits APP and the SCROLL verb is granted, so the ONLY thing that can refuse
            // this is the system-UI DENY branch — proving I-act-3 does not piggyback on another check.
            val outcome = core.act(guard(), grounded, Act.Targeted(Selector.ByTid(0), NodeActionKind.SCROLL_FORWARD))
            assertEquals(ActOutcome.Denied(ActDenyReason.GUARD), outcome)
            assertTrue("a system-UI scroll must NOT touch the backend", systemBackend.performed.isEmpty())
        }
    }
}
