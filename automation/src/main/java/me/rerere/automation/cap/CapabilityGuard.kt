package me.rerere.automation.cap

import java.util.concurrent.atomic.AtomicInteger

/**
 * One admission request. The guard makes NO I/O and trusts NO ambient state — everything it needs
 * to decide is in this value (design I2/P23: authorize is pure & deterministic given the request +
 * the guard's own monotonic step counter). [malformed] lets the tool layer report a JSON it could
 * not parse so the guard fails it closed here (design I3/P24) rather than letting it slip through.
 */
data class AuthRequest(
    val verb: Verb,
    val targetPkg: String?,
    /** Write verb targets a password/FLAG_SECURE node — must be blocked (design I8/P18). */
    val sensitiveNode: Boolean = false,
    /** Write verb targets system/grant UI (systemui/packageinstaller) — observable, not actionable. */
    val systemUiTarget: Boolean = false,
    /** Sink this action would exercise, if any. Reads have none. */
    val sink: Sink? = null,
    /** True when the model-supplied args could not be parsed; forces a fail-closed DENY. */
    val malformed: Boolean = false,
    /** Raw arg text for the ledger (redacted on write, never stored raw). */
    val rawArgs: String? = null,
)

/**
 * Fail-closed admission control over a single [Capability] (design §6). This is the only brake in
 * the system once in-chat approval is gone, so its default on every uncertain branch is DENY.
 *
 * Decision order (each branch DENYs immediately; reaching the end ADMITs):
 *   revoked → lease-active(now ≤ expiresAt) → surface-allow(pkg ∈ surface) → verb-allow →
 *   sink-in-budget → sensitive/system-UI block → step ≤ maxSteps → ADMIT.
 *
 * Properties enforced here: P17 (surface), P20 (revoke ⇒ all DENY, incl. children via the shared
 * token), P21 (expiry), P22 (ADMIT ≤ maxSteps), P23 (pure/deterministic), P24 (unknown/malformed ⇒
 * DENY), P25 (exactly one redacted ledger entry per decision), S2 (the tool path calls this BEFORE
 * the backend). Every decision appends exactly one [Audit] entry.
 *
 * NOTE: in v1 only [Verb.OBSERVE] is ever requested by a live tool; the write-verb branches
 * (sink/sensitive) ship and are tested so #198 gates on a proven kernel, not a new one.
 */
class CapabilityGuard(
    private val capability: Capability,
    private val clock: TrustClock,
    val audit: Audit = Audit(),
) {
    // ADMIT counter (design P22). Atomic so concurrent tool steps can't race past maxSteps.
    private val admitted = AtomicInteger(0)

    fun authorize(request: AuthRequest): Decision {
        val (decision, reason) = decide(request)
        audit.append(
            verb = request.verb,
            targetPkg = request.targetPkg,
            decision = decision,
            reason = reason,
            rawArgs = request.rawArgs,
        )
        return decision
    }

    private fun decide(request: AuthRequest): Pair<Decision, DenyReason?> {
        // Fail-closed on anything we could not even parse (design I3/P24).
        if (request.malformed) return Decision.DENY to DenyReason.MALFORMED

        // Revocation cascade (shared token ⇒ a revoked ancestor denies this child too). P20.
        if (capability.isRevoked) return Decision.DENY to DenyReason.REVOKED

        // Lease/TTL via the TRUST clock, never System.now. P21.
        if (clock.now() > capability.lease.expiresAt) {
            return Decision.DENY to DenyReason.LEASE_EXPIRED
        }

        // Surface whitelist; default-empty ⇒ deny-all. P17/S1. A null pkg can never match.
        val pkg = request.targetPkg
        if (pkg == null || pkg !in capability.surface) {
            return Decision.DENY to DenyReason.SURFACE_NOT_ALLOWED
        }

        // Verb whitelist. Unknown/unauthorized verb ⇒ DENY (fail-closed). P24.
        if (request.verb !in capability.verbs) {
            return Decision.DENY to DenyReason.VERB_NOT_ALLOWED
        }

        // Sink must be inside the budget. A sink not in budget (incl. an action that declares one
        // when the budget is empty) ⇒ DENY (P24 unknown-sink, P19 conservation foundation).
        val sink = request.sink
        if (sink != null && sink !in capability.sinkBudget) {
            return Decision.DENY to DenyReason.SINK_NOT_IN_BUDGET
        }

        // Observable ≠ actionable: password/FLAG_SECURE nodes and system/grant UI are never write
        // targets, regardless of surface (design I8/P18). Read verbs carry neither flag.
        if (request.sensitiveNode || request.systemUiTarget) {
            return Decision.DENY to DenyReason.SENSITIVE_BLOCKED
        }

        // Step budget (design P22): ADMIT count ≤ maxSteps; the (maxSteps+1)-th would-be ADMIT DENYs.
        // We reserve the slot atomically and roll back if we somehow exceeded (no other branch can
        // intervene after this point, but the rollback keeps the counter exact under concurrency).
        val next = admitted.incrementAndGet()
        if (next > capability.lease.maxSteps) {
            admitted.decrementAndGet()
            return Decision.DENY to DenyReason.STEP_BUDGET_EXCEEDED
        }

        return Decision.ADMIT to null
    }

    /** Number of ADMITs granted so far (design P22 boundary; exposed for tests/telemetry). */
    val admitCount: Int get() = admitted.get()

    /**
     * Kill-switch entry point (design I9/P20). Revokes the whole capability subtree: future
     * authorize → DENY(REVOKED) for this guard AND every child sharing the token, and any in-flight
     * backend work registered on the token is cancelled. O(1) cascade — children share the flag.
     */
    fun revoke() = capability.revocation.revoke()

    val isRevoked: Boolean get() = capability.isRevoked
}
