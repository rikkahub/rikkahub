package me.rerere.automation.act

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settle-policy PBT (#198 slice 8, design §1 step 6 / D3 / property P13). The act path must wait for
 * the screen to settle with a quiet-window-plus-hard-cap debounce, NEVER a fixed sleep. Stating the
 * decision as a pure function ([SettlePolicy.settleOffsetMs]) makes P13 a hermetic boundary property
 * instead of a brittle wall-clock assertion.
 */
class SettlePolicyPropertyTest {

    private val policy = SettlePolicy(quietWindowMs = 250, hardCapMs = 1500)

    // ---- P13 (boundary invariant): settle is ALWAYS within [quietWindow, hardCap], for any events ----
    @Test
    fun `P13 settle offset is always within quiet-window and hard-cap`() {
        runBlocking {
            checkAll(500, Arb.list(Arb.long(-100L..3000L), 0..20)) { events ->
                val settle = policy.settleOffsetMs(events)
                assertTrue("settle $settle < quietWindow", settle >= policy.quietWindowMs)
                assertTrue("settle $settle > hardCap", settle <= policy.hardCapMs)
            }
        }
    }

    // ---- P13 boundary: no events ⇒ exactly the quiet window (the floor) ----
    @Test
    fun `P13 no events settles at the quiet window`() {
        assertEquals(250L, policy.settleOffsetMs(emptyList()))
    }

    // ---- P13 boundary: a single event pushes settle to event + quietWindow ----
    @Test
    fun `P13 a single event extends settle by one quiet window`() {
        assertEquals(350L, policy.settleOffsetMs(listOf(100L)))
    }

    // ---- P13 boundary: an event after a full quiet window cannot delay the earlier settle ----
    @Test
    fun `P13 an event past the quiet window does not delay settle`() {
        // event at 100 ⇒ settle 350; the 600 event arrives after a full 250 quiet gap (350<600) ⇒
        // settle stays 350, the later change is the next observe's problem.
        assertEquals(350L, policy.settleOffsetMs(listOf(100L, 600L)))
    }

    // ---- P13 boundary: a relentless event stream is clamped to the hard cap ----
    @Test
    fun `P13 continuous events clamp to the hard cap`() {
        val continuous = (100L..2000L step 100L).toList()
        assertEquals(1500L, policy.settleOffsetMs(continuous))
    }

    // ---- P13 metamorphic: ordering the events differently cannot change the settle point ----
    @Test
    fun `P13 settle is independent of event order`() {
        runBlocking {
            checkAll(300, Arb.list(Arb.long(0L..2000L), 0..15)) { events ->
                assertEquals(policy.settleOffsetMs(events), policy.settleOffsetMs(events.reversed()))
                assertEquals(policy.settleOffsetMs(events), policy.settleOffsetMs(events.sorted()))
            }
        }
    }
}
