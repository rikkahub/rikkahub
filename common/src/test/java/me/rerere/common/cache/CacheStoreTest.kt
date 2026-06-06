package me.rerere.common.cache

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keySer = String.serializer()
    private val valSer = String.serializer()

    // In-memory fake store that records remove() calls, so TTL-eviction side effects
    // can be asserted deterministically without touching the filesystem or sleeping.
    private class FakeStore<K : Any, V : Any> : CacheStore<K, V> {
        val map = LinkedHashMap<K, CacheEntry<V>>()
        val removed = mutableListOf<K>()

        override fun loadEntry(key: K): CacheEntry<V>? = map[key]
        override fun saveEntry(key: K, entry: CacheEntry<V>) {
            map[key] = entry
        }

        override fun remove(key: K) {
            removed += key
            map.remove(key)
        }

        override fun clear() {
            map.clear()
        }

        override fun loadAllEntries(): Map<K, CacheEntry<V>> = LinkedHashMap(map)
        override fun keys(): Set<K> = map.keys.toSet()
    }

    // --- Base64JsonKeyCodec ---

    @Test
    fun `key codec round-trips arbitrary strings`() {
        val codec = Base64JsonKeyCodec(keySer)
        runBlocking {
            checkAll(Arb.string()) { key ->
                assertEquals(key, codec.fromFileName(codec.toFileName(key)))
            }
        }
    }

    @Test
    fun `key codec emits url-safe unpadded file names`() {
        val codec = Base64JsonKeyCodec(keySer)
        runBlocking {
            checkAll(Arb.string()) { key ->
                val name = codec.toFileName(key)
                // URL-safe alphabet uses '-' and '_' instead of '+' and '/', and we drop padding.
                assertFalse("file name must not contain '/': $name", name.contains('/'))
                assertFalse("file name must not contain '+': $name", name.contains('+'))
                assertFalse("file name must not contain '=': $name", name.contains('='))
            }
        }
    }

    @Test
    fun `key codec fails closed on non-base64 input`() {
        val codec = Base64JsonKeyCodec(keySer)
        // '*' is outside the base64url alphabet -> decode throws -> codec returns null, never throws.
        assertNull(codec.fromFileName("****"))
        assertNull(codec.fromFileName("not a file name!"))
    }

    @Test
    fun `key codec fails closed on valid base64 that is not json`() {
        val codec = Base64JsonKeyCodec(keySer)
        // "{{{" is valid base64url but not parseable JSON for a String key.
        val notJson = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{{{".toByteArray(Charsets.UTF_8))
        assertNull(codec.fromFileName(notJson))
    }

    // --- CacheEntry.isExpired boundary ---

    @Test
    fun `entry with null expiry never expires`() {
        val entry = CacheEntry(value = "v", expiresAt = null)
        runBlocking {
            checkAll(Arb.long()) { now ->
                assertFalse(entry.isExpired(now))
            }
        }
    }

    @Test
    fun `isExpired is inclusive at the boundary`() {
        val entry = CacheEntry(value = "v", expiresAt = 1000L)
        assertFalse("one millis before expiry is not expired", entry.isExpired(999L))
        assertTrue("exactly at expiry is expired", entry.isExpired(1000L))
        assertTrue("after expiry is expired", entry.isExpired(1001L))
    }

    // --- LruCache (over the in-memory fake store) ---

    @Test
    fun `lru cache never exceeds capacity in memory`() {
        runBlocking {
            checkAll(Arb.int(1, 8), Arb.int(0, 40)) { capacity, n ->
                val cache = LruCache(capacity = capacity, store = FakeStore<Int, String>())
                repeat(n) { i -> cache.put(i, "v$i") }
                assertTrue(cache.keysInMemory().size <= capacity)
                assertTrue(cache.size() <= capacity)
            }
        }
    }

    @Test
    fun `lru cache evicts least-recently-used on overflow`() {
        val store = FakeStore<String, String>()
        val cache = LruCache(capacity = 2, store = store)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.get("a")          // touch a -> b is now least recently used
        cache.put("c", "3")     // overflow -> evict b
        val keys = cache.keysInMemory()
        assertTrue("a should be retained", keys.contains("a"))
        assertTrue("c should be retained", keys.contains("c"))
        assertFalse("b should be evicted", keys.contains("b"))
    }

    @Test
    fun `larger capacity retains a superset for the same insertion sequence`() {
        runBlocking {
            checkAll(Arb.int(1, 6), Arb.int(0, 30)) { small, n ->
                val large = small + 2
                val seq = (0 until n).toList()

                val smallCache = LruCache(capacity = small, store = FakeStore<Int, String>())
                val largeCache = LruCache(capacity = large, store = FakeStore<Int, String>())
                for (i in seq) {
                    smallCache.put(i, "v$i")
                    largeCache.put(i, "v$i")
                }
                assertTrue(largeCache.keysInMemory().containsAll(smallCache.keysInMemory()))
            }
        }
    }

    @Test
    fun `ttl of zero expires immediately and removes from store`() {
        val store = FakeStore<String, String>()
        val cache = LruCache(capacity = 4, store = store)
        cache.put("k", "v", ttlMillis = 0L)
        assertNull("zero ttl must read back as expired", cache.get("k"))
        assertTrue("expired read must purge the backing store", store.removed.contains("k"))
    }

    @Test
    fun `huge ttl keeps the value live`() {
        val cache = LruCache(capacity = 4, store = FakeStore<String, String>())
        cache.put("k", "v", ttlMillis = Long.MAX_VALUE / 2)
        assertEquals("v", cache.get("k"))
    }

    // --- SingleFileCacheStore ---

    private fun singleStore(file: File) =
        SingleFileCacheStore(file = file, keySerializer = keySer, valueSerializer = valSer)

    @Test
    fun `single file store round-trips and is idempotent`() {
        val file = File(tmp.newFolder(), "cache.json")
        val store = singleStore(file)
        store.saveEntry("a", CacheEntry("1"))
        store.saveEntry("a", CacheEntry("1")) // idempotent overwrite, same value
        store.saveEntry("b", CacheEntry("2"))
        assertEquals("1", store.loadEntry("a")?.value)
        assertEquals("2", store.loadEntry("b")?.value)
        assertEquals(setOf("a", "b"), store.keys())
        assertEquals(2, store.loadAllEntries().size)
    }

    @Test
    fun `single file store fails closed on a corrupted file`() {
        val file = File(tmp.newFolder(), "cache.json")
        file.writeText("}{ this is not json")
        val store = singleStore(file)
        assertTrue(store.loadAllEntries().isEmpty())
        assertNull(store.loadEntry("a"))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun `single file store clear empties it`() {
        val file = File(tmp.newFolder(), "cache.json")
        val store = singleStore(file)
        store.saveEntry("a", CacheEntry("1"))
        store.clear()
        assertTrue(store.loadAllEntries().isEmpty())
    }

    // --- PerKeyFileCacheStore ---

    private fun perKeyStore(dir: File) =
        PerKeyFileCacheStore(dir = dir, keyCodec = Base64JsonKeyCodec(keySer), valueSerializer = valSer)

    @Test
    fun `per-key store round-trips and reconstructs keys`() {
        val dir = tmp.newFolder()
        val store = perKeyStore(dir)
        store.saveEntry("alpha", CacheEntry("1"))
        store.saveEntry("beta", CacheEntry("2"))
        assertEquals("1", store.loadEntry("alpha")?.value)
        assertEquals(setOf("alpha", "beta"), store.keys())
        assertEquals(setOf("alpha", "beta"), store.loadAllEntries().keys)
    }

    @Test
    fun `per-key store overwrite keeps a single file and last value wins`() {
        val dir = tmp.newFolder()
        val store = perKeyStore(dir)
        store.saveEntry("k", CacheEntry("first"))
        store.saveEntry("k", CacheEntry("second"))
        assertEquals("second", store.loadEntry("k")?.value)
        val jsonFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0
        assertEquals("overwrite must not multiply files", 1, jsonFiles)
    }

    @Test
    fun `per-key store fails closed on corrupt and non-json files`() {
        val dir = tmp.newFolder()
        val store = perKeyStore(dir)
        store.saveEntry("good", CacheEntry("ok"))
        // A .json file with an undecodable name and body.
        File(dir, "not-a-valid-key.json").writeText("garbage")
        // A non-.json file that must be ignored entirely.
        File(dir, "stray.txt").writeText("ignore me")
        val all = store.loadAllEntries()
        assertEquals(setOf("good"), all.keys)
        assertEquals("ok", all["good"]?.value)
    }

    @Test
    fun `per-key store clear removes only json files`() {
        val dir = tmp.newFolder()
        val store = perKeyStore(dir)
        store.saveEntry("k", CacheEntry("v"))
        File(dir, "keep.txt").writeText("stay")
        store.clear()
        assertTrue(store.loadAllEntries().isEmpty())
        assertTrue("non-json file must survive clear()", File(dir, "keep.txt").exists())
    }
}
