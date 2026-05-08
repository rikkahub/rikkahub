package me.rerere.locallm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads a local-LLM model file from a URL into app-private storage.
 *
 * Resume-aware: if a `<filename>.partial` exists, sends a `Range:` header to pick up
 * where it stopped. Atomic rename to the final filename only after the byte count
 * matches the server-reported `Content-Length`. Cancel-safe: a cancelled coroutine
 * leaves the partial file in place so the next attempt resumes.
 *
 * Files land under `${context.filesDir}/local-models/{litert|llamacpp}/<basename>`.
 */
object ModelInstall {

    private const val LOCAL_MODELS_DIRNAME = "local-models"

    /**
     * Progress events emitted by [download]. Terminal events are [Done] and [Failed].
     */
    sealed class Progress {
        data class Started(val totalBytes: Long?) : Progress()
        data class Tick(val bytesRead: Long, val totalBytes: Long?) : Progress()
        data class Done(val file: File) : Progress()
        data class Failed(val cause: Throwable) : Progress()
    }

    fun isValidDownloadUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            val parsed = java.net.URI(url)
            parsed.scheme == "https" && parsed.host?.isNotBlank() == true
        }.getOrDefault(false)
    }

    fun runtimeForExtension(extension: String): LocalRuntime? = when (extension.lowercase()) {
        "task", "tflite" -> LocalRuntime.LiteRT
        "gguf" -> LocalRuntime.LlamaCpp
        else -> null
    }

    fun extractFileNameFromUrl(url: String): String {
        val withoutQuery = url.substringBefore('?')
        return withoutQuery.substringAfterLast('/')
    }

    fun targetFile(baseDir: File, runtime: LocalRuntime, fileName: String): File {
        val sub = when (runtime) {
            LocalRuntime.LiteRT -> "litert"
            LocalRuntime.LlamaCpp -> "llamacpp"
        }
        return File(File(baseDir, sub), fileName)
    }

    fun localModelsDir(context: Context): File =
        File(context.filesDir, LOCAL_MODELS_DIRNAME).apply { mkdirs() }

    /**
     * Download [url] into [target], emitting progress as it goes. Resume-aware via
     * `Range:` header on `<target>.partial`. Atomic rename to [target] only on success.
     */
    fun download(
        client: OkHttpClient,
        url: String,
        target: File,
    ): Flow<Progress> = flow {
        target.parentFile?.mkdirs()
        val partial = File(target.absolutePath + ".partial")
        val existing = if (partial.exists()) partial.length() else 0L

        val builder = Request.Builder().url(url)
        if (existing > 0L) builder.addHeader("Range", "bytes=$existing-")
        val request = builder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                emit(Progress.Failed(IllegalStateException("HTTP ${response.code}")))
                return@flow
            }
            val total = response.header("Content-Length")?.toLongOrNull()
                ?.let { if (existing > 0L) it + existing else it }
            emit(Progress.Started(total))

            val body = response.body ?: run {
                emit(Progress.Failed(IllegalStateException("empty body")))
                return@flow
            }

            partial.outputStream().channel.use { fileChan ->
                if (existing > 0L) fileChan.position(existing)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var totalRead = existing
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        java.nio.ByteBuffer.wrap(buf, 0, n).also { fileChan.write(it) }
                        totalRead += n
                        emit(Progress.Tick(totalRead, total))
                    }
                }
            }

            if (target.exists()) target.delete()
            partial.renameTo(target)
            emit(Progress.Done(target))
        }
    }.flowOn(Dispatchers.IO)
}
