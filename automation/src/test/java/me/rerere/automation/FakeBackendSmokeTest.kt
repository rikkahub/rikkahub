package me.rerere.automation

import kotlinx.coroutines.runBlocking
import me.rerere.automation.act.AutomationCore
import me.rerere.automation.backend.FakeBackend
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import me.rerere.automation.observe.ScreenState
import me.rerere.automation.observe.SnapshotProjector
import me.rerere.automation.observe.UiFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Module compile + wiring guard (#187 PR1). Proves the :automation module builds and that a raw
 * tree round-trips through [FakeBackend] -> [AutomationCore] -> a projected [UiSnapshot]. Cheap,
 * deterministic, example-based — the floor under the property suites.
 */
class FakeBackendSmokeTest {

    @Test
    fun `fake backend round-trips a tree into a projected snapshot`() {
        runBlocking {
            val tree = RawTree(
                stateSeq = 7L,
                foregroundPkg = "com.example.app",
                windows = listOf(
                    RawWindow(
                        pkg = "com.example.app",
                        root = RawNode(
                            resourceId = "id/login",
                            text = "Sign in",
                            className = "Button",
                            visible = true,
                            hasArea = true,
                            clickable = true,
                            children = listOf(
                                RawNode(
                                    resourceId = "id/pw",
                                    text = "topsecret",
                                    className = "EditText",
                                    visible = true,
                                    hasArea = true,
                                    editable = true,
                                    password = true,
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val backend = FakeBackend(tree)
            val core = AutomationCore(backend, SnapshotProjector())

            val snap = core.observe()

            assertEquals(7L, snap.stateSeq)
            assertEquals("com.example.app", snap.foregroundPkg)
            assertEquals(ScreenState.READY, snap.screenState)
            // Both nodes pass the projection rule.
            assertEquals(2, snap.targets.size)
            // The password field is masked — bullets, never the real value.
            val pw = snap.targets.first { UiFlag.PASSWORD in it.flags }
            assertTrue("password not masked", pw.text?.all { it == '•' } == true)
            assertTrue("password text leaked", pw.text?.contains("topsecret") != true)
            // The backend was actually hit exactly once.
            assertEquals(1, backend.snapshotCount)
        }
    }
}
