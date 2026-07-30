package me.rerere.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenUsageDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: TokenUsageDAO

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = database.tokenUsageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addUsage_accumulatesAtomically() = runBlocking {
        List(20) {
            async {
                dao.addUsage(
                    promptTokens = 10,
                    completionTokens = 2,
                    cachedTokens = 3,
                )
            }
        }.awaitAll()

        val usage = dao.getUsage()
        assertEquals(200L, usage.promptTokens)
        assertEquals(40L, usage.completionTokens)
        assertEquals(60L, usage.cachedTokens)
    }
}
