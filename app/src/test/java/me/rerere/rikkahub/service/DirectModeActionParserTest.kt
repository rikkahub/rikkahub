package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectModeActionParserTest {

    @Test
    fun `parses well-formed array`() {
        val r = DirectModeActionRunner.parse("""[{"tool":"x","args":{"k":"v"}}]""")
        assertTrue(r.isSuccess)
        assertEquals(1, r.getOrThrow().size)
        assertEquals("x", r.getOrThrow()[0].tool)
    }

    @Test
    fun `parses two actions`() {
        val r = DirectModeActionRunner.parse(
            """[{"tool":"a","args":{}},{"tool":"b","args":{"n":1}}]"""
        )
        assertEquals(2, r.getOrThrow().size)
    }

    @Test
    fun `rejects empty array`() {
        val r = DirectModeActionRunner.parse("[]")
        assertTrue(r.isFailure)
        assertEquals("empty_actions", (r.exceptionOrNull() as DirectModeActionRunner.ParseError).code)
    }

    @Test
    fun `rejects non-array root`() {
        assertTrue(DirectModeActionRunner.parse("""{"tool":"x"}""").isFailure)
    }

    @Test
    fun `rejects malformed JSON`() {
        assertTrue(DirectModeActionRunner.parse("not json").isFailure)
    }

    @Test
    fun `rejects action missing tool field`() {
        assertTrue(DirectModeActionRunner.parse("""[{"args":{}}]""").isFailure)
    }
}
