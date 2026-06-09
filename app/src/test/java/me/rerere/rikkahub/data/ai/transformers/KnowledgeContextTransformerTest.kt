package me.rerere.rikkahub.data.ai.transformers

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.knowledge.KnowledgeSource
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.document.DocumentExtractionResult
import me.rerere.rikkahub.data.document.DocumentType
import me.rerere.rikkahub.data.rag.KnowledgeBase
import me.rerere.rikkahub.data.rag.KnowledgeStoreFactory
import me.rerere.rikkahub.data.model.Assistant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.uuid.Uuid

/**
 * Migrated coverage for [KnowledgeContextTransformer], which replaced DocumentAsPromptTransformer and
 * KnowledgeRetrievalTransformer (issue #141 Phase 1). Carries forward their regressions against the
 * new pure seams so they stay JVM-pure (no Android URI / disk IO / network):
 *  - RAG relevance floor #22 (`buildRetrievalRequest`),
 *  - attachment ordering #20 + typed-result rendering #102 + scanned-PDF Empty #189
 *    (`buildAttachmentBlocks` over a reader lambda),
 *  - the zero-overhead no-op when there is no work to do (`transform` with throwing Koin fakes).
 */
class KnowledgeContextTransformerTest {

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    private fun ctx(assistant: Assistant) = TransformerContext(
        context = ContextWrapper(null),
        model = Model(),
        assistant = assistant,
        settings = Settings(),
    )

    private fun userMessage(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.USER,
        parts = parts.toList(),
    )

    private fun document(name: String) = UIMessagePart.Document(
        url = "file:///$name",
        fileName = name,
        mime = "text/plain",
    )

    private fun success(text: String) =
        DocumentExtractionResult.Success(text, DocumentType.PlainText, "text/plain")

    // ---- RAG relevance floor (issue #22) --------------------------------------------------------

    // On the unfixed code minScore was null and this assertion fails: retrieval returned the top-k
    // nearest chunks no matter how unrelated, polluting the context window with noise.
    @Test
    fun `retrieval request applies the relevance floor`() {
        val kb = KnowledgeBase(topK = 7)
        val request = KnowledgeContextTransformer.buildRetrievalRequest("how do cats purr", kb)

        assertEquals(KnowledgeBase.DEFAULT_MIN_SCORE, request.minScore)
        assertEquals(kb.topK, request.limit)
        assertEquals("how do cats purr", request.queryText)
    }

    // ---- Attachment ordering + typed rendering (issues #20, #102, #189) -------------------------

    @Test
    fun `multiple documents keep their original order`() {
        val parts = listOf(
            document("A.txt"),
            document("B.txt"),
            document("C.txt"),
            UIMessagePart.Text("user question"),
        )

        val blocks = KnowledgeContextTransformer.buildAttachmentBlocks(parts) { doc ->
            success("content of ${doc.fileName}")
        }

        // One block per document, in order (bug produced C B A by inserting each at index 0).
        assertEquals(listOf("A.txt", "B.txt", "C.txt"), blocks.map { it.title })
        assertTrue("A precedes B", blocks[0].content.indexOf("A.txt") >= 0)
        assertTrue(blocks.all { it.source == KnowledgeSource.ATTACHMENT })
    }

    @Test
    fun `documents without attachments produce no blocks`() {
        val parts = listOf<UIMessagePart>(UIMessagePart.Text("just text"))

        val blocks = KnowledgeContextTransformer.buildAttachmentBlocks(parts) { success("unused") }

        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `parse failure is not embedded as document content`() {
        // Issue #102 core regression: the old path wrapped the reader's error string in <content>.
        val parts = listOf(document("broken.pdf"))

        val blocks = KnowledgeContextTransformer.buildAttachmentBlocks(parts) {
            DocumentExtractionResult.ParseFailed("internal parser stacktrace")
        }

        val content = blocks.single().content
        assertFalse("must not embed failure in <content>", content.contains("<content>"))
        assertFalse("must not leak parser reason", content.contains("internal parser stacktrace"))
        assertTrue("attachment is still acknowledged", content.contains("broken.pdf"))
    }

    @Test
    fun `scanned pdf Empty is rendered as a no-extractable-text note never as content`() {
        // Issue #189: a scanned PDF resolves to Empty; it must surface as a metadata note, not content.
        val parts = listOf(document("scanned.pdf"))

        val blocks = KnowledgeContextTransformer.buildAttachmentBlocks(parts) {
            DocumentExtractionResult.Empty
        }

        val content = blocks.single().content
        assertFalse("Empty must not produce a <content> block", content.contains("<content>"))
        assertTrue("Empty surfaces the no-text note", content.contains("no extractable text"))
        assertTrue(content.contains("scanned.pdf"))
    }

    // ---- Zero-overhead no-op (the store/settings must not be touched) ----------------------------

    @Test
    fun `no knowledge base and no documents is a zero-overhead no-op`() = runBlocking {
        // Throwing fakes: if the transformer touches the store/settings, the test fails.
        startKoin {
            modules(
                module {
                    single<SettingsStore> { error("SettingsStore must not be accessed for a plain text turn") }
                    single { error("KnowledgeStoreFactory must not be accessed for a plain text turn") as KnowledgeStoreFactory }
                }
            )
        }

        val assistant = Assistant(knowledgeBaseId = null)
        val messages = listOf(userMessage(UIMessagePart.Text("hello")))

        val result = KnowledgeContextTransformer.transform(ctx(assistant), messages)

        // No candidates selected -> the original list reference is returned untouched.
        assertSame(messages, result)
    }

    @Test
    fun `no user message is a no-op even with KB attached`() = runBlocking {
        startKoin {
            modules(
                module {
                    single<SettingsStore> { error("must not be accessed") }
                    single { error("must not be accessed") as KnowledgeStoreFactory }
                }
            )
        }

        val assistant = Assistant(knowledgeBaseId = Uuid.random())
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi")))
        )

        val result = KnowledgeContextTransformer.transform(ctx(assistant), messages)
        assertSame(messages, result)
    }
}
