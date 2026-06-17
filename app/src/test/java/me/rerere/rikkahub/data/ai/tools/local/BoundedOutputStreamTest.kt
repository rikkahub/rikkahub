package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BoundedOutputStream], the bounded sink that keeps SSH stdout/stderr from
 * OOMing the app on a chatty remote command. The seam under test is pure (write bytes, read
 * back [BoundedOutputStream.snapshot]); the live channel wiring is verified on-device.
 */
class BoundedOutputStreamTest {

    @Test
    fun `output under the cap is returned verbatim with no truncation marker`() {
        val sink = BoundedOutputStream(64)
        val data = "hello world".toByteArray(Charsets.UTF_8)
        sink.write(data, 0, data.size)
        assertEquals("hello world", sink.snapshot())
    }

    @Test
    fun `single-byte writes under the cap round-trip exactly`() {
        val sink = BoundedOutputStream(64)
        "abc".toByteArray(Charsets.UTF_8).forEach { sink.write(it.toInt()) }
        assertEquals("abc", sink.snapshot())
    }

    @Test
    fun `output over the cap is truncated and reports the true overflow byte count`() {
        val cap = 100
        val sink = BoundedOutputStream(cap)
        // Write far more than cap + slack so the stream must discard bytes.
        val total = cap + 5_000
        val data = ByteArray(total) { 'a'.code.toByte() }
        sink.write(data, 0, data.size)

        val out = sink.snapshot()
        // First [cap] chars are kept, then the marker counts every byte beyond the cap —
        // including the ones that were discarded and never held in memory.
        assertEquals("a".repeat(cap) + "\n…[truncated; ${total - cap} bytes more]", out)
    }

    @Test
    fun `discarded bytes are counted even when delivered across many writes`() {
        val cap = 50
        val sink = BoundedOutputStream(cap)
        val chunk = ByteArray(1_000) { 'x'.code.toByte() }
        repeat(20) { sink.write(chunk, 0, chunk.size) } // 20_000 bytes total

        val out = sink.snapshot()
        assertTrue(out.startsWith("x".repeat(cap)))
        assertTrue(out.endsWith("[truncated; ${20_000 - cap} bytes more]"))
    }

    @Test
    fun `output exactly at the cap is not marked as truncated`() {
        val cap = 32
        val sink = BoundedOutputStream(cap)
        val data = ByteArray(cap) { 'b'.code.toByte() }
        sink.write(data, 0, data.size)

        val out = sink.snapshot()
        assertEquals("b".repeat(cap), out)
        assertFalse(out.contains("truncated"))
    }

    @Test
    fun `empty stream snapshots to an empty string`() {
        assertEquals("", BoundedOutputStream(16).snapshot())
    }

    @Test
    fun `multibyte output whose byte count fits the cap is not falsely truncated`() {
        // Regression: snapshot() compared the BYTE total against a CHAR-based take. With the
        // byte count (9) above the smaller char count, the old path could emit a truncation
        // marker even though every byte was kept. Three CJK chars (9 bytes) under a 16-byte cap
        // must round-trip verbatim.
        val cap = 16
        val sink = BoundedOutputStream(cap)
        val data = "你好世".toByteArray(Charsets.UTF_8) // 9 bytes, 3 chars
        sink.write(data, 0, data.size)
        val out = sink.snapshot()
        assertEquals("你好世", out)
        assertFalse(out.contains("truncated"))
    }

    @Test
    fun `multibyte output over the cap snaps to a code-point boundary and counts bytes`() {
        // Four CJK chars = 12 bytes. cap=7 must keep only whole 3-byte chars (2 chars = 6 bytes),
        // never split a 3-byte sequence, and report the remaining 6 bytes honestly.
        val cap = 7
        val sink = BoundedOutputStream(cap)
        val data = "你好世界".toByteArray(Charsets.UTF_8) // 12 bytes
        sink.write(data, 0, data.size)
        val out = sink.snapshot()
        assertEquals("你好" + "\n…[truncated; 6 bytes more]", out)
        // The kept prefix is valid UTF-8 (no mojibake from a split sequence).
        assertTrue(out.startsWith("你好"))
    }

    @Test
    fun `emoji output over the cap keeps whole surrogate-pair code points`() {
        // Each emoji is a 4-byte UTF-8 sequence (a surrogate pair in Java). cap=5 fits exactly
        // one emoji (4 bytes); the boundary back-off must not split the 4-byte sequence.
        val cap = 5
        val sink = BoundedOutputStream(cap)
        val data = "😀😁".toByteArray(Charsets.UTF_8) // 8 bytes
        sink.write(data, 0, data.size)
        val out = sink.snapshot()
        assertEquals("😀" + "\n…[truncated; 4 bytes more]", out)
    }
}
