package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageFtsManagerTest {
    @Test
    fun `search operators are treated as text`() {
        assertEquals("\"AND\"", "AND".toFtsLiteralQuery())
        assertEquals("\"foo\" AND \"OR\" AND \"bar\"", "foo OR bar".toFtsLiteralQuery())
    }

    @Test
    fun `quotes are escaped and blank input stays blank`() {
        assertEquals("\"say\"\"hi\"", "say\"hi".toFtsLiteralQuery())
        assertEquals("", "  \n ".toFtsLiteralQuery())
    }
}
