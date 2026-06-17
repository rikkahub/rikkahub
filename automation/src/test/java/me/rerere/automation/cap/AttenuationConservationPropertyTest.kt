package me.rerere.automation.cap

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P19 (sink-budget conservation / no-laundering) + MR4 (order-independence of attenuation) for the
 * capability kernel (#198 slice 11 — the deferred P19 the design parked from day one). The sink axis
 * is the security-critical one: an attenuation chain must NEVER let a descendant ADMIT a sink an
 * ancestor would have refused (that would be "permission laundering" via re-attenuation), and the
 * final admitted sink-set must not depend on the ORDER in which two independent constraints are
 * applied (intersection is commutative).
 *
 * These run over the existing [arbRootCapability] / [arbValidAttenuation] generators + a hand-pinned
 * [TrustClock]. The properties pass iff [Capability.attenuate] already INTERSECTS each axis (which it
 * does, via its three `require(... all { it in this.<axis> })` checks); a hypothetical attenuate that
 * UNIONed or replaced-without-subsetting a sink would FAIL P19. No production change is expected.
 */
class AttenuationConservationPropertyTest {

    /**
     * The set of [Sink]s a fresh [CapabilityGuard] over [cap] ADMITs, holding everything else fixed to
     * the cap's OWN granted authority so the ONLY varying axis is the sink. Mirrors the MBT-a reference
     * predicate: pick a verb and target package from the cap's own granted sets (so verb/surface never
     * cause the deny), an in-lease clock, and a fresh guard PER sink (so the step budget — which the
     * guard tracks per instance — never causes a deny). The result is purely the sink-in-budget +
     * dangerous-but-budgeted logic, which is exactly what P19/MR4 assert.
     *
     * Vacuity note: if the cap has an empty surface or empty verbs, NO request is admissible and this
     * returns ∅ for every cap in the chain — the subset/equality relations still HOLD (∅ ⊆ ∅, ∅ == ∅),
     * just uninformatively. [arbSubsetOf]'s pinned full-universe edgecase guarantees a healthy share of
     * runs have a non-empty surface/verbs (and the `non-vacuous` test below pins one concrete case), so
     * the property is not trivially-always-empty.
     */
    private fun admittedSinks(cap: Capability): Set<Sink> {
        val pkg = (cap.surface as? Surface.Scoped)?.packages?.firstOrNull() ?: return emptySet()
        val verb = cap.verbs.firstOrNull() ?: return emptySet()
        return Sink.entries.filterTo(mutableSetOf()) { sink ->
            // A FRESH guard per sink: step budget cannot accumulate across the probe (P22 is not under
            // test here). In-lease clock (now=0 ≤ a generous expiresAt would normally hold, but a
            // degenerate cap may have expiresAt=0; now=0 keeps now ≤ expiresAt for expiresAt≥0, so the
            // lease branch never spuriously denies an otherwise-admissible sink).
            val guard = CapabilityGuard(cap, fixedClock(0))
            guard.authorize(
                AuthRequest(verb = verb, targetPkg = pkg, sink = sink),
            ) == Decision.ADMIT
        }
    }

    /** A random subset of a fixed finite universe via independent Bernoulli inclusion (total). */
    private fun <T> arbSubsetOfLocal(universe: Collection<T>): Arb<Set<T>> =
        arbitrary { universe.filterTo(mutableSetOf()) { Arb.boolean().bind() } }

    /**
     * A (root, child, grandchild) attenuation chain flattened into ONE generator — the same anti-
     * starvation pattern as [arbRootAndChild]: a nested `checkAll(k, arbValidAttenuation(root))`
     * throws "target size requirement could not be satisfied" when a degenerate root admits only one
     * distinct attenuation, so the chain is built inside a single `arbitrary {}` instead.
     */
    private fun arbChain(): Arb<Triple<Capability, Capability, Capability>> = arbitrary {
        val root = arbRootCapability().bind()
        val child = arbValidAttenuation(root).bind()
        val grandchild = arbValidAttenuation(child).bind()
        Triple(root, child, grandchild)
    }

    // ---- P19 (conservation along a real attenuation chain): a grandchild can never ADMIT a sink its
    // child or root would refuse. Because every attenuate() intersects the sink budget (require-subset),
    // admittedSinks shrinks monotonically down the chain. A naive attenuate that did NOT subset the sink
    // axis would FAIL this (a re-attenuation could re-introduce a sink the parent dropped). ----
    @Test
    fun `P19 attenuation never launders a sink down the chain`() {
        runBlocking {
            checkAll(600, arbChain()) { (root, child, grandchild) ->
                val rootSinks = admittedSinks(root)
                val childSinks = admittedSinks(child)
                val grandSinks = admittedSinks(grandchild)
                assertTrue(
                    "child must not ADMIT a sink the root refuses (no laundering): $childSinks ⊄ $rootSinks",
                    rootSinks.containsAll(childSinks),
                )
                assertTrue(
                    "grandchild must not ADMIT a sink the child refuses: $grandSinks ⊄ $childSinks",
                    childSinks.containsAll(grandSinks),
                )
            }
        }
    }

    /**
     * Root + two independent sink-subsets of its budget, flattened (same anti-starvation reasoning as
     * [arbChain]) so the two attenuation orders below are built from the same a/b without a nested
     * checkAll over a per-root subset generator.
     */
    private fun arbRootAndTwoSinkSubsets(): Arb<Triple<Capability, Set<Sink>, Set<Sink>>> = arbitrary {
        val root = arbRootCapability().bind()
        val a = arbSubsetOfLocal(root.sinkBudget).bind()
        val b = arbSubsetOfLocal(root.sinkBudget).bind()
        Triple(root, a, b)
    }

    // ---- MR4 (order-independence): applying two independent sink constraints `a` then `b` admits the
    // SAME sinks as `b` then `a`. Both composition orders are built to be VALID (the second attenuation
    // requests the intersection a∩b, which is ⊆ either single constraint), so each order's final sink
    // budget is exactly root ∩ a ∩ b — independent of order. A naive attenuate that did not intersect
    // each axis (e.g. replaced the sink set) would make order matter and FAIL this. ----
    @Test
    fun `MR4 attenuation order does not change the admitted sinks`() {
        runBlocking {
            checkAll(600, arbRootAndTwoSinkSubsets()) { (root, a, b) ->
                val ab = a intersect b
                // Order 1: constrain to `a`, then to a∩b (⊆ a ⇒ valid). Final sink budget = a∩b.
                val orderAB = root.attenuate(sinkBudget = a).attenuate(sinkBudget = ab)
                // Order 2: constrain to `b`, then to a∩b (⊆ b ⇒ valid). Final sink budget = a∩b.
                val orderBA = root.attenuate(sinkBudget = b).attenuate(sinkBudget = ab)
                assertTrue(
                    "attenuation order must not change admitted sinks: " +
                        "${admittedSinks(orderAB)} != ${admittedSinks(orderBA)}",
                    admittedSinks(orderAB) == admittedSinks(orderBA),
                )
            }
        }
    }

    // ---- P19 (ENFORCEMENT / negative): attenuate REJECTS re-widening a sink budget. The chain
    // properties above show valid attenuations stay subset; this pins the MECHANISM that makes that
    // hold — a child that DROPPED a sink can never re-introduce it (nor any outside-universe sink) via
    // a further attenuate; the call throws. A naive attenuate that allowed re-widening would FAIL this
    // and break P19's no-laundering guarantee at the source (a descendant could re-admit a refused
    // sink). The forward chain property cannot catch this — it only ever builds VALID (subset)
    // attenuations; this one constructs the illegal widening and asserts it is refused. ----
    @Test
    fun `P19 attenuate rejects re-introducing a dropped or outside sink`() {
        runBlocking {
            checkAll(400, arbRootCapability()) { root ->
                val droppable = root.sinkBudget.firstOrNull()
                if (droppable != null) {
                    val childSinks = root.sinkBudget - droppable
                    val child = root.attenuate(sinkBudget = childSinks)
                    // Re-introducing the sink the child dropped must be refused (the parent had it; the
                    // child gave it up; a re-attenuation cannot get it back — that is laundering).
                    assertThrowsIAE { child.attenuate(sinkBudget = childSinks + droppable) }
                    // Any sink outside the child's budget is likewise refused.
                    val outside = Sink.entries.toSet() - child.sinkBudget
                    if (outside.isNotEmpty()) {
                        assertThrowsIAE { child.attenuate(sinkBudget = child.sinkBudget + outside.first()) }
                    }
                }
            }
        }
    }

    private fun assertThrowsIAE(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected attenuate to reject a widening sink budget (IllegalArgumentException)")
        } catch (e: IllegalArgumentException) {
            // expected: attenuate's require(sinkBudget ⊆ this.sinkBudget) rejected the widening.
        }
    }

    // ---- non-vacuity guard: a concrete cap whose admittedSinks is NON-EMPTY, so admittedSinks is not
    // trivially-always-∅ (which would make P19/MR4 pass vacuously). A root that grants TAP + the full
    // sink budget + a real surface ADMITs the budgeted sinks. ----
    @Test
    fun `admittedSinks is non-empty for a permissive cap`() {
        val root = Capability.root(
            sessionId = "s",
            surface = Surface.Scoped(setOf("com.example.app")),
            verbs = setOf(Verb.TAP),
            sinkBudget = setOf(Sink.SUBMIT, Sink.GLOBAL_NAV),
            lease = Lease(expiresAt = Long.MAX_VALUE, maxSteps = 1000),
        )
        val admitted = admittedSinks(root)
        // SUBMIT and GLOBAL_NAV are in budget ⇒ admitted; TYPE_INTO is NOT in budget ⇒ refused.
        assertTrue("a budgeted SUBMIT sink must be admitted", admitted.contains(Sink.SUBMIT))
        assertTrue("a budgeted GLOBAL_NAV sink must be admitted", admitted.contains(Sink.GLOBAL_NAV))
        assertTrue("an unbudgeted TYPE_INTO sink must be refused", !admitted.contains(Sink.TYPE_INTO))
    }
}
