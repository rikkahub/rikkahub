package me.rerere.rikkahub.service.automation

import kotlinx.coroutines.runBlocking
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.backend.FakeBackend
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import me.rerere.automation.observe.ScreenState
import me.rerere.automation.observe.SnapshotProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented conformance test for the [AutomationBackend] seam (#187 design B8 / maintainer note).
 *
 * IMPORTANT — THIS IS NOT A CI GATE. This project's CI runs JVM unit tests only
 * (`testDebugUnitTest`); `androidTest` is excluded by construction, so this file never gates a PR.
 * The pure-kernel proofs (#205: P1–P25 + S1/S2 + MBT-a over [FakeBackend]) are the logic gate, and
 * the `ui_observe` factory is JVM-tested in
 * `app/src/test/.../UiAutomationToolsTest.kt`. This test exists to DOCUMENT the contract that BOTH
 * backends must satisfy and to let a developer exercise it on a device/emulator.
 *
 * The real [AccessibilityRuntime] half additionally requires a device/emulator WITH the RikkaHub
 * accessibility service enabled in system settings (Settings → Accessibility → RikkaHub UI
 * Automation). With the service connected, `AccessibilityRuntime.instance` is non-null and the same
 * three contract properties below must hold against it:
 *   1. stateSeq is monotonic across window state/content events (never decreases);
 *   2. snapshotRawTree() returns a value tree that EXCLUDES the host package from projected targets;
 *   3. windowContentHash(seq) is stable for an unchanged window and changes when content changes.
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

    private fun appTree(stateSeq: Long): RawTree = RawTree(
        stateSeq = stateSeq,
        foregroundPkg = "com.example.target",
        windows = listOf(
            RawWindow(
                pkg = "com.example.target",
                root = RawNode(text = "Continue", className = "android.widget.Button", clickable = true),
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
        val snapshot = projector.project(backend(withHostWindow).snapshotRawTree())

        assertTrue(
            "host package content must never reach the projected targets",
            snapshot.targets.none { it.text == "host chrome" },
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
    fun contract_windowContentHash_is_stable_for_unchanged_window_and_changes_on_content_change() {
        val fake = FakeBackend(appTree(stateSeq = 9L))
        fake.setContentHash(9L, "hash-A")
        fake.setContentHash(10L, "hash-B")

        assertEquals("stable hash for an unchanged window", "hash-A", fake.windowContentHash(9L))
        assertTrue("a changed window yields a different hash", fake.windowContentHash(10L) != fake.windowContentHash(9L))
    }

    @Test
    fun contract_host_foreground_yields_pause_state_with_no_targets() = runBlocking {
        val hostForeground = RawTree(
            stateSeq = 2L,
            foregroundPkg = "me.rerere.rikkahub",
            windows = listOf(RawWindow(pkg = "me.rerere.rikkahub", root = RawNode(text = "chat"))),
        )
        val snapshot = projector.project(backend(hostForeground).snapshotRawTree())
        assertEquals(ScreenState.FOREGROUND_IS_HOST, snapshot.screenState)
        assertTrue(snapshot.targets.isEmpty())
    }
}
