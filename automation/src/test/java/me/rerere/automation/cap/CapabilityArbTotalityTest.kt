package me.rerere.automation.cap

import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import org.junit.Test

/**
 * Regression for the seed-dependent generator flake that reddened master 2026-06-09.
 *
 * Root cause: `Arb.set(elementArb, 0..cardinality)` is NON-TOTAL at the cardinality boundary — it is
 * a coupon-collector with a bounded retry budget, so asking it for a set whose size equals the
 * element arb's distinct cardinality throws
 * `IllegalStateException: the target size requirement of N could not be satisfied` for ~1/5000
 * unlucky seeds. `arbRootCapability`'s surface axis (a 6-package universe asked for a size-6 set) was
 * the proven offender; `arbValidAttenuation` carried the same footgun. Because the security PBT runs
 * 150+ root samples per CI run, the cumulative per-run failure was a few percent — green on the PR,
 * red on the post-merge master run.
 *
 * The generators must be TOTAL: every seed yields a value. They now build subsets via Bernoulli
 * inclusion ([arbSubsetOf]), which never throws. This sweep FAILS before that fix (the old `Arb.set`
 * path throws within the seed range) and PASSES after. It guards the property, not an example.
 */
class CapabilityArbTotalityTest {

    @Test
    fun `arbRootCapability is total over a wide seed sweep`() {
        for (seed in 0L until 20_000L) {
            arbRootCapability().next(RandomSource.seeded(seed)) // must not throw for any seed
        }
    }

    @Test
    fun `arbRootAndChild is total over a wide seed sweep`() {
        // Exercises arbValidAttenuation (the other half of the same footgun) too.
        for (seed in 0L until 20_000L) {
            arbRootAndChild().next(RandomSource.seeded(seed))
        }
    }
}
