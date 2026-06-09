package me.rerere.automation.cap

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.withEdgecases

/**
 * Generators for the capability security PBT (#187 design §8). The whole suite runs over these,
 * a [me.rerere.automation.backend.FakeBackend], and a hand-advanced [TrustClock] — never real time.
 */

private val PACKAGES = listOf(
    "com.example.app", "com.example.other", "com.bank.app",
    "com.social.app", "com.shop.app", "org.foo.bar",
)

fun arbPackage(): Arb<String> = Arb.element(PACKAGES)

fun arbVerb(): Arb<Verb> = Arb.element(Verb.entries)

fun arbSink(): Arb<Sink> = Arb.element(Sink.entries)

/**
 * A random subset of a FIXED finite [universe] via independent Bernoulli inclusion — one coin flip
 * per element. This is the right model for "a random subset of a known finite set", and unlike
 * `Arb.set(elementArb, 0..universe.size)` it is TOTAL: it produces a value for every seed. The set
 * variant is a coupon-collector with a bounded retry budget, so asking it for a set of size ==
 * cardinality throws `IllegalStateException: the target size requirement of N could not be
 * satisfied` for ~1/5000 unlucky seeds (the flake that reddened master 2026-06-09; surface was the
 * proven offender). The two security-relevant boundaries — deny-all (∅) and maximally-permissive
 * (the full universe) — are pinned as edgecases so every run still exercises them deterministically.
 */
private fun <T> arbSubsetOf(universe: Collection<T>): Arb<Set<T>> =
    arbitrary { universe.filterTo(mutableSetOf()) { Arb.boolean().bind() } }
        .withEdgecases(emptySet(), universe.toSet())

/** A fixed trust clock pinned at [now] (tests advance it by re-minting). */
fun fixedClock(now: Long): TrustClock = TrustClock { now }

/**
 * A valid-by-construction root capability bound to a session. surface/verbs/sinkBudget are random
 * non-trivial subsets of the universe; the lease is generous so step/expiry branches are exercised
 * by separate, targeted generators rather than accidentally tripping here.
 */
fun arbRootCapability(): Arb<Capability> = arbitrary {
    val surface = arbSubsetOf(PACKAGES).bind()
    val verbs = arbSubsetOf(Verb.entries).bind()
    val sinks = arbSubsetOf(Sink.entries).bind()
    val maxSteps = Arb.int(0..32).bind()
    val expiresAt = Arb.int(0..1_000_000).bind().toLong()
    Capability.root(
        sessionId = Arb.string(1..8).bind(),
        surface = surface,
        verbs = verbs,
        sinkBudget = sinks,
        lease = Lease(expiresAt = expiresAt, maxSteps = maxSteps),
    )
}

/**
 * A valid attenuation of [parent]: each axis is a random subset, lease no longer-lived. Always
 * succeeds (never throws) — this is the "well-behaved child" generator for monotonicity (P15/P16).
 */
fun arbValidAttenuation(parent: Capability): Arb<Capability> = arbitrary {
    // A subset of each PARENT axis (⊆ parent ⇒ attenuate() never widens). arbSubsetOf is total even
    // for an empty parent axis, so the old per-axis empty-guards are no longer needed.
    val surface = arbSubsetOf(parent.surface).bind()
    val verbs = arbSubsetOf(parent.verbs).bind()
    val sinks = arbSubsetOf(parent.sinkBudget).bind()
    val maxSteps = Arb.int(0..parent.lease.maxSteps.coerceAtLeast(0)).bind()
    val expiresAt = Arb.int(0..parent.lease.expiresAt.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        .bind().toLong()
    parent.attenuate(
        surface = surface,
        verbs = verbs,
        sinkBudget = sinks,
        expiresAt = expiresAt,
        maxSteps = maxSteps,
    )
}

/**
 * A (root, valid-child) pair in a single arb. Flattening the attenuation into one generator avoids
 * kotest's "target size requirement could not be satisfied" when a degenerate root (empty surface/
 * verbs/sinks, maxSteps=0, expiresAt=0) has only ONE possible attenuation — a nested
 * `checkAll(k, arbValidAttenuation(root))` cannot produce k distinct children from such a root.
 */
fun arbRootAndChild(): Arb<Pair<Capability, Capability>> = arbitrary {
    val root = arbRootCapability().bind()
    val child = arbValidAttenuation(root).bind()
    root to child
}

/** An auth request for a random verb/target, never sensitive (the common read-path shape). */
fun arbAuthRequest(): Arb<AuthRequest> = arbitrary {
    AuthRequest(
        verb = arbVerb().bind(),
        targetPkg = Arb.element(PACKAGES + listOf<String?>(null)).bind(),
        sink = Arb.element(Sink.entries.map { it as Sink? } + listOf<Sink?>(null)).bind(),
    )
}

/** A short sequence of OBSERVE requests against an allowed package (for step-budget tests). */
fun arbObserveBurst(pkg: String, max: Int): Arb<List<AuthRequest>> =
    Arb.list(
        arbitrary { AuthRequest(verb = Verb.OBSERVE, targetPkg = pkg) },
        0..max,
    )
