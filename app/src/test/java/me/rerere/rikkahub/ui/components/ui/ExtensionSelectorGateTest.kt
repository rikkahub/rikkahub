package me.rerere.rikkahub.ui.components.ui

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gate for #283: the Extensions sheet must do NO skills disk IO until the user settles on the
 * Skills tab, and must load at most once per sheet instance. [awaitPage] is the seam that enforces
 * both — it suspends until the settled-page flow first emits the target index, then returns, having
 * cancelled the upstream. The same "zero IO until the tab is settled" property must also hold for
 * any future tab that loads lazily (e.g. a Workspace tab), so it is pinned here, not inside the
 * Compose layer (which this CI cannot run as a JVM unit test).
 */
class ExtensionSelectorGateTest {

    /**
     * INVARIANT (zero-until-target + single-shot): for any run of non-target pages, then the
     * target, then arbitrary trailing pages, [awaitPage] consumes exactly the leading non-target
     * pages (never short-circuiting early) and stops at the FIRST target — the trailing pages,
     * including any further targets, are never observed. So the guarded work runs once, only after
     * the target settles.
     */
    @Test
    fun `awaitPage fires once, only after the first target, never before`() = runBlocking<Unit> {
        val target = SKILLS_PAGE_INDEX
        val nonTarget = Arb.int(0..2)          // the three non-Skills tabs
        checkAll(Arb.list(nonTarget, 0..6), Arb.list(Arb.int(0..3), 0..6)) { prefix, suffix ->
            var nonTargetSeen = 0
            var targetSeen = 0
            var loadCount = 0

            (prefix + target + suffix).asFlow()
                .onEach { if (it == target) targetSeen++ else nonTargetSeen++ }
                .awaitPage(target)
            loadCount++   // the guarded load runs exactly here, after awaitPage returns

            assertEquals(1, loadCount)
            assertEquals(prefix.size, nonTargetSeen)  // all leading non-targets consumed, suffix unreached
            assertEquals(1, targetSeen)               // only the first target observed → single-shot
        }
    }

    /** The issue's exact scenario: open → tabs 0,1,2 → Skills(3) → 0 → Skills(3). One load, after 0,1,2. */
    @Test
    fun `awaitPage matches the reported open-close cycle`() = runBlocking {
        var nonTargetSeen = 0
        var targetSeen = 0

        listOf(0, 1, 2, SKILLS_PAGE_INDEX, 0, SKILLS_PAGE_INDEX).asFlow()
            .onEach { if (it == SKILLS_PAGE_INDEX) targetSeen++ else nonTargetSeen++ }
            .awaitPage(SKILLS_PAGE_INDEX)

        assertEquals(3, nonTargetSeen)  // 0,1,2 consumed; trailing 0 never reached
        assertEquals(1, targetSeen)     // trailing second Skills(3) never reached
    }

    /** Boundary: the gate must target the same pager index the Skills tab lives at. */
    @Test
    fun `skills page index is pinned`() {
        assertEquals(3, SKILLS_PAGE_INDEX)
    }
}
