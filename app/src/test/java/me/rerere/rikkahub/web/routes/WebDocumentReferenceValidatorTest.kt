package me.rerere.rikkahub.web.routes

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDocumentReferenceValidatorTest {
    @Test
    fun `managed upload document is allowed`() = runBlocking {
        val document = UIMessagePart.Document(
            url = "file:///data/user/0/me.rerere.rikkahub/files/upload/uploaded.pdf",
            fileName = "uploaded.pdf",
            mime = "application/pdf",
        )

        val error = validateWebDocumentReferences(listOf(document)) { true }

        assertNull(error)
    }

    @Test
    fun `arbitrary file document is rejected`() = runBlocking {
        val document = UIMessagePart.Document(
            url = "file:///data/user/0/me.rerere.rikkahub/files/databases/rikkahub.db",
            fileName = "rikkahub.db",
        )

        val error = validateWebDocumentReferences(listOf(document)) { false }

        assertEquals("Document 'rikkahub.db' must reference a managed uploaded file", error)
    }

    @Test
    fun `content document is rejected because the Web API cannot read it`() = runBlocking {
        val document = UIMessagePart.Document(
            url = "content://com.android.providers.media.documents/document/42",
            fileName = "shared.pdf",
            mime = "application/pdf",
        )

        val error = validateWebDocumentReferences(listOf(document)) { error("must not be called") }

        assertEquals(
            "Document 'shared.pdf' uses an unsupported content URI; upload the file through the Web API first",
            error
        )
    }
}
