package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.document.DocumentExtractionResult
import me.rerere.rikkahub.data.document.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the attachment-ordering bug (issue #20) and the typed-result rendering
 * contract (issue #102): the transformer must keep multiple documents in order, prepend the prompt
 * block, and — critically — render only `Success` as `<content>`. A parse failure must never be
 * embedded as document content.
 */
class DocumentAsPromptTransformerTest {

    private fun document(name: String) = UIMessagePart.Document(
        url = "file:///$name",
        fileName = name,
        mime = "text/plain",
    )

    private fun success(text: String) =
        DocumentExtractionResult.Success(text, DocumentType.PlainText, "text/plain")

    @Test
    fun `multiple documents keep their original order in the prompt block`() {
        val parts = listOf(
            document("A.txt"),
            document("B.txt"),
            document("C.txt"),
            UIMessagePart.Text("user question"),
        )

        val result = DocumentAsPromptTransformer.buildPartsWithDocumentPrompts(parts) { doc ->
            success("content of ${doc.fileName}")
        }

        val joined = result.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val idxA = joined.indexOf("A.txt")
        val idxB = joined.indexOf("B.txt")
        val idxC = joined.indexOf("C.txt")

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
            success("content of ${doc.fileName}")
        }

        val texts = result.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertTrue("first text is the document prompt", texts.first().contains("A.txt"))
        assertEquals("user question", texts.last())
    }

    @Test
    fun `parts without documents are returned unchanged`() {
        val parts = listOf<UIMessagePart>(UIMessagePart.Text("just text"))

        val result = DocumentAsPromptTransformer.buildPartsWithDocumentPrompts(parts) { success("unused") }

        assertEquals(parts, result)
    }

    @Test
    fun `parse failure is not embedded as document content`() {
        // Issue #102 core regression: the old path wrapped the reader's error string in <content>.
        val parts = listOf(
            document("broken.pdf"),
            UIMessagePart.Text("user question"),
        )

        val result = DocumentAsPromptTransformer.buildPartsWithDocumentPrompts(parts) {
            DocumentExtractionResult.ParseFailed("internal parser stacktrace")
        }

        val prompt = result.filterIsInstance<UIMessagePart.Text>().first().text
        assertFalse("must not embed failure in <content>", prompt.contains("<content>"))
        assertFalse("must not leak parser reason", prompt.contains("internal parser stacktrace"))
        assertTrue("attachment is still acknowledged", prompt.contains("broken.pdf"))
    }
}
