package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * Measure-only benchmark (SPEC M5, candidate #2): per-chunk merge cost of the streaming
 * path `List<UIMessage>.handleMessageChunk` -> `UIMessage.appendChunk` over a synthetic
 * 1k-chunk text stream.
 *
 * Records ns/op and allocated bytes/op as BEFORE-evidence for deciding whether the
 * per-chunk immutable list rebuild is material. No production code is touched; no
 * wall-clock threshold is asserted (CI-stable: assertions only check the harness
 * produced sane, non-degenerate measurements and that the merge conserved content).
 *
 * Quartile breakdown is reported because the Text merge concatenates the accumulated
 * string per chunk (`lastPart.text + deltaPart.text`), so cost is expected to grow
 * with stream position — a single mean would hide that.
 *
 * VERDICT (audit close-out): measured, not material, no change. nsPerOp=2001,
 * allocBytesPerOp=12386, quartile ns/op 822 -> 1518 -> 2315 -> 3345 — the growth is
 * real but the absolute per-chunk cost sits orders of magnitude below the throttled
 * UI publish window, so the per-chunk immutable rebuild stays untouched.
 */
class AppendChunkBenchTest {
    @Test
    fun `synthetic 1k-chunk stream is merged by the real production path`() {
        val chunks = buildTextChunks(CHUNK_COUNT, CHUNK_TEXT_LENGTH)
        var messages = listOf(UIMessage.user("bench prompt"))
        for (chunk in chunks) {
            messages = messages.handleMessageChunk(chunk)
        }
        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages.last().role)
        assertEquals(
            chunks.joinToString(separator = "") { chunk ->
                (chunk.choices[0].delta!!.parts[0] as UIMessagePart.Text).text
            },
            messages.last().toText(),
        )
    }

    @Test
    fun `appendChunk ns per op and alloc bytes per op over 1k chunks`() {
        assumeTrue("JVM does not expose per-thread allocation counters", allocationCounterAvailable())

        val chunks = buildTextChunks(CHUNK_COUNT, CHUNK_TEXT_LENGTH)
        val result = benchAppendChunk(chunks, warmupRounds = 3, measuredRounds = 5)

        println(
            "[BENCH appendChunk] chunks=${result.chunks} chunkTextLen=$CHUNK_TEXT_LENGTH " +
                "finalTextLen=${CHUNK_COUNT * CHUNK_TEXT_LENGTH} " +
                "nsPerOp=${"%.0f".format(result.nsPerOp)} " +
                "allocBytesPerOp=${"%.0f".format(result.allocBytesPerOp)}"
        )
        result.quartileNsPerOp.forEachIndexed { index, ns ->
            println("[BENCH appendChunk] quartile${index + 1}NsPerOp=${"%.0f".format(ns)}")
        }

        assertEquals(CHUNK_COUNT, result.chunks)
        assertEquals(4, result.quartileNsPerOp.size)
        assertTrue("harness must have timed real work", result.nsPerOp > 0.0)
        assertTrue("appendChunk allocates per op by construction", result.allocBytesPerOp > 0.0)
    }

    private fun benchAppendChunk(
        chunks: List<MessageChunk>,
        warmupRounds: Int,
        measuredRounds: Int,
    ): BenchResult {
        val threadBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val quartileSize = chunks.size / 4
        var sink = 0

        repeat(warmupRounds) { sink += runStream(chunks).last().parts.size }

        var bestTotalNs = Long.MAX_VALUE
        var bestQuartileNsPerOp = List(4) { 0.0 }
        var allocTotal = 0L
        repeat(measuredRounds) {
            var messages = listOf(UIMessage.user("bench prompt"))
            val quartileNs = LongArray(4)
            val allocBefore = threadBean.currentThreadAllocatedBytes
            val start = System.nanoTime()
            for (quartile in 0 until 4) {
                val quartileStart = System.nanoTime()
                for (index in quartile * quartileSize until (quartile + 1) * quartileSize) {
                    messages = messages.handleMessageChunk(chunks[index])
                }
                quartileNs[quartile] = System.nanoTime() - quartileStart
            }
            val totalNs = System.nanoTime() - start
            allocTotal += threadBean.currentThreadAllocatedBytes - allocBefore
            sink += messages.last().parts.size
            if (totalNs < bestTotalNs) {
                bestTotalNs = totalNs
                bestQuartileNsPerOp = quartileNs.map { it.toDouble() / quartileSize }
            }
        }
        check(sink > 0) { "merged stream must not be optimized away" }

        return BenchResult(
            chunks = chunks.size,
            nsPerOp = bestTotalNs.toDouble() / chunks.size,
            allocBytesPerOp = allocTotal.toDouble() / measuredRounds / chunks.size,
            quartileNsPerOp = bestQuartileNsPerOp,
        )
    }

    private fun runStream(chunks: List<MessageChunk>): List<UIMessage> {
        var messages = listOf(UIMessage.user("bench prompt"))
        for (chunk in chunks) {
            messages = messages.handleMessageChunk(chunk)
        }
        return messages
    }

    private fun allocationCounterAvailable(): Boolean {
        val bean = ManagementFactory.getThreadMXBean()
        return bean is com.sun.management.ThreadMXBean && bean.isThreadAllocatedMemorySupported
    }

    private fun buildTextChunks(count: Int, textLength: Int): List<MessageChunk> {
        return (0 until count).map { index ->
            val text = buildString(textLength) {
                while (length < textLength) {
                    append("chunk$index ")
                }
            }.substring(0, textLength)
            MessageChunk(
                id = "bench-$index",
                model = "bench-model",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Text(text)),
                        ),
                        message = null,
                        finishReason = null,
                    )
                ),
            )
        }
    }

    private data class BenchResult(
        val chunks: Int,
        val nsPerOp: Double,
        val allocBytesPerOp: Double,
        val quartileNsPerOp: List<Double>,
    )

    companion object {
        private const val CHUNK_COUNT = 1000
        private const val CHUNK_TEXT_LENGTH = 24
    }
}
