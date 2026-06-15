package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class A2aAgentCardPrecedenceTest {

    @Test
    fun `application routes are registered before static spa fallback`() {
        val source = listOf(
            File("web/src/main/java/me/rerere/rikkahub/web/Entry.kt"),
            File("../web/src/main/java/me/rerere/rikkahub/web/Entry.kt"),
        ).first { it.isFile }.readText()
        val moduleIndex = source.indexOf("        module()")
        val staticIndex = source.indexOf("            staticResources")

        assertTrue(moduleIndex >= 0)
        assertTrue(staticIndex >= 0)
        assertTrue(moduleIndex < staticIndex)
    }
}
