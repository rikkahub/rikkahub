package me.rerere.rikkahub.data.files

import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.s3.S3ListResult
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

private const val TAG = "RemoteFileStore"

/**
 * 把本地文件传到 S3 换成临时公网地址，供只接受 URL 的接口使用（媒体生成的参考素材、聊天附件等）。
 *
 * 远程对象只是缓存，本地文件始终是源：key 由文件内容的 SHA-256 决定，相同内容不会重复上传；
 * 调用方不应持久化返回的地址，需要时重新调用 [upload] 即可。超过 [RETENTION] 的对象会被清扫删除。
 */
class RemoteFileStore internal constructor(
    private val scope: CoroutineScope,
    private val config: () -> S3Config,
    private val storage: (S3Config) -> RemoteObjectStorage,
    private val now: () -> Instant = Instant::now,
) {
    constructor(settingsStore: SettingsStore, httpClient: HttpClient, scope: CoroutineScope) : this(
        scope = scope,
        // 签出的地址要交给只接受 HTTPS 素材的接口，没写协议时不能落到 http
        config = { settingsStore.settingsFlow.value.uploadS3Config.withHttpsByDefault() },
        storage = { S3ObjectStorage(S3Client(it, httpClient)) },
    )

    private val mutex = Mutex()
    private val hashes = ConcurrentHashMap<String, FileHash>()

    // 以下状态只在持有 mutex 时读写
    private var knownConfig: S3Config? = null
    private val knownObjects = HashMap<String, KnownObject>()
    private var lastSweepAt: Instant? = null

    val isConfigured: Boolean
        get() = config().isUsable()

    /**
     * 确保 [file] 在桶里，并返回 [expires] 内有效的下载地址。内容已上传过时不会重传，只重新签发地址。
     */
    suspend fun upload(
        file: File,
        contentType: String = guessContentType(file),
        expires: Duration = DEFAULT_URL_EXPIRES,
    ): Result<RemoteFile> = runCatching {
        require(expires.isPositive() && expires <= RETENTION) { "expires must be between 0 and $RETENTION" }
        val config = config()
        check(config.isUsable()) { "S3 is not configured" }

        val key = objectKey(file)
        val storage = storage(config)
        val sweepDue = mutex.withLock {
            if (config != knownConfig) {
                knownObjects.clear()
                knownConfig = config
            }
            if (!isReusable(storage, key, expires)) {
                storage.put(key, file, contentType)
                knownObjects[key] = KnownObject(lastModified = now(), checkedAt = now())
            }
            val due = lastSweepAt?.let { now() >= it + SWEEP_INTERVAL.toJavaDuration() } ?: true
            if (due) lastSweepAt = now()
            due
        }
        if (sweepDue) {
            scope.launch {
                runCatching { sweep() }.onFailure { Log.w(TAG, "sweep failed", it) }
            }
        }

        RemoteFile(
            key = key,
            url = storage.presign(key, expires),
            expiresAt = now() + expires.toJavaDuration(),
        )
    }.onFailure { if (it is CancellationException) throw it }

    /**
     * 用当前配置访问一次桶，配置缺失或请求失败时返回失败。
     */
    suspend fun testConnection(): Result<Unit> = runCatching {
        val config = config()
        check(config.isUsable()) { "S3 is not configured" }
        storage(config).list(KEY_PREFIX, null)
        Unit
    }.onFailure { if (it is CancellationException) throw it }

    /**
     * 删除超过 [RETENTION] 的对象。
     */
    internal suspend fun sweep() {
        val config = config()
        if (!config.isUsable()) return
        val storage = storage(config)

        var token: String? = null
        do {
            // 逐页加锁：清扫不会和上传交错，上传也不用等整轮清扫结束
            token = mutex.withLock {
                val page = storage.list(KEY_PREFIX, token)
                val deadline = now() - RETENTION.toJavaDuration()
                page.objects
                    .filter { it.lastModified?.isBefore(deadline) == true }
                    .forEach {
                        storage.delete(it.key)
                        knownObjects.remove(it.key)
                    }
                page.nextContinuationToken.takeIf { page.isTruncated }
            }
        } while (token != null)
    }

    // 对象已在桶里，并且在地址过期前不会被清扫掉
    private suspend fun isReusable(storage: RemoteObjectStorage, key: String, expires: Duration): Boolean {
        val now = now()
        val known = knownObjects[key]?.takeIf { now < it.checkedAt + RECHECK_INTERVAL.toJavaDuration() }
        val lastModified = known?.lastModified ?: storage.lastModified(key)
        if (lastModified == null) {
            knownObjects.remove(key)
            return false
        }
        if (known == null) knownObjects[key] = KnownObject(lastModified = lastModified, checkedAt = now)
        return now + expires.toJavaDuration() <= lastModified + RETENTION.toJavaDuration()
    }

    private suspend fun objectKey(file: File): String = withContext(Dispatchers.IO) {
        require(file.isFile) { "File does not exist: ${file.path}" }
        val length = file.length()
        val lastModified = file.lastModified()
        val cached = hashes[file.path]?.takeIf { it.length == length && it.lastModified == lastModified }
        val sha256 = cached?.sha256
            ?: file.sha256Hex().also { hashes[file.path] = FileHash(length, lastModified, it) }
        val extension = file.extension.lowercase().takeIf { ext -> ext.isNotEmpty() && ext.all { it.isLetterOrDigit() } }
        KEY_PREFIX + sha256 + extension?.let { ".$it" }.orEmpty()
    }

    private data class FileHash(val length: Long, val lastModified: Long, val sha256: String)

    private data class KnownObject(val lastModified: Instant, val checkedAt: Instant)

    companion object {
        const val KEY_PREFIX = "rikkahub_uploads/"

        /** 对象的保留时长，同时也是 SigV4 预签名地址的最长有效期。 */
        val RETENTION = 7.days
        val DEFAULT_URL_EXPIRES = 24.hours

        // 超过这个时间就重新向桶确认对象还在，避免对象被桶的生命周期规则或手动删除后仍然签出地址
        private val RECHECK_INTERVAL = 1.hours
        private val SWEEP_INTERVAL = 24.hours
    }
}

data class RemoteFile(
    val key: String,
    val url: String,
    val expiresAt: Instant,
)

/**
 * [RemoteFileStore] 用到的对象存储操作。
 */
internal interface RemoteObjectStorage {
    /** 对象不存在，或拿不到修改时间时返回 null。 */
    suspend fun lastModified(key: String): Instant?

    suspend fun put(key: String, file: File, contentType: String)

    suspend fun list(prefix: String, continuationToken: String?): S3ListResult

    suspend fun delete(key: String)

    fun presign(key: String, expires: Duration): String
}

private class S3ObjectStorage(private val client: S3Client) : RemoteObjectStorage {
    override suspend fun lastModified(key: String): Instant? =
        client.headObject(key).getOrNull()?.lastModified?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        }

    override suspend fun put(key: String, file: File, contentType: String) =
        client.putObject(key, file, contentType).getOrThrow()

    override suspend fun list(prefix: String, continuationToken: String?): S3ListResult =
        client.listObjects(prefix = prefix, continuationToken = continuationToken).getOrThrow()

    override suspend fun delete(key: String) = client.deleteObject(key).getOrThrow()

    override fun presign(key: String, expires: Duration): String = client.presignGetUrl(key, expires)
}

private fun S3Config.isUsable(): Boolean =
    endpoint.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank() && bucket.isNotBlank()

private fun guessContentType(file: File): String =
    URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
