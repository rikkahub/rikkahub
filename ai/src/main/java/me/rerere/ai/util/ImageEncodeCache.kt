package me.rerere.ai.util

import android.util.Log
import java.io.File
import java.security.MessageDigest

private const val TAG = "ImageEncodeCache"

/**
 * 发送给模型前压缩好的图片（JPEG 字节）的磁盘缓存。
 *
 * 历史图片在每轮请求、每次工具调用后都会重新构建请求体，没有缓存时每次都要全尺寸解码再 JPEG 编码。
 * key 包含原图路径、修改时间、大小和压缩参数，原图变化后自然换 key；旧条目按最近使用时间淘汰。
 * 由 [me.rerere.ai.provider.ProviderManager] 初始化缓存目录；未初始化时（例如单元测试）不缓存。
 */
object ImageEncodeCache {
    // 压缩逻辑变化时递增，让旧缓存失效
    private const val VERSION = 1
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    @Volatile
    private var dir: File? = null

    fun init(cacheDir: File) {
        dir = cacheDir.resolve("encoded_images")
    }

    internal fun get(key: String): File? {
        val file = fileOf(key) ?: return null
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    internal fun put(key: String, bytes: ByteArray) {
        val target = fileOf(key) ?: return
        runCatching {
            val parent = target.parentFile ?: return
            parent.mkdirs()
            // 先写临时文件再重命名，避免并发请求读到写了一半的缓存
            val temp = File.createTempFile("tmp_", ".part", parent)
            try {
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) {
                    target.delete()
                    check(temp.renameTo(target)) { "Failed to move cache file to $target" }
                }
            } finally {
                temp.delete()
            }
            trim(parent)
        }.onFailure {
            Log.w(TAG, "Failed to write encoded image cache", it)
        }
    }

    private fun fileOf(key: String): File? {
        val dir = dir ?: return null
        return dir.resolve("${sha256("$VERSION|$key")}.jpg")
    }

    @Synchronized
    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "jpg" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_TOTAL_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
