package me.rerere.ai.ui

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PROPERTY #1 — METAMORPHIC chunk-granularity invariance over the streaming merge engine
 * ([UIMessage.appendChunk] via [handleMessageChunk]).
 *
 * The streamed merge must be invariant to HOW a stream is split into chunks: two different
 * fragmentations of the SAME (reasoningBody, textBody) must fold to the same final content.
 *
 * What is compared: ONLY the concatenated Reasoning.reasoning string and Text.text string, plus
 * the part-type structure. Reasoning.finishedAt is DELIBERATELY ignored — the "Handle Reasoning
 * End" branch (Message.kt:148-157) stamps finishedAt = Clock.now() when a non-reasoning delta
 * follows reasoning, and the two chunkings cross that boundary at different wall-clock moments, so
 * timestamps legitimately differ and are not part of "content".
 *
 * Non-empty fragments are required because appendChunk skips empty Text (Message.kt:42) and empty
 * Reasoning (Message.kt:75) deltas; an empty body therefore contributes zero chunks for that
 * modality. Role is held uniform ASSISTANT so handleMessageChunk never forks a new message.
 */
class MessageChunkGranularityPropertyTest {

    private fun assistantSeed() = listOf(
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
    )

    private fun chunkOf(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = "test",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null
            )
        )
    )

    private fun List<UIMessage>.applyAll(chunks: List<MessageChunk>): List<UIMessage> {
        var acc = this
        for (c in chunks) acc = acc.handleMessageChunk(c)
        return acc
    }

    /**
     * Splits [body] into NON-EMPTY fragments whose concatenation equals [body] exactly. Strategy is
     * chosen per body: 0 = char-by-char, 1 = random-sized pieces cut at Arb.int(1..remaining)
     * boundaries until consumed. Empty body -> empty fragment list (contributes no chunks).
     */
    private val arbSplitter: (String) -> Arb<List<String>> = { body ->
        arbitrary { rs ->
            if (body.isEmpty()) return@arbitrary emptyList()
            val strategy = Arb.int(0..1).bind()
            val fragments = if (strategy == 0) {
                body.map { it.toString() }
            } else {
                val out = mutableListOf<String>()
                var start = 0
                while (start < body.length) {
                    val remaining = body.length - start
                    val cut = Arb.int(1..remaining).bind()
                    out.add(body.substring(start, start + cut))
                    start += cut
                }
                out
            }
            // In-generator guard: the chunking must be lossless and contain no empty fragment.
            check(fragments.joinToString("") == body) { "splitter lost content for body=$body" }
            check(fragments.none { it.isEmpty() }) { "splitter produced an empty fragment" }
            fragments
        }
    }

    private fun chunkSequence(
        reasoningFragments: List<String>,
        textFragments: List<String>,
    ): List<MessageChunk> {
        val reasoningChunks = reasoningFragments.map { frag ->
            chunkOf(UIMessagePart.Reasoning(reasoning = frag, finishedAt = null))
        }
        val textChunks = textFragments.map { frag -> chunkOf(UIMessagePart.Text(frag)) }
        return reasoningChunks + textChunks
    }

    /** Content fingerprint: concatenated reasoning + concatenated text, ignoring timestamps. */
    private fun content(messages: List<UIMessage>): Pair<String, String> {
        val parts = messages.last().parts
        val reasoning = parts.filterIsInstance<UIMessagePart.Reasoning>()
            .joinToString("") { it.reasoning }
        val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        return reasoning to text
    }

    /**
     * One test case: a canonical (reasoningBody, textBody) and TWO independent fragmentations of it
     * (each chosen char-by-char or random-sized, independently for reasoning and text).
     */
    private data class Case(
        val reasoningBody: String,
        val textBody: String,
        val seqA: List<MessageChunk>,
        val seqB: List<MessageChunk>,
    )

    private val arbCase: Arb<Case> = arbitrary {
        val reasoningBody = Arb.string(0..40).bind()
        val textBody = Arb.string(0..40).bind()
        val seqA = chunkSequence(
            arbSplitter(reasoningBody).bind(),
            arbSplitter(textBody).bind(),
        )
        val seqB = chunkSequence(
            arbSplitter(reasoningBody).bind(),
            arbSplitter(textBody).bind(),
        )
        Case(reasoningBody, textBody, seqA, seqB)
    }

    @Test
    fun `merge is invariant to chunk granularity for reasoning and text content`() {
        runBlocking {
            checkAll(200, arbCase) { case ->
                val finalA = content(assistantSeed().applyAll(case.seqA))
                val finalB = content(assistantSeed().applyAll(case.seqB))

                // The two independent chunkings must agree...
                assertEquals(finalB, finalA)
                // ...and both must reproduce the canonical content exactly.
                assertEquals(case.reasoningBody to case.textBody, finalA)
            }
        }
    }
}
