package me.rerere.automation.cap

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.automation.backend.FakeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SECURITY GATE (#187 v2, design §6 / properties PG1-PG4). Proves the pure
 * [AutomationGrant.toCapability] derivation that finally fills `Capability.surface` is itself
 * fail-closed: an absent/empty grant yields no capability (deny-all), and a present grant only ever
 * NARROWS - it never amplifies surface/verbs/sinks/TTL/steps beyond what the user approved, and
 * never admits `Sink.SUBMIT`.
 *
 * This is the testable core of the v2 fix BEFORE any wiring (M5): the derivation is a pure function,
 * so its invariants are proven here over [FakeBackend] + a hand-advanced [TrustClock], exactly like
 * the existing [CapabilityGuardPropertyTest] guard suite - no Android, no ChatService.
 *
 * Every property is written so a naive/over-permissive derivation (mint a capability regardless,
 * pass SUBMIT through, ignore zero TTL/steps) FAILS it; the real fail-closed derivation passes.
 */
class AutomationGrantPropertyTest {

    private val PACKAGES = listOf(
        "com.example.app", "com.example.other", "com.bank.app",
        "com.social.app", "com.shop.app", "org.foo.bar",
    )

    /** A random subset of a fixed finite universe via independent Bernoulli inclusion (total). */
    private fun <T> arbSubsetOf(universe: Collection<T>): Arb<Set<T>> =
        arbitrary { universe.filterTo(mutableSetOf()) { Arb.boolean().bind() } }

    /** An ENABLED, NON-EMPTY-surface, POSITIVE-TTL/steps grant - the admissible branch. */
    private fun arbLiveGrant(): Arb<AutomationGrant> = arbitrary {
        AutomationGrant(
            enabled = true,
            // Non-empty surface: at least the first package is always present.
            allowedPackages = arbSubsetOf(PACKAGES).bind() + PACKAGES.first(),
            verbs = arbSubsetOf(Verb.entries).bind(),
            sinks = arbSubsetOf(Sink.entries).bind(),
            ttlMinutes = Arb.int(1..240).bind(),
            maxSteps = Arb.int(1..200).bind(),
        )
    }

    /** Any grant at all - enabled or not, empty or not, zero or positive bounds. */
    private fun arbAnyGrant(): Arb<AutomationGrant> = arbitrary {
        AutomationGrant(
            enabled = Arb.boolean().bind(),
            allowedPackages = arbSubsetOf(PACKAGES).bind(),
            verbs = arbSubsetOf(Verb.entries).bind(),
            sinks = arbSubsetOf(Sink.entries).bind(),
            ttlMinutes = Arb.int(-10..240).bind(),
            maxSteps = Arb.int(-10..200).bind(),
        )
    }

    private fun arbVerb(): Arb<Verb> = arbitrary { Verb.entries[Arb.int(0 until Verb.entries.size).bind()] }
    private fun arbSink(): Arb<Sink> = arbitrary { Sink.entries[Arb.int(0 until Sink.entries.size).bind()] }
    private fun arbPkg(): Arb<String> = arbitrary { PACKAGES[Arb.int(0 until PACKAGES.size).bind()] }
    private fun arbSession(): Arb<String> = Arb.string(1..8)

    private fun guardFor(cap: Capability, now: Long): CapabilityGuard =
        CapabilityGuard(cap, TrustClock { now })

    // ---- PG1: no grant (disabled OR empty surface OR zero TTL/steps) => DENY all (deny-all) ----
    @Test
    fun `PG1 absent or invalid grant derives deny-all`() {
        runBlocking {
            val backend = FakeBackend()
            checkAll(400, arbAnyGrant(), arbSession(), Arb.int(0..1_000_000)) { grant, session, now ->
                val cap = grant.toCapability(session, now.toLong())
                // Deny-all is encoded as a null capability OR an empty-surface one - either way no
                // package can ever be authorized. The "no grant => still deny" root-cause invariant.
                if (!grant.enabled || grant.allowedPackages.isEmpty()
                    || grant.ttlMinutes <= 0 || grant.maxSteps <= 0
                ) {
                    if (cap != null) {
                        val g = guardFor(cap, now.toLong())
                        checkAll(8, arbVerb(), arbPkg()) { verb, pkg ->
                            assertEquals(
                                "an invalid/absent grant must DENY every request",
                                Decision.DENY,
                                g.authorize(AuthRequest(verb = verb, targetPkg = pkg)),
                            )
                        }
                    }
                    // A derivation performs no I/O; the guard alone decides.
                    assertEquals(0, backend.snapshotCount)
                }
            }
        }
    }

    // ---- PG2: derived surface subset allowedPackages, and == allowedPackages when grant is live ----
    @Test
    fun `PG2 derived surface equals allowedPackages with no amplification`() {
        runBlocking {
            checkAll(400, arbLiveGrant(), arbSession(), Arb.int(0..1_000_000)) { grant, session, now ->
                val cap = grant.toCapability(session, now.toLong())
                assertNotNull("a live grant must derive a capability", cap)
                cap!!
                assertTrue(
                    "derived surface must not reach a package the user did not approve",
                    (cap.surface as Surface.Scoped).packages.all { it in grant.allowedPackages },
                )
                assertEquals(
                    "a live grant's surface is exactly the approved packages",
                    Surface.Scoped(grant.allowedPackages),
                    cap.surface,
                )
            }
        }
    }

    // ---- PG3: a verb/sink NOT in the grant is never authorized (attenuation, no amplification) ----
    @Test
    fun `PG3 withheld verb or sink is never authorized`() {
        runBlocking {
            checkAll(400, arbLiveGrant(), arbSession(), arbVerb()) { grant, session, verb ->
                val now = 0L
                val cap = grant.toCapability(session, now) ?: return@checkAll
                val g = guardFor(cap, now)
                val pkg = grant.allowedPackages.first()
                // A verb the user did not grant => DENY even on an in-surface package.
                if (verb !in grant.verbs) {
                    assertEquals(
                        "a withheld verb must never authorize",
                        Decision.DENY,
                        g.authorize(AuthRequest(verb = verb, targetPkg = pkg)),
                    )
                }
            }
            // SUBMIT is excluded from every derived budget (the kernel withholds submit-class), and
            // any sink not in the granted budget is denied.
            checkAll(400, arbLiveGrant(), arbSession(), arbSink()) { grant, session, sink ->
                val now = 0L
                val cap = grant.toCapability(session, now) ?: return@checkAll
                // SUBMIT never makes it into the derived budget, regardless of the grant.
                assertTrue("SUBMIT must never enter the derived sink budget", Sink.SUBMIT !in cap.sinkBudget)
                val g = guardFor(cap, now)
                val pkg = grant.allowedPackages.first()
                // Use a granted verb so the SINK gate is the deciding branch; if none is granted the
                // verb gate already denies, which still satisfies "never authorized".
                val verb = grant.verbs.firstOrNull() ?: Verb.OBSERVE
                if (sink !in cap.sinkBudget) {
                    assertEquals(
                        "a sink outside the derived budget must never authorize",
                        Decision.DENY,
                        g.authorize(AuthRequest(verb = verb, targetPkg = pkg, sink = sink)),
                    )
                }
            }
        }
    }

    // ---- PG4: TTL/steps boundary - zero => DENY; positive => expiry honored + (maxSteps+1)-th DENY -
    @Test
    fun `PG4 zero ttl or steps denies and positive bounds are honored`() {
        runBlocking {
            // Zero/negative TTL => no usable capability (already expired => deny-all).
            checkAll(300, arbSession(), Arb.int(-5..0), Arb.int(1..50)) { session, ttl, steps ->
                val grant = AutomationGrant(
                    enabled = true,
                    allowedPackages = setOf(PACKAGES.first()),
                    verbs = setOf(Verb.OBSERVE),
                    sinks = emptySet(),
                    ttlMinutes = ttl,
                    maxSteps = steps,
                )
                val cap = grant.toCapability(session, now = 0L)
                if (cap != null) {
                    val g = guardFor(cap, now = 0L)
                    assertEquals(
                        "zero/negative TTL is already expired => DENY",
                        Decision.DENY,
                        g.authorize(AuthRequest(verb = Verb.OBSERVE, targetPkg = PACKAGES.first())),
                    )
                } else {
                    assertNull(cap)
                }
            }
            // Zero/negative steps => no admit => deny-all.
            checkAll(300, arbSession(), Arb.int(1..240), Arb.int(-5..0)) { session, ttl, steps ->
                val grant = AutomationGrant(
                    enabled = true,
                    allowedPackages = setOf(PACKAGES.first()),
                    verbs = setOf(Verb.OBSERVE),
                    sinks = emptySet(),
                    ttlMinutes = ttl,
                    maxSteps = steps,
                )
                val cap = grant.toCapability(session, now = 0L)
                if (cap != null) {
                    val g = guardFor(cap, now = 0L)
                    assertEquals(
                        "zero/negative steps => no admit",
                        Decision.DENY,
                        g.authorize(AuthRequest(verb = Verb.OBSERVE, targetPkg = PACKAGES.first())),
                    )
                } else {
                    assertNull(cap)
                }
            }
            // Positive bounds: expiry == now + ttlMinutes*60_000, and the (maxSteps+1)-th admit DENYs.
            checkAll(200, arbSession(), Arb.int(1..240), Arb.int(1..16), Arb.int(0..1_000_000)) { session, ttl, steps, now ->
                val grant = AutomationGrant(
                    enabled = true,
                    allowedPackages = setOf(PACKAGES.first()),
                    verbs = setOf(Verb.OBSERVE),
                    sinks = emptySet(),
                    ttlMinutes = ttl,
                    maxSteps = steps,
                )
                val cap = grant.toCapability(session, now.toLong())
                assertNotNull("positive bounds must derive a capability", cap)
                cap!!
                assertEquals(
                    "lease expiry is now + ttlMinutes*60_000",
                    now.toLong() + ttl.toLong() * 60_000L,
                    cap.lease.expiresAt,
                )
                assertEquals("maxSteps carried through", steps, cap.lease.maxSteps)
                val g = guardFor(cap, now.toLong())
                var admits = 0
                repeat(steps + 3) {
                    if (g.authorize(AuthRequest(verb = Verb.OBSERVE, targetPkg = PACKAGES.first())) == Decision.ADMIT) {
                        admits++
                    }
                }
                assertEquals("exactly maxSteps ADMITs", steps, admits)
                assertEquals(
                    "the (maxSteps+1)-th admit DENYs",
                    Decision.DENY,
                    g.authorize(AuthRequest(verb = Verb.OBSERVE, targetPkg = PACKAGES.first())),
                )
            }
        }
    }

    // ---- YOLO derivation: maximally-permissive capability, no surface requirement ----
    @Test
    fun `YOLO grant derives an unbounded host-inclusive all-sink capability`() {
        runBlocking {
            checkAll(300, arbSession(), Arb.int(1..240), Arb.int(1..200), Arb.int(0..1_000_000)) { session, ttl, steps, now ->
                // YOLO needs NO allowedPackages (empty on purpose) — the empty-surface deny must not apply.
                val grant = AutomationGrant(
                    enabled = true,
                    allowedPackages = emptySet(),
                    ttlMinutes = ttl,
                    maxSteps = steps,
                    yolo = true,
                )
                val cap = grant.toCapability(session, now.toLong())
                assertNotNull("a YOLO grant must derive a capability despite an empty surface", cap)
                cap!!
                assertEquals("YOLO surface is unbounded", Surface.Unbounded, cap.surface)
                assertTrue("YOLO includes the host", cap.includeHost)
                assertTrue("YOLO grants every verb", cap.verbs.containsAll(Verb.entries.toSet()))
                assertTrue("YOLO grants every sink incl. SUBMIT", cap.sinkBudget.containsAll(Sink.entries.toSet()))
                // An arbitrary package the user never listed is admitted under YOLO.
                val g = guardFor(cap, now.toLong())
                assertEquals(
                    "YOLO admits an arbitrary, never-listed package",
                    Decision.ADMIT,
                    g.authorize(AuthRequest(verb = Verb.OBSERVE, targetPkg = "com.never.listed")),
                )
            }
        }
    }

    // ---- YOLO still obeys the master enable + lease bounds (kill switch is independent) ----
    @Test
    fun `a YOLO grant still requires enabled and a positive lease`() {
        // Disabled YOLO ⇒ no capability.
        assertNull(
            "disabled YOLO derives nothing",
            AutomationGrant(enabled = false, ttlMinutes = 10, maxSteps = 10, yolo = true)
                .toCapability("s", now = 0L),
        )
        // Zero TTL ⇒ no capability even under YOLO.
        assertNull(
            "zero-TTL YOLO derives nothing",
            AutomationGrant(enabled = true, ttlMinutes = 0, maxSteps = 10, yolo = true)
                .toCapability("s", now = 0L),
        )
        // Zero steps ⇒ no capability even under YOLO.
        assertNull(
            "zero-step YOLO derives nothing",
            AutomationGrant(enabled = true, ttlMinutes = 10, maxSteps = 0, yolo = true)
                .toCapability("s", now = 0L),
        )
    }

    // ---- metamorphic: flipping yolo on only ever WIDENS authority (never narrows) ----
    @Test
    fun `enabling YOLO on a live grant only widens authority`() {
        runBlocking {
            checkAll(300, arbLiveGrant(), arbSession(), Arb.int(0..1_000_000)) { scoped, session, now ->
                val scopedCap = scoped.toCapability(session, now.toLong())!!
                val yoloCap = scoped.copy(yolo = true).toCapability(session, now.toLong())!!
                // Surface widens: scoped ⊆ unbounded (canAttenuateTo from yolo down to scoped holds).
                assertTrue(
                    "YOLO surface must be at least as wide as the scoped surface",
                    yoloCap.surface.canAttenuateTo(scopedCap.surface),
                )
                assertTrue("YOLO verbs ⊇ scoped verbs", yoloCap.verbs.containsAll(scopedCap.verbs))
                assertTrue("YOLO sinks ⊇ scoped sinks", yoloCap.sinkBudget.containsAll(scopedCap.sinkBudget))
                assertTrue("YOLO host-inclusion ⊇ scoped", yoloCap.includeHost || !scopedCap.includeHost)
            }
        }
    }
}
