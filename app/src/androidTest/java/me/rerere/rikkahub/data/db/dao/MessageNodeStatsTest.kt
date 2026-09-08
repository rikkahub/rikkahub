package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNodeStatsTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MessageNodeDAO
    private var nodeIndex = 0

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.messageNodeDao()
        database.conversationDao().insert(
            ConversationEntity(
                id = "stats-test",
                assistantId = "assistant",
                title = "Stats test",
                nodes = "[]",
                createAt = 0,
                updateAt = 0,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun malformedRowsAreSkippedWhileValidMessagesAreAggregated() = runBlocking {
        insertMessages("""[{"role":"user"""")
        insertMessages(
            """[
                {"role":"user","createdAt":"2026-08-12T09:00:00"},
                {"role":"assistant","createdAt":"2026-08-12T09:01:00",
                 "usage":{"promptTokens":100,"completionTokens":40,"cachedTokens":20}},
                {"role":"user","createdAt":"2026-08-12T10:00:00"},
                {"role":"user","createdAt":"2026-08-13T09:00:00"},
                {"role":"user","createdAt":"2026-08-01T09:00:00"}
            ]""".trimIndent()
        )
        insertMessages("not json")
        insertMessages(
            """[{"role":"assistant","createdAt":"2026-08-13T09:01:00",
                 "usage":{"promptTokens":50,"completionTokens":10,"cachedTokens":5}}]"""
        )
        insertMessages("[]")

        assertEquals(MessageTokenStats(6, 150, 50, 25), dao.getTokenStats())
        assertEquals(
            mapOf("2026-08-12" to 2, "2026-08-13" to 1),
            dao.getMessageCountPerDay("2026-08-12").associate { it.day to it.count },
        )
        assertEquals(5, dao.getNodesOfConversation("stats-test").size)
    }

    @Test
    fun onlyMalformedOrEmptyRowsProduceEmptyStats() = runBlocking {
        insertMessages("")
        insertMessages("[")
        insertMessages("not json")
        insertMessages("[]")

        assertEquals(MessageTokenStats(), dao.getTokenStats())
        assertEquals(emptyList<MessageDayCount>(), dao.getMessageCountPerDay("2026-08-12"))
    }

    @Test
    fun emptyDatabaseProducesEmptyStats() = runBlocking {
        assertEquals(MessageTokenStats(), dao.getTokenStats())
        assertEquals(emptyList<MessageDayCount>(), dao.getMessageCountPerDay("2026-08-12"))
    }

    private suspend fun insertMessages(messages: String) {
        val index = nodeIndex++
        dao.insert(
            MessageNodeEntity(
                id = "node-$index",
                conversationId = "stats-test",
                nodeIndex = index,
                messages = messages,
                selectIndex = 0,
            )
        )
    }
}
