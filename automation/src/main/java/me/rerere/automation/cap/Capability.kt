package me.rerere.automation.cap

/**
 * Object-capability model (#187 design §6). Because no in-chat approval is reachable while the
 * agent drives another app, this layer — not a human dialog — is the only safety rail. A
 * [Capability] is unforgeable and injected (design I2: no ambient authority); the model never
 * names one, it is closed over at tool-construction time in :app/UiAutomationTools.
 *
 * Authority can only ever SHRINK: [attenuate] hands out a child whose surface/verbs/sinkBudget are
 * subsets of the parent's and whose lease is no longer-lived (design I4/I16, properties P15/P16).
 * Revocation is shared and cascading: a parent and every descendant point at the same
 * [RevocationToken], so revoking the parent denies all children in O(1) and cancels in-flight work
 * (design I9, property P20).
 */
class Capability internal constructor(
    /** Package authority. [Surface.Scoped] default-empty = deny-all (design I3, property P17, S1). */
    val surface: Surface,
    val verbs: Set<Verb>,
    val sinkBudget: Set<Sink>,
    val lease: Lease,
    /**
     * Whether the host app ([SnapshotProjector.HOST_PACKAGE]) may itself be observed/acted on. Default
     * false: the host is excluded from projection and the act path host-pauses on it (design P2/P12).
     * Only the YOLO grant derivation flips this true (and it can never widen under [attenuate]).
     */
    val includeHost: Boolean = false,
    val parent: Capability? = null,
    val depth: Int = 0,
    /** Shared revocation state for this capability subtree (root creates it, children inherit). */
    internal val revocation: RevocationToken,
    /** Opaque conversation/session binding (design S1: a cap may not be reused across chats). */
    val sessionId: String,
) {
    /**
     * Produce an attenuated child. Every requested axis must be a SUBSET of this capability — a
     * request that tries to widen surface/verbs/sinkBudget, extend the lease, or raise maxSteps is
     * rejected with [IllegalArgumentException] (the "rogue exceeding-parent" case the generators
     * exercise; property P16). The child shares this subtree's [revocation], so revoking the parent
     * also kills the child (property P20). The child is bound to the same [sessionId].
     */
    fun attenuate(
        surface: Surface = this.surface,
        verbs: Set<Verb> = this.verbs,
        sinkBudget: Set<Sink> = this.sinkBudget,
        expiresAt: Long = this.lease.expiresAt,
        maxSteps: Int = this.lease.maxSteps,
        rateLimitPerMinute: Int = this.lease.rateLimitPerMinute,
        includeHost: Boolean = this.includeHost,
    ): Capability {
        require(this.surface.canAttenuateTo(surface)) {
            "attenuate cannot widen surface"
        }
        require(!includeHost || this.includeHost) {
            "attenuate cannot grant host access the parent lacks"
        }
        require(verbs.all { it in this.verbs }) {
            "attenuate cannot widen verbs"
        }
        require(sinkBudget.all { it in this.sinkBudget }) {
            "attenuate cannot widen sinkBudget"
        }
        require(expiresAt <= this.lease.expiresAt) {
            "attenuate cannot extend lease expiry"
        }
        require(maxSteps <= this.lease.maxSteps) {
            "attenuate cannot raise maxSteps"
        }
        require(rateLimitPerMinute <= this.lease.rateLimitPerMinute) {
            "attenuate cannot raise rateLimit"
        }
        return Capability(
            surface = surface,
            verbs = verbs,
            sinkBudget = sinkBudget,
            lease = Lease(expiresAt, maxSteps, rateLimitPerMinute),
            includeHost = includeHost,
            parent = this,
            depth = this.depth + 1,
            revocation = this.revocation,
            sessionId = this.sessionId,
        )
    }

    /** True iff this capability or any ancestor in its subtree has been revoked (design I9). */
    val isRevoked: Boolean get() = revocation.isRevoked

    companion object {
        /**
         * Mint a fresh root grant bound to a conversation/session (design S1). The root is the ONLY
         * place a capability is created from nothing; everything else is [attenuate]. Defaults are
         * strict: an empty surface denies every package until the caller explicitly grants one.
         */
        fun root(
            sessionId: String,
            surface: Surface = Surface.Scoped(emptySet()),
            verbs: Set<Verb> = emptySet(),
            sinkBudget: Set<Sink> = emptySet(),
            lease: Lease,
            includeHost: Boolean = false,
        ): Capability = Capability(
            surface = surface,
            verbs = verbs,
            sinkBudget = sinkBudget,
            lease = lease,
            includeHost = includeHost,
            parent = null,
            depth = 0,
            revocation = RevocationToken(),
            sessionId = sessionId,
        )
    }
}

