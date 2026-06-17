package me.rerere.automation.cap

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PBT for the [Surface] authority lattice (the YOLO data-structure-first change). [Surface] owns the
 * admission predicate ([Surface.allows]) and the attenuation lattice ([Surface.canAttenuateTo]); these
 * properties pin that a [Surface.Scoped] is exactly its package set, [Surface.Unbounded] admits
 * everything, and attenuation can only ever SHRINK authority (a scoped surface can never widen back to
 * unbounded). Mirrors the kotest style of the rest of the capability suite.
 */
class SurfacePropertyTest {

    private val universe = listOf(
        "com.example.app", "com.example.other", "com.bank.app", "com.shop.app", "org.foo.bar",
    )

    private fun <T> arbSubsetOf(items: Collection<T>): Arb<Set<T>> =
        arbitrary { items.filterTo(mutableSetOf()) { Arb.boolean().bind() } }

    private fun arbScoped(): Arb<Surface.Scoped> = arbitrary { Surface.Scoped(arbSubsetOf(universe).bind()) }

    private fun arbSurface(): Arb<Surface> = arbitrary {
        if (Arb.boolean().bind()) Surface.Unbounded else arbScoped().bind()
    }

    // ---- allows: Scoped is exactly its set; Unbounded admits everything ----
    @Test
    fun `allows mirrors the package set for Scoped and is total for Unbounded`() {
        runBlocking {
            checkAll(500, arbScoped(), Arb.element(universe + "com.not.listed")) { scoped, pkg ->
                assertTrue("Scoped.allows must equal membership", scoped.allows(pkg) == (pkg in scoped.packages))
                assertTrue("Unbounded admits every package", Surface.Unbounded.allows(pkg))
            }
        }
    }

    @Test
    fun `empty Scoped is deny-all`() {
        val empty = Surface.Scoped(emptySet())
        universe.forEach { assertFalse("an empty surface must deny every package", empty.allows(it)) }
    }

    // ---- canAttenuateTo: only ever SHRINK ----
    @Test
    fun `Unbounded can attenuate to anything`() {
        runBlocking {
            checkAll(300, arbSurface()) { child ->
                assertTrue("Unbounded ⊇ every surface", Surface.Unbounded.canAttenuateTo(child))
            }
        }
    }

    @Test
    fun `Scoped can attenuate only to a subset and never widens to Unbounded`() {
        runBlocking {
            checkAll(400, arbScoped(), arbScoped()) { parent, child ->
                assertTrue(
                    "Scoped→Scoped attenuation must equal subset containment",
                    parent.canAttenuateTo(child) == child.packages.all { it in parent.packages },
                )
                assertFalse(
                    "a scoped surface must never widen to Unbounded",
                    parent.canAttenuateTo(Surface.Unbounded),
                )
            }
        }
    }

    @Test
    fun `canAttenuateTo is reflexive`() {
        runBlocking {
            checkAll(300, arbSurface()) { s ->
                assertTrue("every surface can attenuate to itself", s.canAttenuateTo(s))
            }
        }
    }

    // ---- metamorphic: attenuation is transitive (a ⊇ b ⊇ c ⇒ a ⊇ c) ----
    @Test
    fun `canAttenuateTo is transitive`() {
        runBlocking {
            checkAll(500, arbSurface(), arbSurface(), arbSurface()) { a, b, c ->
                if (a.canAttenuateTo(b) && b.canAttenuateTo(c)) {
                    assertTrue("attenuation must be transitive", a.canAttenuateTo(c))
                }
            }
        }
    }
}
