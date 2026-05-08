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

    /**
     * Normalise a HuggingFace URL so that both the viewer (blob) form and the direct
     * download (resolve) form work.
     *
     * HuggingFace "view file" URLs look like:
     *   https://huggingface.co/<user>/<repo>/blob/main/<file>
     * The actual download URL is:
     *   https://huggingface.co/<user>/<repo>/resolve/main/<file>
     *
     * Pasting the viewer URL into the manual-install field would previously succeed
     * HTTP-wise (200 OK) but return an HTML page, not the model binary. This transform
     * converts blob → resolve for any huggingface.co path. Non-HF URLs are returned
     * unchanged.
     */
    fun normalizeHuggingFaceUrl(url: String): String {
        if (!url.contains("huggingface.co")) return url
        return url
            .replace("/blob/main/", "/resolve/main/")
            .replace(Regex("/blob/([^/]+)/")) { m -> "/resolve/${m.groupValues[1]}/" }
    }

    fun isValidDownloadUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            val parsed = java.net.URI(url)
            parsed.scheme == "https" && parsed.host?.isNotBlank() == true
        }.getOrDefault(false)
    }

    fun runtimeForExtension(extension: String): LocalRuntime? = when (extension.lowercase()) {
        "litertlm" -> LocalRuntime.LiteRT
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
     * Returns true if the first [count] bytes of [buf] look like an HTML document.
     * Used to abort downloads that succeeded HTTP-wise but returned an error page.
     */
    fun looksLikeHtml(buf: ByteArray, count: Int): Boolean {
        val sample = String(buf, 0, count.coerceAtMost(256), Charsets.UTF_8)
            .trimStart()
            .lowercase()
        return sample.startsWith("<!doctype") ||
               sample.startsWith("<html") ||
               sample.startsWith("<head") ||
               sample.startsWith("<body") ||
               sample.startsWith("<?xml")
    }

    /**
     * Download [url] into [target], emitting progress as it goes. Resume-aware via
     * `Range:` header on `<target>.partial`. Atomic rename to [target] only on success.
     *
     * HuggingFace blob URLs are automatically normalised to resolve URLs before the
     * request is issued. Callers may also pre-normalise with [normalizeHuggingFaceUrl].
     */
    fun download(
        client: OkHttpClient,
        url: String,
        target: File,
    ): Flow<Progress> = flow {
        target.parentFile?.mkdirs()
        val partial = File(target.absolutePath + ".partial")
        val existing = if (partial.exists()) partial.length() else 0L

        val resolvedUrl = normalizeHuggingFaceUrl(url)
        val builder = Request.Builder().url(resolvedUrl)
        if (existing > 0L) builder.addHeader("Range", "bytes=$existing-")
        val request = builder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                emit(Progress.Failed(IllegalStateException("HTTP ${response.code}")))
                return@flow
            }

            // Reject HTML responses immediately — a 200 OK from HuggingFace viewer pages,
            // Cloudflare error pages, etc. would otherwise land silently as an HTML file.
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
                emit(Progress.Failed(IllegalStateException(
                    "Server returned an HTML page, not a model file. " +
                    "Use the raw download URL — for HuggingFace, the /resolve/ form " +
                    "(we auto-rewrite /blob/ to /resolve/, but other sites may need explicit URLs)."
                )))
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
                    var sniffed = false
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        // Sniff the very first chunk for HTML preamble regardless of Content-Type.
                        if (!sniffed) {
                            sniffed = true
                            if (existing == 0L && looksLikeHtml(buf, n)) {
                                runCatching { partial.delete() }
                                emit(Progress.Failed(IllegalStateException(
                                    "Server returned an HTML page, not a model file. " +
                                    "Check the URL — for HuggingFace, paste the /blob/ or /resolve/ " +
                                    "form (auto-normalized)."
                                )))
                                return@use
                            }
                        }
                        java.nio.ByteBuffer.wrap(buf, 0, n).also { fileChan.write(it) }
                        totalRead += n
                        emit(Progress.Tick(totalRead, total))
                    }
                }
            }

            // Only rename partial → target if we completed without bailing.
            // If looksLikeHtml triggered return@use above, partial was already deleted
            // and state was set to Failed, so skip the rename/Done emit.
            if (partial.exists()) {
                if (target.exists()) target.delete()
                partial.renameTo(target)
                emit(Progress.Done(target))
            }
        }
    }.flowOn(Dispatchers.IO)
}
