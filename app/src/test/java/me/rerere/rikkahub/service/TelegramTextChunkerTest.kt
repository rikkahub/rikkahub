package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramTextChunkerTest {

    @Test fun `short string returns single chunk`() {
        val s = "hello world"
        assertEquals(listOf(s), chunkForTelegram(s, 100))
    }

    @Test fun `cuts at paragraph break inside trailing window`() {
        val a = "a".repeat(70) + "\n\n" + "b".repeat(70)
        val parts = chunkForTelegram(a, 100)
        assertEquals(2, parts.size)
        assertTrue("first chunk ends with a-run, no leading b", parts[0].endsWith("\n\n"))
    }

    @Test fun `falls back to single newline when no paragraph break`() {
        val s = "a".repeat(80) + "\n" + "b".repeat(80)
        val parts = chunkForTelegram(s, 100)
        assertEquals(2, parts.size)
        assertEquals(80, parts[0].length)
    }

    @Test fun `falls back to hard cut when no newline in window`() {
        val s = "x".repeat(250)
        val parts = chunkForTelegram(s, 100)
        assertEquals(3, parts.size)
        assertEquals(100, parts[0].length)
        assertEquals(100, parts[1].length)
        assertEquals(50, parts[2].length)
    }

    @Test fun `does not slice through a surrogate pair at the cut boundary`() {
        // U+1F600 (😀) is a 2-char surrogate pair: high surrogate D83D + low surrogate DE00.
        // Place it so its low surrogate lands exactly at index `n`. A naive substring(0, n)
        // would orphan the high surrogate at index n-1.
        val emoji = "😀"
        // 99 'a's + emoji (2 chars) + tail. cut=100 would orphan the high surrogate at 99.
        val s = "a".repeat(99) + emoji + "b".repeat(50)
        val parts = chunkForTelegram(s, 100)
        // The walk-back makes the first chunk end at 99 (no surrogate), so the emoji
        // ends up intact at the start of chunk 2.
        assertEquals(99, parts[0].length)
        assertTrue("chunk[0] ends without the high surrogate", !parts[0].last().isHighSurrogate())
        assertTrue("chunk[1] starts with the full surrogate pair",
            parts[1].length >= 2 && parts[1][0].isHighSurrogate() && parts[1][1].isLowSurrogate())
    }

    @Test fun `multiple emoji concatenated never produce orphan surrogates`() {
        // 60 emoji = 120 chars (each emoji is 2 UTF-16 code units), exceeds 100 cap.
        val emoji = "😀"
        val s = emoji.repeat(60)
        val parts = chunkForTelegram(s, 100)
        for ((i, p) in parts.withIndex()) {
            assertTrue("chunk $i must not start with a low surrogate (orphan)",
                p.isEmpty() || !p[0].isLowSurrogate())
            assertTrue("chunk $i must not end with a high surrogate (orphan)",
                p.isEmpty() || !p.last().isHighSurrogate())
        }
        // Round-trip: concatenation should equal the original (modulo trimStart('\n'),
        // which is a no-op here since there are no newlines).
        assertEquals(s, parts.joinToString(""))
    }

    @Test fun `prefers paragraph break over single newline when both fit window`() {
        // Both breaks must sit in the TRAILING half (n/2 = 50) of the chunk to be eligible
        // — otherwise the function intentionally falls back to the later cut so chunk 0
        // doesn't ship near-empty. Para at index 70..71 (later) and a stray newline at 82.
        val s = "a".repeat(70) + "\n\n" + "b".repeat(10) + "\n" + "c".repeat(50)
        val parts = chunkForTelegram(s, 100)
        // Chunk 1 should end at the "\n\n" (cut = 72), preferred over the "\n" at 82.
        assertEquals(72, parts[0].length)
        assertTrue(parts[0].endsWith("\n\n"))
    }

    @Test fun `falls back to later newline when paragraph break is too early in chunk`() {
        // Para at index 40 falls outside the trailing-half window (50), so the function
        // intentionally takes the later single-newline cut instead — would otherwise ship
        // a half-empty chunk that wastes Telegram round-trips.
        val s = "a".repeat(40) + "\n\n" + "b".repeat(20) + "\n" + "c".repeat(50)
        val parts = chunkForTelegram(s, 100)
        // Cut at the "\n" (index 62) — chunk 0 runs 0..61 (ends with 'b'), the "\n" is
        // consumed by trimStart on the next chunk. Net effect: chunk 0 stays naturally
        // bounded by the newline without leading whitespace on chunk 1.
        assertEquals(62, parts[0].length)
        assertTrue(parts[0].endsWith("b"))
        assertTrue(parts[1].startsWith("c"))
    }
}