/**
 * The package authority axis of a [Capability]. [Scoped] is the default, fail-closed shape: a finite
 * whitelist (empty = deny-all, design I3/S1/P17). [Unbounded] is the YOLO widening — every package is
 * in scope — minted ONLY by the YOLO grant derivation; a default/scoped grant never produces it.
 *
 * Modelled as a sealed type rather than a `Set<String> + boolean` so the contradictory state
 * ("unbounded yet carrying a non-empty whitelist") is unrepresentable, and so the attenuation lattice
 * ([canAttenuateTo]) and the admission predicate ([allows]) live ON the type — the surface owns its
 * own rules, the guard/derivation never re-implement them (the data structure makes the special cases
 * vanish, Linus §5).
 */
sealed interface Surface {
    /** True iff [pkg] is within this surface. */
    fun allows(pkg: String): Boolean

    /** True iff [child] is no wider than this surface — attenuation may only ever SHRINK authority. */
    fun canAttenuateTo(child: Surface): Boolean

    /** A finite package whitelist. Empty = deny-all. */
    data class Scoped(val packages: Set<String>) : Surface {
        override fun allows(pkg: String): Boolean = pkg in packages
        override fun canAttenuateTo(child: Surface): Boolean = when (child) {
            is Scoped -> child.packages.all { it in packages }
            Unbounded -> false // a scoped parent can never widen to unbounded
        }
    }

    /** Every package is in scope (YOLO). Only the acknowledged YOLO derivation mints this. */
    data object Unbounded : Surface {
        override fun allows(pkg: String): Boolean = true
        override fun canAttenuateTo(child: Surface): Boolean = true // anything is ⊆ unbounded
    }
}

/**
 * The action a capability authorizes. v1 only wires [OBSERVE] into a live tool; the write verbs
 * ship in the enum so the kernel and its proofs cover them, but no tool exposes them until #198.
 */
enum class Verb {
    OBSERVE,
    TAP,
    SET_TEXT,
    SCROLL,
    GLOBAL,
}

/**
 * A side-effecting outcome ("permission-laundering" guard axis, design §6). Reads have NO sink;
 * the sinks listed here are all v2 write outcomes. Carried in v1 so [Sink]-budget intersection
 * (P19, deferred) and the fail-closed unknown-sink rule (P24) are expressible from day one.
 */
enum class Sink {
    TYPE_INTO,
    SUBMIT,
    GLOBAL_NAV,
}

/**
 * Which sinks are DANGEROUS — i.e. trigger the out-of-band confirmation gate before a write lands
 * (#198 slice 11, design Q2 / I-act-5). Only [Sink.SUBMIT] (submit-class: send/pay/checkout/…) is
 * dangerous: it is the irreversible, side-effect-committing outcome a model must NOT reach without an
 * explicit user confirm. [Sink.TYPE_INTO] (typing does not commit) and [Sink.GLOBAL_NAV] (nav is
 * reversible) are NOT dangerous — they are budgeted but never gated. A pure predicate on the existing
 * enum (no enum member changes): the core consults it to decide whether to await the confirm channel.
 */
val Sink.isDangerous: Boolean
    get() = this == Sink.SUBMIT

/**
 * Time-boxed grant. The clock is a [TrustClock], never `System.now` (design §6/§7) — lease/TTL
 * properties (P21) must be reproducible. [maxSteps] caps total ADMITs over the lease (P22).
 */
data class Lease(
    val expiresAt: Long,
    val maxSteps: Int,
    val rateLimitPerMinute: Int = Int.MAX_VALUE,
)

/**
 * Injected monotonic clock for lease evaluation. NEVER `System.currentTimeMillis()` directly —
 * that makes temporal properties (P21) untestable. :app supplies a real implementation; tests
 * supply a hand-advanced one.
 */
fun interface TrustClock {
    fun now(): Long
}
