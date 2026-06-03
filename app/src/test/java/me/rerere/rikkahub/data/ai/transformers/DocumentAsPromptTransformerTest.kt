package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the attachment-ordering bug (issue #20): the transformer inserted each
 * document prompt at index 0 inside a forEach loop, reversing the order of multiple documents so
 * attachment order A B C reached the model as C B A.
 */
class DocumentAsPromptTransformerTest {

    private fun document(name: String) = UIMessagePart.Document(
        url = "file:///$name",
        fileName = name,
        mime = "text/plain",
    )

    @Test
    fun `multiple documents keep their original order in the prompt block`() {
        val parts = listOf(
            document("A.txt"),
            document("B.txt"),
            document("C.txt"),
            UIMessagePart.Text("user question"),
        )

        val result = DocumentAsPromptTransformer.buildPartsWithDocumentPrompts(parts) { doc ->
            "content of ${doc.fileName}"
        }

        val joined = result.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val idxA = joined.indexOf("user sent a file: A.txt")
        val idxB = joined.indexOf("user sent a file: B.txt")
        val idxC = joined.indexOf("user sent a file: C.txt")

        assertTrue("A header present", idxA >= 0)
        assertTrue("B header present", idxB >= 0)
        assertTrue("C header present", idxC >= 0)
        // Bug produced C before B before A.
        assertTrue("A precedes B", idxA < idxB)
        assertTrue("B precedes C", idxB < idxC)
    }

    @Test
    fun `document prompts are prepended before the original parts`() {
        val parts = listOf(
            document("A.txt"),
            UIMessagePart.Text("user question"),
        )

        val result = DocumentAsPromptTransformer.buildPartsWithDocumentPrompts(parts) { doc ->
            "content of ${doc.fileName}"
        }

        val texts = result.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertTrue("first text is the document prompt", texts.first().contains("user sent a file: A.txt"))
        assertEquals("user question", texts.last())
    }

    @Test
    fun `parts without documents are returned unchanged`() {
        val parts = listOf<UIMessagePart>(UIMessagePart.Text("just text"))

        val result = DocumentAsPromptTransformer.buildPartsWithDocumentPrompts(parts) { "unused" }

        assertEquals(parts, result)
    }
}
