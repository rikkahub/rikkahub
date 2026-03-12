package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MessageFtsManagerTest {
    @Test
    fun `current message fts schema is compatible`() {
        assertTrue(isMessageFtsSchemaCompatible(MESSAGE_FTS_REQUIRED_COLUMNS))
    }

    @Test
    fun `legacy message fts schema is incompatible`() {
        assertFalse(
            isMessageFtsSchemaCompatible(
                setOf("text", "node_id", "message_id", "conversation_id", "role")
            )
        )
    }

    @Test
    fun `search row parsing tolerates nullable display fields`() {
        val result = MessageFtsSearchRow(
            rowId = 7L,
            nodeId = "node-1",
            messageId = "msg-1",
            conversationId = "conv-1",
            title = null,
            updateAtRaw = "1700000000000",
            snippet = null,
        ).toSearchResultOrNull()

        assertNotNull(result)
        assertEquals("node-1", result?.nodeId)
        assertEquals("", result?.title)
        assertEquals("", result?.snippet)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), result?.updateAt)
    }

    @Test
    fun `search row parsing rejects rows without required identifiers`() {
        val result = MessageFtsSearchRow(
            rowId = 8L,
            nodeId = null,
            messageId = "msg-1",
            conversationId = "conv-1",
            title = "hello",
            updateAtRaw = "1700000000000",
            snippet = "snippet",
        ).toSearchResultOrNull()

        assertNull(result)
    }
}
