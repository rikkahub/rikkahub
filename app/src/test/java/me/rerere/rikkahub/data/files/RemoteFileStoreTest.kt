package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.s3.S3ListResult
import me.rerere.rikkahub.data.sync.s3.S3Object
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class RemoteFileStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = Instant.parse("2026-10-01T00:00:00Z")
    private var config = S3Config(
        endpoint = "https://s3.example.com",
        accessKeyId = "id",
        secretAccessKey = "secret",
        bucket = "bucket",
    )
    private val storage = FakeStorage()
    private val store = RemoteFileStore(
        scope = CoroutineScope(Dispatchers.Unconfined),
        config = { config },
        storage = { storage },
        now = { now },
    )

    // "hello" 的 SHA-256
    private val helloKey = "rikkahub_uploads/2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824.png"

    private fun file(name: String, content: String = "hello"): File =
        folder.newFile(name).apply { writeText(content) }

    private fun advance(duration: Duration) {
        now += duration.toJavaDuration()
    }

    @Test
    fun `upload stores the file under its content hash and returns a presigned url`() = runBlocking {
        val remote = store.upload(file("Photo.PNG"), expires = 2.hours).getOrThrow()

        assertEquals(helloKey, remote.key)
        assertEquals("signed:$helloKey:2h", remote.url)
        assertEquals(now + 2.hours.toJavaDuration(), remote.expiresAt)
        assertEquals(listOf(helloKey to "image/png"), storage.puts)
    }

    @Test
    fun `same content is uploaded once and reused without asking the bucket again`() = runBlocking {
        store.upload(file("a.png")).getOrThrow()
        val remote = store.upload(file("b.png")).getOrThrow()

        assertEquals(helloKey, remote.key)
        assertEquals(1, storage.puts.size)
        assertEquals(1, storage.heads)
    }

    @Test
    fun `object already in the bucket is not uploaded again`() = runBlocking {
        storage.objects[helloKey] = now - 1.days.toJavaDuration()

        store.upload(file("a.png")).getOrThrow()

        assertTrue(storage.puts.isEmpty())
    }

    @Test
    fun `object that would be swept before the url expires is uploaded again`() = runBlocking {
        storage.objects[helloKey] = now - 6.days.toJavaDuration() - 1.minutes.toJavaDuration()

        store.upload(file("a.png"), expires = 24.hours).getOrThrow()

        assertEquals(1, storage.puts.size)
        assertEquals(now, storage.objects[helloKey])
    }

    @Test
    fun `object deleted from the bucket is uploaded again after the recheck interval`() = runBlocking {
        val file = file("a.png")
        store.upload(file).getOrThrow()
        storage.objects.clear()

        advance(30.minutes)
        store.upload(file).getOrThrow()
        assertEquals(1, storage.puts.size)

        advance(31.minutes)
        store.upload(file).getOrThrow()
        assertEquals(2, storage.puts.size)
    }

    @Test
    fun `changed file content gets a new key`() = runBlocking {
        val file = file("a.png")
        val first = store.upload(file).getOrThrow()
        file.writeText("hello world")
        val second = store.upload(file).getOrThrow()

        assertNotEquals(first.key, second.key)
        assertEquals(2, storage.puts.size)
    }

    @Test
    fun `switching the s3 config forgets what was uploaded`() = runBlocking {
        val file = file("a.png")
        store.upload(file).getOrThrow()
        storage.objects.clear()
        config = config.copy(bucket = "other")

        store.upload(file).getOrThrow()

        assertEquals(2, storage.puts.size)
    }

    @Test
    fun `upload fails without touching the bucket when s3 is not configured`() = runBlocking {
        config = S3Config()

        assertFalse(store.isConfigured)
        assertTrue(store.upload(file("a.png")).exceptionOrNull() is IllegalStateException)
        assertTrue(store.upload(File(folder.root, "missing.png")).isFailure)
        assertEquals(0, storage.heads)
        assertTrue(storage.puts.isEmpty())
    }

    @Test
    fun `upload rejects an expiry longer than the retention`() = runBlocking {
        assertTrue(store.upload(file("a.png"), expires = 8.days).exceptionOrNull() is IllegalArgumentException)
        assertTrue(storage.puts.isEmpty())
    }

    @Test
    fun `testConnection reports missing config and bucket errors`() = runBlocking {
        assertTrue(store.testConnection().isSuccess)

        storage.listError = IllegalStateException("403")
        assertEquals("403", store.testConnection().exceptionOrNull()?.message)

        config = S3Config()
        assertTrue(store.testConnection().exceptionOrNull()?.message.orEmpty().contains("not configured"))
    }

    @Test
    fun `sweep deletes only objects past the retention across all pages`() = runBlocking {
        storage.pageSize = 2
        val expired = (1..3).map { "rikkahub_uploads/old$it.png" }
        val fresh = (1..2).map { "rikkahub_uploads/new$it.png" }
        expired.forEach { storage.objects[it] = now - 8.days.toJavaDuration() }
        fresh.forEach { storage.objects[it] = now - 6.days.toJavaDuration() }
        storage.objects["rikkahub_backups/backup_1.zip"] = now - 30.days.toJavaDuration()

        store.sweep()

        assertEquals(expired.toSet(), storage.deleted.toSet())
        assertEquals((fresh + "rikkahub_backups/backup_1.zip").toSet(), storage.objects.keys)
    }

    @Test
    fun `upload sweeps at most once a day`() = runBlocking {
        val file = file("a.png")
        storage.objects["rikkahub_uploads/old.png"] = now - 8.days.toJavaDuration()

        store.upload(file).getOrThrow()
        assertEquals(listOf("rikkahub_uploads/old.png"), storage.deleted)
        assertEquals(1, storage.lists)

        advance(23.hours)
        store.upload(file).getOrThrow()
        assertEquals(1, storage.lists)

        advance(2.hours)
        store.upload(file).getOrThrow()
        assertEquals(2, storage.lists)
    }

    private inner class FakeStorage : RemoteObjectStorage {
        val objects = sortedMapOf<String, Instant>()
        val puts = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        var heads = 0
        var lists = 0
        var pageSize = 1000
        var listError: Exception? = null

        override suspend fun lastModified(key: String): Instant? {
            heads++
            return objects[key]
        }

        override suspend fun put(key: String, file: File, contentType: String) {
            puts += key to contentType
            objects[key] = now
        }

        override suspend fun list(prefix: String, continuationToken: String?): S3ListResult {
            listError?.let { throw it }
            if (continuationToken == null) lists++
            val remaining = objects.entries
                .filter { it.key.startsWith(prefix) && (continuationToken == null || it.key > continuationToken) }
            val page = remaining.take(pageSize)
            return S3ListResult(
                objects = page.map { S3Object(it.key, size = 0, etag = null, lastModified = it.value, storageClass = null) },
                commonPrefixes = emptyList(),
                isTruncated = remaining.size > page.size,
                nextContinuationToken = page.lastOrNull()?.key,
                keyCount = page.size,
            )
        }

        override suspend fun delete(key: String) {
            deleted += key
            objects.remove(key)
        }

        override fun presign(key: String, expires: Duration): String = "signed:$key:$expires"
    }
}
