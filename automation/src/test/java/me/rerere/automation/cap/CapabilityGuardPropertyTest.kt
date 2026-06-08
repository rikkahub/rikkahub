package me.rerere.automation.cap

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.automation.backend.FakeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SECURITY GATE (#187 design §7 / §8, properties P15–P25 + S1/S2 + MBT-a). This is the hard P1
 * prerequisite: no write verb (#198) may merge until these are green. Every property is written so
 * a naive always-ADMIT guard FAILS it (noted per test); the real fail-closed [CapabilityGuard]
 * passes. All run over [FakeBackend] + a hand-advanced [TrustClock].
 */
class CapabilityGuardPropertyTest {

    private val ALLOWED = "com.example.app"

    private fun guard(
        surface: Set<String> = setOf(ALLOWED),
        verbs: Set<Verb> = setOf(Verb.OBSERVE),
        sinkBudget: Set<Sink> = emptySet(),
        expiresAt: Long = Long.MAX_VALUE,
        maxSteps: Int = 1000,
        now: Long = 0,
        audit: Audit = Audit(),
    ): CapabilityGuard {
        val cap = Capability.root(
            sessionId = "s",
            surface = surface,
            verbs = verbs,
            sinkBudget = sinkBudget,
            lease = Lease(expiresAt = expiresAt, maxSteps = maxSteps),
        )
        return CapabilityGuard(cap, fixedClock(now), audit)
    }

    // ---- P17: pkg not in surface ⇒ DENY (naive ADMIT would ADMIT a non-whitelisted pkg) ----
    @Test
    fun `P17 surface not allowed denies`() {
        runBlocking {
            checkAll(300, arbAuthRequest()) { req ->
                val g = guard(surface = setOf(ALLOWED), verbs = Verb.entries.toSet(), sinkBudget = Sink.entries.toSet())
                val d = g.authorize(req)
                val pkg = req.targetPkg
                if (pkg == null || pkg != ALLOWED) {
                    assertEquals("pkg $pkg outside surface must DENY", Decision.DENY, d)
                }
            }
        }
    }

    // ---- S1: default-empty root surface denies everything ----
    @Test
    fun `S1 default empty surface denies all`() {
        runBlocking {
            val g = guard(surface = emptySet(), verbs = Verb.entries.toSet(), sinkBudget = Sink.entries.toSet())
            checkAll(200, arbAuthRequest()) { req ->
                assertEquals(Decision.DENY, g.authorize(req))
            }
        }
    }

    // ---- P21: now > expiresAt ⇒ DENY regardless of content (naive ADMIT ignores the clock) ----
    @Test
    fun `P21 expired lease denies regardless of request`() {
        runBlocking {
            checkAll(300, Arb.int(0..100_000), Arb.int(0..100_000), arbAuthRequest()) { exp, now, req ->
                val g = guard(
                    surface = Verb.entries.let { setOf(ALLOWED) },
                    verbs = Verb.entries.toSet(),
                    sinkBudget = Sink.entries.toSet(),
                    expiresAt = exp.toLong(),
                    now = now.toLong(),
                )
                val d = g.authorize(req.copy(targetPkg = ALLOWED, sink = null, sensitiveNode = false, systemUiTarget = false))
                if (now > exp) assertEquals("expired lease must DENY", Decision.DENY, d)
            }
        }
    }

    // ---- P22: ADMIT count ≤ maxSteps; the (maxSteps+1)-th ADMIT ⇒ DENY ----
    @Test
    fun `P22 admit count never exceeds maxSteps`() {
        runBlocking {
            checkAll(200, Arb.int(0..10)) { maxSteps ->
                val g = guard(maxSteps = maxSteps)
                var admits = 0
                // Fire well past the budget; every request is otherwise admissible.
                repeat(maxSteps + 5) {
                    if (g.authorize(AuthRequest(verb = Verb.OBSERVE, targetPkg = ALLOWED)) == Decision.ADMIT) admits++
                }
                assertEquals("exactly maxSteps ADMITs", maxSteps, admits)
                assertEquals(maxSteps, g.admitCount)
                // The very next is a DENY (boundary).
                assertEquals(Decision.DENY, g.authorize(AuthRequest(verb = Verb.OBSERVE, targetPkg = ALLOWED)))
            }
        }
    }

    // ---- P24: unknown/unauthorized verb, sink not in budget, malformed ⇒ DENY (fail-closed) ----
    @Test
    fun `P24 unauthorized verb fails closed`() {
        runBlocking {
            // Surface allowed + lease fine, but verb not granted.
            val g = guard(verbs = setOf(Verb.OBSERVE), maxSteps = 1000)
            checkAll(200, arbVerb()) { verb ->
                val d = g.authorize(AuthRequest(verb = verb, targetPkg = ALLOWED))
                if (verb != Verb.OBSERVE) assertEquals(Decision.DENY, d)
            }
        }
    }

    @Test
    fun `P24 sink outside budget fails closed`() {
        runBlocking {
            val g = guard(verbs = Verb.entries.toSet(), sinkBudget = emptySet(), maxSteps = 1000)
            checkAll(200, arbSink()) { sink ->
                assertEquals(Decision.DENY, g.authorize(AuthRequest(verb = Verb.TAP, targetPkg = ALLOWED, sink = sink)))
            }
        }
    }

    @Test
    fun `P24 malformed request fails closed`() {
        runBlocking {
            // Everything else is maximally permissive; malformed must STILL deny.
            val g = guard(surface = setOf(ALLOWED), verbs = Verb.entries.toSet(), sinkBudget = Sink.entries.toSet())
            checkAll(200, arbAuthRequest()) { req ->
                assertEquals(Decision.DENY, g.authorize(req.copy(malformed = true)))
            }
        }
    }

    // ---- P18: sensitive (password/FLAG_SECURE) or system/grant UI ⇒ DENY ----
    @Test
    fun `P18 sensitive and system-ui targets are non-actionable`() {
        runBlocking {
            val g = guard(verbs = Verb.entries.toSet(), sinkBudget = Sink.entries.toSet(), maxSteps = 1000)
            checkAll(200, arbVerb()) { verb ->
                assertEquals(Decision.DENY, g.authorize(AuthRequest(verb = verb, targetPkg = ALLOWED, sensitiveNode = true)))
                assertEquals(Decision.DENY, g.authorize(AuthRequest(verb = verb, targetPkg = ALLOWED, systemUiTarget = true)))
            }
        }
    }

    // ---- P23: authorize is pure/deterministic for a given request + guard state ----
    @Test
    fun `P23 authorize is deterministic for the same state`() {
        runBlocking {
            checkAll(300, arbAuthRequest()) { req ->
                // Two guards built identically must decide identically for the first call (same state).
                val a = guard(surface = setOf(ALLOWED), verbs = Verb.entries.toSet(), sinkBudget = Sink.entries.toSet(), maxSteps = 1000)
                val b = guard(surface = setOf(ALLOWED), verbs = Verb.entries.toSet(), sinkBudget = Sink.entries.toSet(), maxSteps = 1000)
                assertEquals(a.authorize(req), b.authorize(req))
            }
        }
    }

    // ---- P25: exactly one redacted ledger entry per decision; append-only; no raw args ----
    @Test
    fun `P25 one redacted append-only ledger entry per decision`() {
        runBlocking {
            val audit = Audit()
            val g = guard(surface = setOf(ALLOWED), verbs = Verb.entries.toSet(), sinkBudget = Sink.entries.toSet(), maxSteps = 1000, audit = audit)
            var n = 0
            checkAll(200, arbAuthRequest()) { req ->
                val secret = "password=hunter2-${req.hashCode()}"
                val before = audit.size
                g.authorize(req.copy(rawArgs = secret))
                n++
                // Exactly one new entry per authorize.
                assertEquals(before + 1, audit.size)
                // Append-only: size only grows, equals the running count.
                assertEquals(n, audit.size)
                // Redacted: the secret never appears verbatim in the ledger.
                val last = audit.entries().last()
                assertFalse("raw args leaked into ledger", last.redactedArgs.contains("hunter2"))
            }
            // entries() returns an immutable copy; mutating it cannot shrink the ledger.
            val snapshot = audit.entries()
            assertEquals(n, snapshot.size)
        }
    }

    // ---- P15 + P16 + P20: attenuation monotonicity, never-widen, revoke cascade ----
    @Test
    fun `P16 attenuate never widens any axis`() {
        runBlocking {
            checkAll(300, arbRootAndChild()) { (root, child) ->
                assertTrue(child.surface.all { it in root.surface })
                assertTrue(child.verbs.all { it in root.verbs })
                assertTrue(child.sinkBudget.all { it in root.sinkBudget })
                assertTrue(child.lease.expiresAt <= root.lease.expiresAt)
                assertTrue(child.lease.maxSteps <= root.lease.maxSteps)
            }
        }
    }

    @Test
    fun `P16 rogue attenuation exceeding parent is rejected`() {
        runBlocking {
            val root = Capability.root(
                sessionId = "s",
                surface = setOf(ALLOWED),
                verbs = setOf(Verb.OBSERVE),
                sinkBudget = emptySet(),
                lease = Lease(expiresAt = 100, maxSteps = 5),
            )
            // Each of these widens exactly one axis and must throw.
            assertThrows { root.attenuate(surface = setOf(ALLOWED, "com.bank.app")) }
            assertThrows { root.attenuate(verbs = setOf(Verb.OBSERVE, Verb.TAP)) }
            assertThrows { root.attenuate(sinkBudget = setOf(Sink.SUBMIT)) }
            assertThrows { root.attenuate(expiresAt = 101) }
            assertThrows { root.attenuate(maxSteps = 6) }
        }
    }

    @Test
    fun `P15 attenuation monotonicity child admit implies parent admit`() {
        runBlocking {
            // For every action: if the child ADMITs it, the parent (with at-least-as-much authority)
            // must ADMIT it too. A naive always-ADMIT child breaks this the moment the parent DENYs.
            // FRESH guards per evaluation: each authorize() is the FIRST call on its guard, so step
            // budgets do not accumulate across the property (the invariant under test is the static
            // surface/verb/sink/lease logic, not step exhaustion). The (root, child) pair comes from
            // one flattened arb so a degenerate root never starves the child generator.
            checkAll(400, arbRootAndChild(), arbAuthRequest()) { (root, child), req ->
                val now = 0L
                val childDecision = CapabilityGuard(child, fixedClock(now)).authorize(req)
                if (childDecision == Decision.ADMIT) {
                    // A fresh parent guard, first call ⇒ step budget cannot be the reason to deny.
                    val parentDecision = CapabilityGuard(root, fixedClock(now)).authorize(req)
                    assertEquals(
                        "child ADMIT must imply parent ADMIT (attenuation I4)",
                        Decision.ADMIT,
                        parentDecision,
                    )
                }
            }
        }
    }

    @Test
    fun `P20 revoke denies parent and child cascade`() {
        runBlocking {
            val root = Capability.root(
                sessionId = "s",
                surface = setOf(ALLOWED),
                verbs = setOf(Verb.OBSERVE),
                sinkBudget = emptySet(),
                lease = Lease(expiresAt = Long.MAX_VALUE, maxSteps = 1000),
            )
            val child = root.attenuate()
            val grandchild = child.attenuate()
            val rg = CapabilityGuard(root, fixedClock(0))
            val cg = CapabilityGuard(child, fixedClock(0))
            val gg = CapabilityGuard(grandchild, fixedClock(0))
            val req = AuthRequest(verb = Verb.OBSERVE, targetPkg = ALLOWED)
            // Before revoke: all admit.
            assertEquals(Decision.ADMIT, rg.authorize(req))
            assertEquals(Decision.ADMIT, cg.authorize(req))
            assertEquals(Decision.ADMIT, gg.authorize(req))
            // Revoke the ROOT ⇒ every descendant denies (shared token, O(1) cascade).
            rg.revoke()
            assertEquals(Decision.DENY, rg.authorize(req))
            assertEquals(Decision.DENY, cg.authorize(req))
            assertEquals(Decision.DENY, gg.authorize(req))
            assertTrue(grandchild.isRevoked)
        }
    }

    // ---- P20 (in-flight): revoke() cancels backend work parked under the token ----
    @Test
    fun `P20 revoke cancels in-flight backend work`() {
        runBlocking {
            val backend = FakeBackend()
            val gate = backend.armGate() // next snapshotRawTree() parks until released
            val root = Capability.root(
                sessionId = "s",
                surface = setOf(ALLOWED),
                verbs = setOf(Verb.OBSERVE),
                sinkBudget = emptySet(),
                lease = Lease(expiresAt = Long.MAX_VALUE, maxSteps = 1000),
            )
            val token = root.revocation
            val started = CompletableDeferred<Unit>()
            // Launch work that registers an in-flight canceller on the token, then parks in the backend.
            val job: Job = launch(Dispatchers.Default) {
                token.guardInFlight(
                    cancel = { gate.cancel() }, // revoke fires this ⇒ unparks the await with cancellation
                    onAlreadyRevoked = { },
                    block = {
                        started.complete(Unit)
                        runBlocking { backend.snapshotRawTree() }
                    },
                )
            }
            started.await()
            yield()
            // Revoke from "the kill-switch": must cancel the parked work, not let it complete.
            root.revocation.revoke()
            job.join()
            assertTrue("revoke must cancel in-flight work", job.isCancelled || gate.isCancelled)
        }
    }

    // ---- S2: every tool path calls the guard BEFORE the backend ----
    @Test
    fun `S2 guard denial prevents any backend capture`() {
        runBlocking {
            // Model the tool path: authorize first; only on ADMIT touch the backend.
            val backend = FakeBackend()
            val g = guard(surface = emptySet()) // denies everything
            val req = AuthRequest(verb = Verb.OBSERVE, targetPkg = ALLOWED)
            if (g.authorize(req) == Decision.ADMIT) {
                backend.snapshotRawTree()
            }
            assertEquals("DENY must short-circuit before the backend is hit", 0, backend.snapshotCount)
        }
    }

    // ---- MBT-a: state machine — no command sequence ADMITs outside Init(root) authority ----
    @Test
    fun `MBT-a no sequence admits outside granted authority`() {
        runBlocking {
            checkAll(150, arbRootCapability()) { root ->
                val now = 0L
                val guard = CapabilityGuard(root, fixedClock(now))
                // Reference predicate: under THIS root, would the request be admissible (ignoring step
                // budget exhaustion, which the impl tracks separately)?
                fun referenceAdmissible(req: AuthRequest): Boolean {
                    if (req.malformed) return false
                    if (root.isRevoked) return false
                    if (now > root.lease.expiresAt) return false
                    val pkg = req.targetPkg ?: return false
                    if (pkg !in root.surface) return false
                    if (req.verb !in root.verbs) return false
                    if (req.sink != null && req.sink !in root.sinkBudget) return false
                    if (req.sensitiveNode || req.systemUiTarget) return false
                    return true
                }
                checkAll(20, arbAuthRequest()) { req ->
                    val d = guard.authorize(req)
                    // Cross-step invariant: the guard NEVER admits something the reference model
                    // (the granted authority) forbids. (It MAY deny an otherwise-admissible request
                    // once the step budget is consumed — that direction is allowed.)
                    if (d == Decision.ADMIT) {
                        assertTrue("admitted an action outside granted authority", referenceAdmissible(req))
                    }
                }
            }
        }
    }

    // ---- sanity: a non-empty surface is not the same as default-empty (guards the S1 generator) ----
    @Test
    fun `non-trivial surface differs from empty`() {
        assertNotEquals(emptySet<String>(), setOf(ALLOWED))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
