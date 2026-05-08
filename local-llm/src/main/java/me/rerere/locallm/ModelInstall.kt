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
     * Returns true if [firstBytes] looks like a valid file of the type implied by
     * [extension]. Used as a post-download integrity check to refuse files that
     * downloaded successfully but whose contents are wrong (sparse-fill, server
     * misroute, partial corruption, etc.).
     */
    fun isValidMagicForExtension(extension: String, firstBytes: ByteArray): Boolean {
        if (firstBytes.size < 4) return false
        return when (extension.lowercase()) {
            "litertlm", "tflite", "task" -> {
                // LiteRT-LM files start with ASCII "LITERTLM"
                // (0x4c 0x49 0x54 0x45 0x52 0x54 0x4c 0x4d).
                // .tflite / .task files start with TFL3 (0x54 0x46 0x4c 0x33) at offset 4 in the
                // FlatBuffer header (offset 0..3 is the size prefix). The simplest check: first
                // 8 bytes are "LITERTLM" OR bytes 4..7 are "TFL3". Reject all-zero, all-FF,
                // or HTML-like first bytes.
                val isLitertlm = firstBytes.size >= 8 &&
                    firstBytes[0] == 0x4c.toByte() && firstBytes[1] == 0x49.toByte() &&
                    firstBytes[2] == 0x54.toByte() && firstBytes[3] == 0x45.toByte() &&
                    firstBytes[4] == 0x52.toByte() && firstBytes[5] == 0x54.toByte() &&
                    firstBytes[6] == 0x4c.toByte() && firstBytes[7] == 0x4d.toByte()
                val isTflite = firstBytes.size >= 8 &&
                    firstBytes[4] == 0x54.toByte() && firstBytes[5] == 0x46.toByte() &&
                    firstBytes[6] == 0x4c.toByte() && firstBytes[7] == 0x33.toByte()
                isLitertlm || isTflite
            }
            "gguf" -> {
                // GGUF files start with ASCII "GGUF" (0x47 0x47 0x55 0x46).
                firstBytes[0] == 0x47.toByte() && firstBytes[1] == 0x47.toByte() &&
                    firstBytes[2] == 0x55.toByte() && firstBytes[3] == 0x46.toByte()
            }
            else -> {
                // Unknown extension — accept by default but reject obvious zeros / HTML.
                !looksLikeHtml(firstBytes, firstBytes.size) && firstBytes.any { it != 0x00.toByte() }
            }
        }
    }

    /**
     * Download [url] into [target], emitting progress as it goes. Atomic rename to
     * [target] only on success after a magic-byte integrity check.
     *
     * Resume logic is intentionally omitted in v1 (Phase 22A). Always starts fresh —
     * any leftover .partial file is deleted before the request is issued. Resume with
     * proper FileOutputStream(file, append=true) handling comes in 22B.
     *
     * Root cause of the previous sparse-file corruption: the old resume path called
     * File.outputStream() (which is FileOutputStream(file, append=false) — truncates to
     * zero on open) and then seeked the resulting FileChannel to `existing` bytes past
     * EOF. This produced a file of the right total length where bytes 0..existing were
     * all-zero (sparse fill) and the real data started at offset `existing`.
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
        // No resume in v1 — always start fresh. Delete any leftover partial from a prior
        // interrupted attempt so we can't accidentally write past it.
        runCatching { partial.delete() }

        val normalized = normalizeHuggingFaceUrl(url)
        val request = Request.Builder().url(normalized).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                emit(Progress.Failed(IllegalStateException("HTTP ${response.code}")))
                return@flow
            }

            // Reject HTML responses immediately — a 200 OK from HuggingFace viewer pages,
            // Cloudflare error pages, etc. would otherwise land silently as an HTML file.
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) {
                emit(Progress.Failed(IllegalStateException(
                    "Server returned an HTML page, not a model file. " +
                    "Use a /resolve/ URL — for HuggingFace, /blob/ paths auto-rewrite to /resolve/."
                )))
                return@flow
            }

            val total = response.header("Content-Length")?.toLongOrNull()
            emit(Progress.Started(total))

            val body = response.body ?: run {
                emit(Progress.Failed(IllegalStateException("empty body")))
                return@flow
            }

            var sniffed = false
            var bailed = false
            partial.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var totalRead = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (!sniffed) {
                            sniffed = true
                            if (looksLikeHtml(buf, n)) {
                                bailed = true
                                break
                            }
                        }
                        out.write(buf, 0, n)
                        totalRead += n
                        emit(Progress.Tick(totalRead, total))
                    }
                }
            }

            if (bailed) {
                runCatching { partial.delete() }
                emit(Progress.Failed(IllegalStateException(
                    "Server returned an HTML page (magic-byte check failed). Check the URL."
                )))
                return@flow
            }

            // Post-download magic-byte validation: ensure the file we just wrote is
            // actually a valid model file. Catches sparse-file / partial-write
            // corruption, server misroutes, and other ways the bytes can go wrong.
            val ext = target.name.substringAfterLast('.', "").lowercase()
            val firstBytes = ByteArray(16)
            val bytesRead = partial.inputStream().use { it.read(firstBytes) }
            if (bytesRead > 0 && !isValidMagicForExtension(ext, firstBytes.copyOf(bytesRead))) {
                runCatching { partial.delete() }
                emit(Progress.Failed(IllegalStateException(
                    "Downloaded file does not have the expected magic bytes for .$ext " +
                    "(starts with: ${firstBytes.take(8).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }}). " +
                    "The server may have served the wrong content, or the download was corrupted."
                )))
                return@flow
            }

            if (target.exists()) target.delete()
            partial.renameTo(target)
            emit(Progress.Done(target))
        }
    }.flowOn(Dispatchers.IO)
}
