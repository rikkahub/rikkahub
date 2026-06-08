package me.rerere.automation.cap

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.string

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

/** A fixed trust clock pinned at [now] (tests advance it by re-minting). */
fun fixedClock(now: Long): TrustClock = TrustClock { now }

/**
 * A valid-by-construction root capability bound to a session. surface/verbs/sinkBudget are random
 * non-trivial subsets of the universe; the lease is generous so step/expiry branches are exercised
 * by separate, targeted generators rather than accidentally tripping here.
 */
fun arbRootCapability(): Arb<Capability> = arbitrary { rs ->
    val surface = Arb.set(arbPackage(), 0..PACKAGES.size).bind()
    val verbs = Arb.set(arbVerb(), 0..Verb.entries.size).bind()
    val sinks = Arb.set(arbSink(), 0..Sink.entries.size).bind()
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
    val surface = if (parent.surface.isEmpty()) emptySet()
    else Arb.set(Arb.element(parent.surface.toList()), 0..parent.surface.size).bind()
    val verbs = if (parent.verbs.isEmpty()) emptySet()
    else Arb.set(Arb.element(parent.verbs.toList()), 0..parent.verbs.size).bind()
    val sinks = if (parent.sinkBudget.isEmpty()) emptySet()
    else Arb.set(Arb.element(parent.sinkBudget.toList()), 0..parent.sinkBudget.size).bind()
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
