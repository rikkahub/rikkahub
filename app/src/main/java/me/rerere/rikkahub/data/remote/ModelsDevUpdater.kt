package me.rerere.rikkahub.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelsDevCatalog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "ModelsDevUpdater"
private const val API_URL = "https://models.dev/api.json"
private const val ASSET_NAME = "models_dev.json"
private val REFRESH_INTERVAL = 1.days

// 至少要有这么多条才认为下载的数据可用，防止接口异常返回的空数据覆盖本地缓存
private const val MIN_CATALOG_SIZE = 100

/**
 * 给 [ModelRegistry] 提供 models.dev 数据：先装上本地缓存（没有就用 assets 里的快照），
 * 再按 [REFRESH_INTERVAL] 从 models.dev 拉取新数据，精简后存到 filesDir。
 */
class ModelsDevUpdater(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val dir = File(context.filesDir, "models_dev")
    private val dataFile = File(dir, "api.json")

    // 内容是上次响应的 ETag，修改时间是上次检查更新的时间
    private val etagFile = File(dir, "etag")

    suspend fun start() = withContext(Dispatchers.IO) {
        val hasCache = loadLocal()
        if (System.currentTimeMillis() - etagFile.lastModified() >= REFRESH_INTERVAL.inWholeMilliseconds) {
            runCatching { refresh(useEtag = hasCache) }
                .onFailure { Log.w(TAG, "refresh failed", it) }
        }
    }

    /** 返回是否用上了下载的缓存 */
    private fun loadLocal(): Boolean {
        if (dataFile.exists()) {
            val cached = runCatching { dataFile.inputStream().use { ModelsDevCatalog.decode(it) } }
                .onFailure { Log.w(TAG, "cached catalog is broken", it) }
                .getOrNull()
            if (cached != null) {
                ModelRegistry.installCatalog(cached)
                return true
            }
            dataFile.delete()
        }
        runCatching { context.assets.open(ASSET_NAME).use { ModelsDevCatalog.decode(it) } }
            .onSuccess { ModelRegistry.installCatalog(it) }
            .onFailure { Log.e(TAG, "failed to load bundled catalog", it) }
        return false
    }

    private fun refresh(useEtag: Boolean) {
        val etag = if (useEtag && etagFile.exists()) etagFile.readText().ifBlank { null } else null
        val request = Request.Builder()
            .url(API_URL)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304) {
                markChecked(etag)
                return
            }
            check(response.isSuccessful) { "models.dev responded ${response.code}" }
            val catalog = response.body.byteStream().use { ModelsDevCatalog.decode(it) }
            check(catalog.size >= MIN_CATALOG_SIZE) { "catalog too small: ${catalog.size}" }

            dir.mkdirs()
            val tmp = File(dir, "api.json.tmp")
            tmp.outputStream().use { catalog.encodeTo(it) }
            check(tmp.renameTo(dataFile)) { "failed to replace $dataFile" }
            ModelRegistry.installCatalog(catalog)
            markChecked(response.header("ETag"))
            Log.i(TAG, "catalog updated: ${catalog.size} entries")
        }
    }

    private fun markChecked(etag: String?) {
        dir.mkdirs()
        etagFile.writeText(etag.orEmpty())
    }
}
