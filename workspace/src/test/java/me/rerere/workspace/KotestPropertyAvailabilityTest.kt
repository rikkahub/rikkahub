package me.rerere.workspace

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that `:workspace` can compile and run a Kotest `checkAll` property — i.e. that
 * `testImplementation(libs.kotest.property)` is on the test classpath. This is the dependency the
 * M1 cwd-policy property suites (W-I1..W-B6) need; without it those suites would not compile. The
 * shape mirrors the existing `@Test` + `runBlocking { checkAll(...) }` convention used by the
 * automation capability-guard property tests, NOT the Kotest spec runner.
 */
class KotestPropertyAvailabilityTest {
    @Test
    fun `kotest-property checkAll is available on the workspace test classpath`() {
        runBlocking {
            checkAll(50, Arb.int()) { n ->
                assertTrue(n + 0 == n)
            }
        }
    }
}
