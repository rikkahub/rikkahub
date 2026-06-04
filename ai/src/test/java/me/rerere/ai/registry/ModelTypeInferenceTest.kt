package me.rerere.ai.registry

import me.rerere.ai.provider.ModelType
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTypeInferenceTest {
    @Test
    fun embeddingIdsAreEmbedding() {
        assertEquals(ModelType.EMBEDDING, guessModelType("text-embedding-3-large"))
        assertEquals(ModelType.EMBEDDING, guessModelType("text-embedding-3-small"))
        assertEquals(ModelType.EMBEDDING, guessModelType("text-embedding-ada-002"))
        assertEquals(ModelType.EMBEDDING, guessModelType("text-embedding-004"))
        assertEquals(ModelType.EMBEDDING, guessModelType("bge-large-en-v1.5-embed"))
    }

    @Test
    fun chatIdsAreChat() {
        assertEquals(ModelType.CHAT, guessModelType("gpt-4o"))
        assertEquals(ModelType.CHAT, guessModelType("gpt-5.5"))
        assertEquals(ModelType.CHAT, guessModelType("claude-opus-4-8"))
        assertEquals(ModelType.CHAT, guessModelType("o3-mini"))
        assertEquals(ModelType.CHAT, guessModelType("deepseek-chat"))
    }

    @Test
    fun classificationIsCaseInsensitive() {
        assertEquals(ModelType.EMBEDDING, guessModelType("Text-Embedding-3-Large"))
    }
}
