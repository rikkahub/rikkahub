package me.rerere.rikkahub.web.a2a

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class A2aAgentCardPrecedenceTest {

    @Test
    fun `web server keeps static spa fallback after application routes`() {
        val source = entrySource()
        val moduleIndex = source.indexOf("        module()")
        val staticIndex = source.indexOf("            staticResources")

        assertTrue(moduleIndex >= 0)
        assertTrue(staticIndex >= 0)
        assertTrue(moduleIndex < staticIndex)
    }

    @Test
    fun `no spa helper never mounts static resources`() {
        val source = entrySource()
        val helperStart = source.indexOf("fun startKtorServerNoSpa")
        val webStart = source.indexOf("fun startWebServer")
        val helperBody = source.substring(helperStart, webStart)

        assertTrue(helperStart >= 0)
        assertTrue(webStart > helperStart)
        assertTrue(!helperBody.contains("staticResources"))
        assertTrue(!helperBody.contains("singlePageApplication"))
    }

    private fun entrySource(): String = listOf(
            File("web/src/main/java/me/rerere/rikkahub/web/Entry.kt"),
            File("../web/src/main/java/me/rerere/rikkahub/web/Entry.kt"),
        ).first { it.isFile }.readText()
}
