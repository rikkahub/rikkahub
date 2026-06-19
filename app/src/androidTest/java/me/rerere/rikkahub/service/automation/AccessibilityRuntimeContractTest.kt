package me.rerere.rikkahub.service.automation

import kotlinx.coroutines.runBlocking
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.backend.BindingRequest
import me.rerere.automation.backend.FakeBackend
import me.rerere.automation.backend.FreshnessDecision
import me.rerere.automation.backend.FreshnessEventImpact
import me.rerere.automation.backend.FreshnessEventKind
import me.rerere.automation.backend.FreshnessReducer
import me.rerere.automation.backend.NodeActionKind
import me.rerere.automation.backend.PerformAction
import me.rerere.automation.backend.PerformResult
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import me.rerere.automation.observe.ScreenState
import me.rerere.automation.observe.SnapshotProjector
import me.rerere.automation.observe.UiFlag
import me.rerere.automation.observe.toTargetBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented conformance test for the [AutomationBackend] seam (#187 design B8 / maintainer note),
 * updated for the eyes-open hybrid tap design: the contract BOTH backends must satisfy now centers on
 * (a) `windowId` propagation through projection, (b) bound dispatch via [AutomationBackend.resolveBinding]
 * / [AutomationBackend.perform] returning [PerformResult], and (c) the pure [FreshnessReducer] that
 * decides whether a window event should advance the monotonic epoch / pulse the settle signal.
 *
 * IMPORTANT — THIS IS NOT A CI GATE. This project's CI runs JVM unit tests only
 * (`testDebugUnitTest`); `androidTest` is excluded by construction, so this file never gates a PR.
 * The pure-kernel proofs (#205: P1–P25 + S1/S2 + MBT-a over [FakeBackend], plus the eyes-open
 * binding-match / benign-churn / freshness-reducer properties) are the logic gate, and the
 * `ui_observe` / `ui_scroll` / `ui_set_text` / `ui_tap` factory is JVM-tested in
 * `app/src/test/.../UiAutomationToolsTest.kt`. This test exists to DOCUMENT the contract that BOTH
 * backends must satisfy and to let a developer exercise it on a device/emulator.
 *
 * The real [AccessibilityRuntime] half additionally requires a device/emulator WITH the RikkaHub
 * accessibility service enabled in system settings (Settings → Accessibility → RikkaHub UI
 * Automation). With the service connected, `AccessibilityRuntime.instance` is non-null and the same
 * contract properties below must hold against it:
 *   1. RawWindow.windowId / RawNode.windowId are populated from AccessibilityWindowInfo.getId() /
 *      AccessibilityNodeInfo.getWindowId() (the eyes-open binding names the SAME window across a
 *      fresh re-resolve).
 *   2. snapshotRawTree() returns a value tree that EXCLUDES the host package from projected targets.
 *   3. stateSeq advances monotonically across window state/content events, with the FreshnessReducer
 *      suppressing a classified non-active system content change (status-bar churn).
 *   4. resolveBinding() returns the unique strictly-matching target on a fresh capture; perform()
 *      returns PerformResult.Dispatched on a unique match and PerformResult.BindingMismatch on
 *      zero/multiple (the load-bearing atomic fresh-resolve + dispatch invariant).
 *
 * Because enabling the service is a manual, environment-specific step, the device-runnable assertions
 * here drive the deterministic [FakeBackend] — the reference the real backend is contractually equal
 * to — so the contract shape is exercised even on an un-provisioned emulator. The real-backend block
 * is documented above rather than auto-run so the suite never silently passes on a device where the
 * service was never enabled.
 */
class AccessibilityRuntimeContractTest {

    private val projector = SnapshotProjector()

    private fun backend(tree: RawTree): AutomationBackend = FakeBackend(tree)

    private fun appTree(stateSeq: Long, windowId: Int = 100): RawTree = RawTree(
        stateSeq = stateSeq,
        foregroundPkg = "com.example.target",
        windows = listOf(
            RawWindow(
                pkg = "com.example.target",
                root = RawNode(
                    text = "Continue",
                    className = "android.widget.Button",
                    clickable = true,
                    windowId = windowId,
                ),
                windowId = windowId,
            ),
        ),
    )

    @Test
    fun contract_snapshotRawTree_excludes_host_package_from_projection() = runBlocking {
        val withHostWindow = appTree(stateSeq = 1L).copy(
            windows = appTree(1L).windows + RawWindow(
                pkg = "me.rerere.rikkahub",
                root = RawNode(text = "host chrome", className = "android.widget.TextView"),
            ),
        )
        val snapshot = projector.project(backend(withHostWindow).snapshotRawTree(), setOf("com.example.target"))

        assertTrue(
            "host package content must never reach the projected targets",
            snapshot.targets.none { it.text == "host chrome" },
        )
    }

    @Test
    fun contract_windowId_propagates_from_raw_window_into_projected_target() {
        val snap = projector.project(appTree(stateSeq = 7L, windowId = 4242), setOf("com.example.target"))
        assertEquals(
            "the projected target must carry the owning RawWindow's windowId (binding's first axis)",
            4242,
            snap.targets.single().windowId,
        )
        assertEquals(
            "the projected target must carry the raw child-index path from the window root (binding's position axis)",
            emptyList<Int>(),
            snap.targets.single().structuralPath,
        )
        assertTrue(
            "the projected target must carry a non-empty structural fingerprint (binding's content axis)",
            snap.targets.single().structuralFingerprint.isNotEmpty(),
        )
    }

    @Test
    fun contract_stateSeq_is_monotonic_across_events() = runBlocking {
        val fake = FakeBackend(appTree(stateSeq = 5L))
        val first = fake.snapshotRawTree().stateSeq
        fake.injectTransition() // a window event advances the sequence
        val second = fake.snapshotRawTree().stateSeq
        assertTrue("stateSeq must never decrease across events", second >= first)
    }

    @Test
    fun contract_resolveBinding_returns_the_unique_strict_match_on_a_fresh_capture() = runBlocking {
        val fake = FakeBackend(appTree(stateSeq = 1L, windowId = 99))
        val grounded = projector.project(fake.snapshotRawTree(), setOf("com.example.target"))
        val target = grounded.targets.single()
        val binding = target.toTargetBinding(requireVisibleTextMatch = true)

        val resolution = fake.resolveBinding(
            BindingRequest(binding = binding, allowedPackages = setOf("com.example.target")),
        )

        assertNotNull("a unique match must resolve", resolution as? me.rerere.automation.backend.BindingResolution.Unique)
        val unique = resolution as me.rerere.automation.backend.BindingResolution.Unique
        assertEquals(
            "the resolved target must match on the same windowId / structural identity",
            target.windowId,
            unique.target.windowId,
        )
        assertEquals(target.structuralFingerprint, unique.target.structuralFingerprint)
    }

    @Test
    fun contract_perform_dispatches_on_a_unique_match_and_returns_BindingMismatch_otherwise() =
        runBlocking {
            val fake = FakeBackend(appTree(stateSeq = 1L, windowId = 7))
            val grounded = projector.project(fake.snapshotRawTree(), setOf("com.example.target"))
            val target = grounded.targets.single()
            val realBinding = target.toTargetBinding(requireVisibleTextMatch = false)

            // Unique match: dispatch lands, returns Dispatched, advances the seq.
            val dispatched = fake.perform(
                PerformAction.Node(
                    binding = realBinding,
                    kind = NodeActionKind.SCROLL_FORWARD,
                    allowedPackages = setOf("com.example.target"),
                ),
            )
            assertTrue("a unique match must dispatch", dispatched is PerformResult.Dispatched)

            // Zero match (a binding for a node that is not in the tree): no mutation, BindingMismatch.
            val foreignBinding = realBinding.copy(windowId = realBinding.windowId + 1)
            val mismatched = fake.perform(
                PerformAction.Node(
                    binding = foreignBinding,
                    kind = NodeActionKind.SCROLL_FORWARD,
                    allowedPackages = setOf("com.example.target"),
                ),
            )
            assertTrue(
                "a zero-match binding must NOT dispatch and must carry a fresh snapshot",
                mismatched is PerformResult.BindingMismatch,
            )
        }

    @Test
    fun contract_freshness_reducer_suppresses_a_non_active_system_content_change() {
        val impact = FreshnessEventImpact(
            kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
            eventWindowId = 50,
            eventPackage = "com.android.systemui",
            eventSystemWindow = true,
            activeWindowId = 7,
            activePackage = "com.example.target",
        )
        val decision = FreshnessReducer.decide(impact)
        assertEquals(
            "a classified non-active system content change must NOT advance the epoch",
            FreshnessDecision(bumpEpoch = false, pulseSettle = false),
            decision,
        )
    }

    @Test
    fun contract_freshness_reducer_fail_closes_on_unknown_classification() {
        // Unknown active window id ⇒ the reducer cannot prove the event is non-active ⇒ bump + pulse.
        val impact = FreshnessEventImpact(
            kind = FreshnessEventKind.WINDOW_CONTENT_CHANGED,
            eventWindowId = 50,
            eventPackage = "com.android.systemui",
            eventSystemWindow = true,
            activeWindowId = null, // unknown
            activePackage = null,
        )
        val decision = FreshnessReducer.decide(impact)
        assertTrue(
            "an unclassifiable event must fail closed to bump + pulse (the freshness source stays honest)",
            decision.bumpEpoch && decision.pulseSettle,
        )
    }

    @Test
    fun contract_windowContentHash_is_stable_for_unchanged_window_and_changes_on_content_change() {
        // Retained: the observe-path grounding stamp is still part of the seam, even though the
        // eyes-open act path no longer gates dispatch on it.
        val fake = FakeBackend(appTree(stateSeq = 9L))
        fake.setContentHash(9L, "hash-A")
        fake.setContentHash(10L, "hash-B")

        assertEquals("stable hash for an unchanged window", "hash-A", fake.windowContentHash(9L))
        assertFalse(
            "a changed window yields a different hash",
            fake.windowContentHash(10L) == fake.windowContentHash(9L),
        )
    }

    @Test
    fun contract_host_foreground_yields_pause_state_with_no_targets() = runBlocking {
        val hostForeground = RawTree(
            stateSeq = 2L,
            foregroundPkg = "me.rerere.rikkahub",
            windows = listOf(RawWindow(pkg = "me.rerere.rikkahub", root = RawNode(text = "chat"))),
        )
        val snapshot = projector.project(backend(hostForeground).snapshotRawTree(), setOf("me.rerere.rikkahub"))
        assertEquals(ScreenState.FOREGROUND_IS_HOST, snapshot.screenState)
        assertTrue(snapshot.targets.isEmpty())
    }

    @Test
    fun contract_projection_populates_binding_fields_that_stay_private_from_the_renderer() {
        // This is a PROJECTION-field contract (not a renderer call): it pins that the projector POPULATES
        // the eyes-open binding internals (windowId / structuralFingerprint) and keeps a password's
        // editableText null. The renderer-side guarantee that these never reach the model is asserted by
        // the JVM leak-free renderer test in UiAutomationToolsTest; this test guards the parity input.
        val snap = projector.project(appTree(stateSeq = 1L, windowId = 999), setOf("com.example.target"))
        val target = snap.targets.single()
        // The fields are POPULATED (binding-relevant), but the renderer (UiAutomationTools.kt) does
        // not surface them — pinned by the UiAutomationToolsTest leak-free assertions. Here we just
        // pin that the projector does populate them (so a future renderer change that started
        // surfacing them would be a real regression in :app, not a silent parity drift in :automation).
        assertEquals(999, target.windowId)
        assertTrue(target.structuralFingerprint.isNotEmpty())
        // A password target's editableText must be null even though it is "editable" (masking rule).
        val passwordTree = RawTree(
            stateSeq = 3L,
            foregroundPkg = "com.example.target",
            windows = listOf(
                RawWindow(
                    pkg = "com.example.target",
                    root = RawNode(
                        text = "secret",
                        className = "android.widget.EditText",
                        editable = true,
                        password = true,
                        windowId = 1,
                    ),
                    windowId = 1,
                ),
            ),
        )
        val pwSnap = projector.project(passwordTree, setOf("com.example.target"))
        val pwTarget = pwSnap.targets.single()
        assertTrue("password flag must travel on the target", UiFlag.PASSWORD in pwTarget.flags)
        assertEquals(
            "a password editable value must never escape through editableText",
            null,
            pwTarget.editableText,
        )
    }
}
