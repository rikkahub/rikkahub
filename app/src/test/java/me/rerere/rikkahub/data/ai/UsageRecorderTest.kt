package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.DayCount
import me.rerere.rikkahub.data.db.dao.ModelUsage
import me.rerere.rikkahub.data.db.dao.SourceUsage
import me.rerere.rikkahub.data.db.dao.UsageBucket
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.dao.UsageTotals
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class UsageRecorderTest {
    private val dao = FakeUsageRecordDAO()
    private val setting = ProviderSetting.OpenAI()
    private val model = Model(modelId = "gpt-test", displayName = "Test")
    private val params = TextGenerationParams(model = model)
    private val tag = UsageTag(UsageSource.CHAT, assistantId = Uuid.random(), conversationId = Uuid.random())

    private fun TestScope.recorder() = UsageRecorder(dao, this)

    @Test
    fun `generateText records the usage with its tag`() = runTest {
        val provider = FakeProvider(result = TokenUsage(promptTokens = 10, completionTokens = 2, cachedTokens = 4))

        recorder().wrap(provider, tag).generateText(setting, emptyList(), params)
        testScheduler.advanceUntilIdle()

        val record = dao.records.single()
        assertEquals(UsageSource.CHAT.name, record.source)
        assertEquals(setting.id.toString(), record.providerId)
        assertEquals(model.id.toString(), record.modelId)
        assertEquals("gpt-test", record.modelName)
        assertEquals(tag.assistantId.toString(), record.assistantId)
        assertEquals(tag.conversationId.toString(), record.conversationId)
        assertEquals(listOf(10, 2, 4), listOf(record.promptTokens, record.completionTokens, record.cachedTokens))
    }

    @Test
    fun `stream records the merged usage once`() = runTest {
        val provider = FakeProvider(
            chunks = flow {
                emit(StreamChunk.Usage(TokenUsage(promptTokens = 10)))
                emit(StreamChunk.TextDelta("text", "hi"))
                emit(StreamChunk.Usage(TokenUsage(completionTokens = 5, cachedTokens = 3)))
            }
        )

        val chunks = recorder().wrap(provider, tag).streamText(setting, emptyList(), params).toList()
        testScheduler.advanceUntilIdle()

        assertEquals(3, chunks.size)
        val record = dao.records.single()
        assertEquals(listOf(10, 5, 3), listOf(record.promptTokens, record.completionTokens, record.cachedTokens))
    }

    @Test
    fun `stream cancelled midway still records usage received so far`() = runTest {
        val provider = FakeProvider(
            chunks = flow {
                emit(StreamChunk.Usage(TokenUsage(promptTokens = 10)))
                emit(StreamChunk.TextDelta("text", "hi"))
                awaitCancellation()
            }
        )

        recorder().wrap(provider, tag).streamText(setting, emptyList(), params).take(2).toList()
        testScheduler.advanceUntilIdle()

        assertEquals(10, dao.records.single().promptTokens)
    }

    @Test
    fun `requests without usage are not recorded`() = runTest {
        val recorder = recorder()
        recorder.wrap(FakeProvider(result = null), tag).generateText(setting, emptyList(), params)
        recorder.wrap(FakeProvider(chunks = flow { emit(StreamChunk.TextDelta("text", "hi")) }), tag)
            .streamText(setting, emptyList(), params).toList()
        testScheduler.advanceUntilIdle()

        assertTrue(dao.records.isEmpty())
    }
}

private class FakeUsageRecordDAO : UsageRecordDAO {
    val records = mutableListOf<UsageRecordEntity>()

    override suspend fun insert(record: UsageRecordEntity) {
        records += record
    }

    override suspend fun getTotals(since: Long) = UsageTotals()

    override suspend fun getTotalsByBucket(format: String, since: Long) = emptyList<UsageBucket>()

    override suspend fun getTotalsByModel(since: Long) = emptyList<ModelUsage>()

    override suspend fun getRequestCountPerDay(source: String, since: Long) = emptyList<DayCount>()

    override suspend fun getTotalsBySource(since: Long) = emptyList<SourceUsage>()
}

private class FakeProvider(
    private val result: TokenUsage? = null,
    private val chunks: Flow<StreamChunk> = flow {},
) : Provider<ProviderSetting.OpenAI> {
    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ) = TextGenerationResult(
        id = "result",
        model = params.model.modelId,
        message = UIMessage.assistant(""),
        usage = result
    )

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = chunks
}
