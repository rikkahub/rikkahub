package me.rerere.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTrackingProviderTest {
    private val setting = ProviderSetting.OpenAI()
    private val params = TextGenerationParams(model = Model())

    @Test
    fun `non-streaming request is recorded once`() = runBlocking {
        val recorded = mutableListOf<TokenUsage>()
        val usage = TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120)
        val provider = UsageTrackingProvider(
            delegate = FakeProvider(nonStreamingChunk = chunk(usage)),
            recorder = TokenUsageRecorder { recorded += it },
        )

        provider.generateText(setting, emptyList(), params)

        assertEquals(listOf(usage), recorded)
    }

    @Test
    fun `streaming snapshots are merged and recorded once`() = runBlocking {
        val recorded = mutableListOf<TokenUsage>()
        val provider = UsageTrackingProvider(
            delegate = FakeProvider(
                streamingChunks = flowOf(
                    chunk(TokenUsage(promptTokens = 100, cachedTokens = 30)),
                    chunk(TokenUsage(promptTokens = 100, completionTokens = 20)),
                )
            ),
            recorder = TokenUsageRecorder { recorded += it },
        )

        provider.streamText(setting, emptyList(), params).toList()

        assertEquals(
            listOf(
                TokenUsage(
                    promptTokens = 100,
                    completionTokens = 20,
                    cachedTokens = 30,
                    totalTokens = 120,
                )
            ),
            recorded,
        )
    }

    @Test
    fun `empty usage and cancelled streams are not recorded`() = runBlocking {
        val recorded = mutableListOf<TokenUsage>()
        val emptyProvider = UsageTrackingProvider(
            delegate = FakeProvider(nonStreamingChunk = chunk(TokenUsage())),
            recorder = TokenUsageRecorder { recorded += it },
        )
        emptyProvider.generateText(setting, emptyList(), params)

        val cancelledProvider = UsageTrackingProvider(
            delegate = FakeProvider(
                streamingChunks = flow {
                    emit(chunk(TokenUsage(promptTokens = 100)))
                    throw CancellationException()
                }
            ),
            recorder = TokenUsageRecorder { recorded += it },
        )
        try {
            cancelledProvider.streamText(setting, emptyList(), params).toList()
        } catch (_: CancellationException) {
            // Expected.
        }

        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `failed request is not recorded`() = runBlocking {
        val recorded = mutableListOf<TokenUsage>()
        val provider = UsageTrackingProvider(
            delegate = FakeProvider(generationError = IllegalStateException("provider failed")),
            recorder = TokenUsageRecorder { recorded += it },
        )

        try {
            provider.generateText(setting, emptyList(), params)
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `recorder failure does not fail generation`() = runBlocking {
        val expected = chunk(TokenUsage(promptTokens = 100))
        val provider = UsageTrackingProvider(
            delegate = FakeProvider(nonStreamingChunk = expected),
            recorder = TokenUsageRecorder { error("database unavailable") },
        )

        val actual = provider.generateText(setting, emptyList(), params)

        assertEquals(expected, actual)
    }

    private fun chunk(usage: TokenUsage?) = MessageChunk(
        id = "test",
        model = "test",
        choices = emptyList(),
        usage = usage,
    )

    private class FakeProvider(
        private val nonStreamingChunk: MessageChunk = MessageChunk(
            id = "test",
            model = "test",
            choices = emptyList(),
        ),
        private val streamingChunks: Flow<MessageChunk> = flowOf(),
        private val generationError: Exception? = null,
    ) : Provider<ProviderSetting.OpenAI> {
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            generationError?.let { throw it }
            return nonStreamingChunk
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = streamingChunks
    }
}
