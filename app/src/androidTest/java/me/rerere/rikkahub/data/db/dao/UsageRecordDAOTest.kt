package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class UsageRecordDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: UsageRecordDAO

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.usageRecordDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun requestsAreCountedPerLocalDayForTheSourceSinceTheStart() = runBlocking {
        insert("2026-08-11T23:00:00", "CHAT")
        insert("2026-08-12T00:30:00", "CHAT")
        insert("2026-08-12T23:59:00", "CHAT")
        insert("2026-08-12T12:00:00", "TITLE")
        insert("2026-08-13T08:00:00", "CHAT")

        val counts = dao.getRequestCountPerDay("CHAT", epochMillis("2026-08-12T00:00:00"))

        assertEquals(mapOf("2026-08-12" to 2, "2026-08-13" to 1), counts.associate { it.day to it.count })
    }

    private suspend fun insert(localDateTime: String, source: String) {
        dao.insert(
            UsageRecordEntity(
                createdAt = epochMillis(localDateTime),
                source = source,
                providerId = null,
                modelId = null,
                modelName = null,
                assistantId = null,
                conversationId = null,
                promptTokens = 10,
                completionTokens = 1,
                cachedTokens = 0,
            )
        )
    }

    private fun epochMillis(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
