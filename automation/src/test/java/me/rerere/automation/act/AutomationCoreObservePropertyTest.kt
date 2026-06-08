package me.rerere.automation.act

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
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
 * AutomationCore read-path PBT (#187 design §8, properties P10/P11/P12 + the read-only subset of
 * MBT-b). v1 has no act verb, so the act-path properties (P8/P9/P13/P14) are out of scope by
 * construction — there is nothing to act with. These cover exactly the observe contract.
 */
class AutomationCoreObservePropertyTest {

    private val HOST = SnapshotProjector.HOST_PACKAGE

    private fun appWindow(pkg: String = "com.example.app") =
        RawWindow(pkg = pkg, root = RawNode(text = "Hello", className = "TextView", visible = true, hasArea = true))

    // ---- P10 + P11: observed stateSeq is non-decreasing and each result ≥ entry seq ----
    @Test
    fun `P10 P11 observed stateSeq is monotonic across observes`() {
        runBlocking {
            // A monotone-non-decreasing sequence of backend seqs; the core must accept all and the
            // returned seqs must never go backwards.
            checkAll(200, Arb.list(Arb.int(0..50), 1..10)) { deltas ->
                val backend = FakeBackend(
                    RawTree(stateSeq = 0L, foregroundPkg = "com.example.app", windows = listOf(appWindow())),
                )
                val core = AutomationCore(backend)
                var last = Long.MIN_VALUE
                var current = 0L
                // First observe at seq 0.
                val first = core.observe()
                assertTrue(first.stateSeq >= last)
                last = first.stateSeq
                for (d in deltas) {
                    // Advance the backend by a non-negative delta (monotone), then observe.
                    repeat(d) { backend.injectTransition() }
                    current += d
                    val snap = core.observe()
                    // P11: never decreases.
                    assertTrue("stateSeq decreased: ${snap.stateSeq} < $last", snap.stateSeq >= last)
                    // P10: result ≥ the seq at entry (which equals `last`).
                    assertTrue(snap.stateSeq >= last)
                    last = snap.stateSeq
                    assertEquals(current, snap.stateSeq)
                }
            }
        }
    }

    // ---- P11 (negative): a regressing backend is rejected, not silently accepted ----
    @Test
    fun `P11 regressing backend stateSeq is rejected`() {
        runBlocking {
            val backend = FakeBackend(
                RawTree(stateSeq = 5L, foregroundPkg = "com.example.app", windows = listOf(appWindow())),
            )
            val core = AutomationCore(backend)
            core.observe() // observes seq 5
            // Force the backend backwards (a bug a real a11y backend must never do).
            backend.rawTree = backend.rawTree.copy(stateSeq = 2L)
            try {
                core.observe()
                throw AssertionError("expected the core to reject a regressing stateSeq")
            } catch (e: IllegalStateException) {
                // expected: monotonicity is enforced, not papered over.
            }
        }
    }

    // ---- P12: foreground == host ⇒ FOREGROUND_IS_HOST, no targets (the agent pauses) ----
    @Test
    fun `P12 host foreground pauses with no targets`() {
        runBlocking {
            checkAll(200, Arb.element(listOf("com.example.app", HOST))) { foreground ->
                val backend = FakeBackend(
                    RawTree(stateSeq = 1L, foregroundPkg = foreground, windows = listOf(appWindow())),
                )
                val core = AutomationCore(backend)
                val snap = core.observe()
                if (foreground == HOST) {
                    assertEquals(ScreenState.FOREGROUND_IS_HOST, snap.screenState)
                    assertTrue("host foreground must yield no targets", snap.targets.isEmpty())
                    assertTrue(core.isHostForeground(snap))
                } else {
                    assertTrue(snap.screenState != ScreenState.FOREGROUND_IS_HOST)
                }
            }
        }
    }

    // ---- MBT-b (read-only subset): Observe / InjectTransition / GoHost / Idle ----
    @Test
    fun `MBT-b read-only state machine keeps invariants`() {
        runBlocking {
            val arbCmd: Arb<MbtCmd> = Arb.element(MbtCmd.entries)
            checkAll(150, Arb.list(arbCmd, 0..30)) { commands ->
                val backend = FakeBackend(
                    RawTree(stateSeq = 0L, foregroundPkg = "com.example.app", windows = listOf(appWindow())),
                )
                val core = AutomationCore(backend)
                var lastObserved = Long.MIN_VALUE
                for (cmd in commands) {
                    when (cmd) {
                        MbtCmd.OBSERVE -> {
                            val snap = core.observe()
                            // Invariant 1: stateSeq monotonic across every observe.
                            assertTrue("stateSeq regressed in SM", snap.stateSeq >= lastObserved)
                            lastObserved = snap.stateSeq
                            // Invariant 2: host foreground always pauses (no targets).
                            if (backend.rawTree.foregroundPkg == HOST) {
                                assertEquals(ScreenState.FOREGROUND_IS_HOST, snap.screenState)
                                assertTrue(snap.targets.isEmpty())
                            }
                        }
                        MbtCmd.INJECT_TRANSITION -> backend.injectTransition()
                        MbtCmd.GO_HOST -> backend.setForeground(HOST)
                        MbtCmd.IDLE -> { /* no-op */ }
                    }
                }
            }
        }
    }
}

/** Read-only command alphabet for MBT-b (top-level: Kotlin forbids local named objects/enums). */
private enum class MbtCmd { OBSERVE, INJECT_TRANSITION, GO_HOST, IDLE }
