package me.rerere.ai.ui

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PROPERTY #2 — METAMORPHIC tool-delta interleave invariance (locks the PR #8 streamIndex fix).
 *
 * For TWO parallel tool calls, ANY interleaving of their argument fragments that PRESERVES per-tool
 * fragment order must yield the same two finalized Tools, keyed by streamIndex (Message.kt:96-112).
 * Reference = apply all of A then all of B. The interleaved fold must produce the identical
 * {toolCallId -> (toolName, input)} set.
 *
 * Tool equality already ignores the Transient streamIndex (proven in MessageStreamMergeTest), so we
 * compare on the persisted fields only.
 */
class ToolDeltaInterleavePropertyTest {

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

    private fun toolDelta(
        id: String = "",
        name: String = "",
        input: String = "",
        streamIndex: Int? = null,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
        output = emptyList(),
    ).also { it.streamIndex = streamIndex }

    private fun List<UIMessage>.applyAll(chunks: List<MessageChunk>): List<UIMessage> {
        var acc = this
        for (c in chunks) acc = acc.handleMessageChunk(c)
        return acc
    }

    private data class ToolSpec(val id: String, val name: String, val inputFragments: List<String>)

    /** Ordered fragment chunks for one tool: opening frame (id+name, empty input) then one delta per fragment. */
    private fun toolChunks(spec: ToolSpec, streamIndex: Int): List<MessageChunk> {
        val open = chunkOf(toolDelta(id = spec.id, name = spec.name, input = "", streamIndex = streamIndex))
        val frags = spec.inputFragments.map { frag ->
            chunkOf(toolDelta(input = frag, streamIndex = streamIndex))
        }
        return listOf(open) + frags
    }

    private data class Case(
        val specA: ToolSpec,
        val specB: ToolSpec,
        val interleaved: List<MessageChunk>,
    )

    // distinct non-blank ids; name arbitrary; 1..4 input fragments each of length 1..6.
    private val arbToolSpec: (String) -> Arb<ToolSpec> = { idSuffix ->
        arbitrary {
            ToolSpec(
                id = "id_" + idSuffix + "_" + Arb.string(1..6).bind().filter { it.isLetterOrDigit() }
                    .ifEmpty { "x" },
                name = Arb.string(0..6).bind(),
                inputFragments = Arb.list(Arb.string(1..6), 1..4).bind(),
            )
        }
    }

    private val arbCase: Arb<Case> = arbitrary {
        val specA = arbToolSpec("A").bind()
        val specB = arbToolSpec("B").bind()
        val seqA = toolChunks(specA, streamIndex = 0)
        val seqB = toolChunks(specB, streamIndex = 1)

        // Merge-mask interleaving that preserves each sequence's internal order: draw from A's head
        // when the boolean is true (and A has items left), else from B's head.
        val mask = Arb.list(Arb.boolean(), (seqA.size + seqB.size)..(seqA.size + seqB.size)).bind()
        val ai = seqA.iterator()
        val bi = seqB.iterator()
        val interleaved = mutableListOf<MessageChunk>()
        for (takeA in mask) {
            if (ai.hasNext() && (takeA || !bi.hasNext())) {
                interleaved.add(ai.next())
            } else if (bi.hasNext()) {
                interleaved.add(bi.next())
            }
        }
        while (ai.hasNext()) interleaved.add(ai.next())
        while (bi.hasNext()) interleaved.add(bi.next())

        Case(specA, specB, interleaved)
    }

    private fun toolsOf(messages: List<UIMessage>): Map<String, Pair<String, String>> =
        messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
            .associate { it.toolCallId to (it.toolName to it.input) }

    @Test
    fun `any order-preserving interleaving of two tool streams yields the same finalized tools`() {
        runBlocking {
            checkAll(200, arbCase) { case ->
                val expected = mapOf(
                    case.specA.id to (case.specA.name to case.specA.inputFragments.joinToString("")),
                    case.specB.id to (case.specB.name to case.specB.inputFragments.joinToString("")),
                )

                val merged = toolsOf(assistantSeed().applyAll(case.interleaved))
                assertEquals(expected, merged)
            }
        }
    }
}
