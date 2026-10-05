package me.rerere.rikkahub.data.repository

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.mediagen.model.MediaKind
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MediaCreationDAO
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MediaCreationRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MediaCreationDAO
    private lateinit var repository: MediaCreationRepository
    private lateinit var session: MediaCreationSession
    private var clock = 0L

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .build()
        dao = database.mediaCreationDao()
        repository = MediaCreationRepository(context, database, dao, Json)
        session = repository.getOrCreateEmptySession(MediaCreationDraft())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aNewVersionStaysInPlaceAndBecomesTheShownOne() = runBlocking {
        val first = insert("a1")
        insert("b1")
        insert("a2", nodeId = first.nodeId)

        assertEquals(listOf("b1 1/1", "a2 2/2"), timeline())
        assertEquals(1, repository.getNodePosition(first.nodeId))
        assertEquals(2, repository.observeSessions().first().single().nodeCount)
    }

    @Test
    fun switchingStopsAtBothEnds() = runBlocking {
        // 创建时间相同的版本按写入顺序排
        val first = insert("a1", at = 1)
        insert("a2", nodeId = first.nodeId, at = 1)
        insert("a3", nodeId = first.nodeId, at = 1)
        assertEquals(listOf("a3 3/3"), timeline())

        repository.switchVersion(first.nodeId, 1)
        assertEquals(listOf("a3 3/3"), timeline())

        repository.switchVersion(first.nodeId, -1)
        assertEquals(listOf("a2 2/3"), timeline())

        repository.switchVersion(first.nodeId, -1)
        repository.switchVersion(first.nodeId, -1)
        assertEquals(listOf("a1 1/3"), timeline())

        repository.switchVersion(first.nodeId, 1)
        assertEquals(listOf("a2 2/3"), timeline())
    }

    @Test
    fun deletingTheShownVersionFallsBackToANeighbour() = runBlocking {
        val first = insert("a1")
        val second = insert("a2", nodeId = first.nodeId)
        val third = insert("a3", nodeId = first.nodeId)

        repository.deleteRecord(third)
        assertEquals(listOf("a2 2/2"), timeline())

        repository.switchVersion(first.nodeId, -1)
        repository.deleteRecord(first)
        assertEquals(listOf("a2 1/1"), timeline())

        repository.deleteRecord(second)
        assertEquals(emptyList<String>(), timeline())
        assertFalse(repository.hasNode(first.nodeId))
    }

    @Test
    fun deletingAHiddenVersionKeepsTheShownOne() = runBlocking {
        val first = insert("a1")
        insert("a2", nodeId = first.nodeId)
        insert("a3", nodeId = first.nodeId)

        repository.deleteRecord(first)

        assertEquals(listOf("a3 2/2"), timeline())
        assertTrue(repository.hasNode(first.nodeId))
    }

    @Test
    fun aVersionOfAVanishedItemStartsANewItem() = runBlocking {
        val first = insert("a1")
        insert("b1")
        repository.deleteRecord(first)

        insert("a2", nodeId = first.nodeId)

        assertEquals(listOf("a2 1/1", "b1 1/1"), timeline())
    }

    @Test
    fun deletingASessionRemovesItsItemsAndVersions() = runBlocking {
        val first = insert("a1")
        val second = insert("a2", nodeId = first.nodeId)

        repository.deleteSession(session.id)

        assertFalse(repository.hasNode(first.nodeId))
        assertNull(repository.getRecord(first.id))
        assertNull(repository.getRecord(second.id))
    }

    private suspend fun insert(
        prompt: String,
        nodeId: Uuid = Uuid.random(),
        at: Long = ++clock,
    ): MediaCreationRecord {
        val record = MediaCreationRecord(
            sessionId = session.id,
            nodeId = nodeId,
            providerId = Uuid.random(),
            providerName = "provider",
            modelId = "model",
            kind = MediaKind.IMAGE,
            prompt = prompt,
            createAt = Instant.ofEpochMilli(at),
        )
        repository.insertRecord(record)
        return record
    }

    // 时间线上每一项显示的提示词和「第几个 / 共几个」版本，顺序和界面一致（最新的一项在前）
    private suspend fun timeline(): List<String> {
        val result = dao.nodePagingSource(session.id.toString())
            .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = true))
        return (result as PagingSource.LoadResult.Page).data.map {
            "${it.record.prompt} ${it.versionIndex + 1}/${it.versionCount}"
        }
    }
}
