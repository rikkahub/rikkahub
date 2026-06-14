package me.rerere.automation.cap

/**
 * The kernel-side, Android-free, NON-`@Serializable` value of a user's narrowing automation scope
 * (#187 v2). The `:app` data model carries a `@Serializable` mirror persisted on the assistant; that
 * mirror maps to this at the ChatService lease seam (Open Q1: mirror, not reuse — the kernel stays
 * free of serialization coupling and of any `:app` dependency, so its proofs run pure).
 *
 * Every field defaults to the fail-closed deny-all posture: a default [AutomationGrant] derives NO
 * capability (deny-all), preserving the inert behavior the kernel ships with until the user fills
 * the grant. A grant ONLY narrows — [toCapability] never amplifies surface/verbs/sinks/TTL/steps
 * beyond what is recorded here, and never lets `Sink.SUBMIT` through (submit-class stays the
 * stricter, separate opt-in the kernel deliberately withholds).
 */
data class AutomationGrant(
    val enabled: Boolean = false,
    val allowedPackages: Set<String> = emptySet(),
    val verbs: Set<Verb> = emptySet(),
    val sinks: Set<Sink> = emptySet(),
    val ttlMinutes: Int = 0,
    val maxSteps: Int = 0,
)

/** Wall-clock minutes are converted to the lease's millisecond clock at this single point. */
private const val MILLIS_PER_MINUTE = 60_000L

/**
 * Derive the kernel [Capability] this grant authorizes for [sessionId], leased from [now] (a
 * [TrustClock] reading in millis, never `System.now`). PURE and total: same input ⇒ same output, no
 * I/O, no ambient state — so the PG1–PG4 properties are reproducible.
 *
 * Returns `null` (deny-all) when the grant is not a usable authorization:
 *  - not [enabled] (the master switch is off),
 *  - [allowedPackages] is empty (no surface ⇒ every package DENIED — the v1 root-cause invariant),
 *  - [ttlMinutes] ≤ 0 (zero/negative TTL is already-expired ⇒ DENY), or
 *  - [maxSteps] ≤ 0 (no admit budget ⇒ DENY).
 *
 * Otherwise mints `Capability.root` whose `surface = allowedPackages` (no amplification: exactly the
 * approved packages), `verbs = verbs`, `sinkBudget = sinks` with `Sink.SUBMIT` REMOVED, and a
 * `Lease(expiresAt = now + ttlMinutes*60_000, maxSteps = maxSteps)`. The result still passes through
 * the [CapabilityGuard]'s fail-closed gates at authorize time; this function only sets the ceiling.
 */
fun AutomationGrant.toCapability(sessionId: String, now: Long): Capability? {
    if (!enabled) return null
    if (allowedPackages.isEmpty()) return null
    if (ttlMinutes <= 0) return null
    if (maxSteps <= 0) return null
    return Capability.root(
        sessionId = sessionId,
        surface = allowedPackages,
        verbs = verbs,
        // Submit-class is never granted by this derivation, regardless of what the grant lists.
        sinkBudget = sinks - Sink.SUBMIT,
        lease = Lease(
            expiresAt = now + ttlMinutes.toLong() * MILLIS_PER_MINUTE,
            maxSteps = maxSteps,
        ),
    )
}
