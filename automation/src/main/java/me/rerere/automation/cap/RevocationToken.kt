package me.rerere.automation.cap

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared revocation state for a capability subtree (design I9, property P20).
 *
 * A root [Capability] creates one; every [Capability.attenuate] child reuses the SAME token. So a
 * single [revoke] call:
 *  1. flips [isRevoked] true for the parent AND every descendant at once (cascade, O(1) — no tree
 *     walk, the children already share the flag), and
 *  2. fires every currently-registered in-flight canceller, so work parked inside the backend is
 *     cancelled rather than allowed to complete (the "revoke cancels in-flight" requirement the
 *     gate review elevated from a PBT gap to a core invariant).
 *
 * Idempotent: revoking twice is a no-op the second time. Thread-safe (the backend runs on
 * Dispatchers.IO while the kill-switch revokes from the UI/overlay thread).
 */
class RevocationToken {
    private val revoked = AtomicBoolean(false)

    // Identity-keyed set of cancel callbacks for work in flight under this subtree. A canceller
    // removes itself on completion, so the map only ever holds genuinely in-flight work.
    private val inFlight = ConcurrentHashMap<Any, () -> Unit>()

    val isRevoked: Boolean get() = revoked.get()

    /**
     * Register an in-flight canceller and run [block], guaranteeing the canceller is deregistered
     * on every terminal path (normal return, exception, cancellation). If the token is ALREADY
     * revoked when [block] is about to run, [onAlreadyRevoked] runs instead — closing the race
     * where revoke() fires between the authorize() check and the backend call.
     */
    fun <T> guardInFlight(
        cancel: () -> Unit,
        onAlreadyRevoked: () -> T,
        block: () -> T,
    ): T {
        if (isRevoked) return onAlreadyRevoked()
        val handle = register(cancel)
        try {
            // Re-check after registering: revoke() may have run between the first check and
            // register(); if so it could have missed this canceller, so fire it ourselves.
            if (isRevoked) {
                cancel()
                return onAlreadyRevoked()
            }
            return block()
        } finally {
            unregister(handle)
        }
    }

    /** Flip to revoked and fire every registered in-flight canceller exactly once. */
    fun revoke() {
        if (!revoked.compareAndSet(false, true)) return
        // Snapshot then clear so a concurrent unregister can't double-fire, and each canceller runs.
        val callbacks = inFlight.values.toList()
        inFlight.clear()
        callbacks.forEach { it() }
    }

    fun register(cancel: () -> Unit): Any {
        val key = Any()
        inFlight[key] = cancel
        return key
    }

    fun unregister(handle: Any) {
        inFlight.remove(handle)
    }
}
