package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.exportImageFile
import me.rerere.rikkahub.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
) {
    companion object {
        private const val TAG = "FilesManager"
    }

    suspend fun saveUploadFromUri(
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
        val target = createTargetFile(FileFolders.UPLOAD, resolvedName, resolvedMime)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val now = System.currentTimeMillis()
        repository.insert(
            ManagedFileEntity(
                folder = FileFolders.UPLOAD,
                relativePath = "${FileFolders.UPLOAD}/${target.name}",
                displayName = resolvedName,
                mimeType = resolvedMime,
                sizeBytes = target.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun saveUploadFromBytes(
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(FileFolders.UPLOAD, displayName, mimeType)
        target.writeBytes(bytes)
        val now = System.currentTimeMillis()
        repository.insert(
            ManagedFileEntity(
                folder = FileFolders.UPLOAD,
                relativePath = "${FileFolders.UPLOAD}/${target.name}",
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = target.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun saveUploadText(
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(FileFolders.UPLOAD, displayName, mimeType)
        target.writeText(text)
        val now = System.currentTimeMillis()
        repository.insert(
            ManagedFileEntity(
                folder = FileFolders.UPLOAD,
                relativePath = "${FileFolders.UPLOAD}/${target.name}",
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = target.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

    fun createChatFilesByContents(uris: List<Uri>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
            val sourceMime = getFileMimeType(uri)
            val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val newUri = file.toUri()
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    file.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                trackUploadFile(file = file, displayName = sourceName, mimeType = guessedMime)
                newUris.add(newUri)
            }.onFailure {
                it.printStackTrace()
                Log.e(TAG, "createChatFilesByContents: Failed to save file from $uri", it)
                Logging.log(
                    TAG,
                    "createChatFilesByContents: Failed to save file from $uri ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
        return newUris
    }

    fun createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            val fileName = buildUuidFileName(displayName = "image.png", mimeType = "image/png")
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val newUri = file.toUri()
            file.outputStream().use { outputStream ->
                outputStream.write(byteArray)
            }
            trackUploadFile(file = file, displayName = "image.png", mimeType = "image/png")
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> {
                            if (part.url.startsWith("data:image")) {
                                val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                                val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                                val byteArray = bitmap.compressToPng()
                                val urls = createChatFilesByByteArrays(listOf(byteArray))
                                Log.i(
                                    TAG,
                                    "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                                )
                                part.copy(
                                    url = urls.first().toString(),
                                )
                            } else {
                                part
                            }
                        }

                        else -> part
                    }
                }
            )
        }

    fun deleteChatFiles(uris: List<Uri>) {
        val relativePaths = mutableSetOf<String>()
        uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
            val file = uri.toFile()
            getRelativePathInFilesDir(file)?.let { relativePaths.add(it) }
            if (file.exists()) {
                file.delete()
            }
        }
        if (relativePaths.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                relativePaths.forEach { path ->
                    repository.deleteByPath(path)
                }
            }
        }
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        trackUploadFile(file = file, displayName = "pasted_text.txt", mimeType = "text/plain")
        return UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve("images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        when {
            image.startsWith("data:image") -> {
                val inlineImage = parseInlineImage(image)
                val tempFile = createTempImageFile(inlineImage.mimeType)
                try {
                    tempFile.writeBytes(inlineImage.bytes)
                    check(
                        activityContext.exportImageFile(
                            activity = activity,
                            file = tempFile,
                            fileName = inlineImage.fileName,
                            mimeType = inlineImage.mimeType,
                        )
                    ) { "Failed to save image" }
                } finally {
                    tempFile.delete()
                }
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                check(file.exists()) { "Image file not found" }
                check(
                    activityContext.exportImageFile(
                        activity = activity,
                        file = file,
                        fileName = file.name,
                        mimeType = guessMimeType(file, file.name),
                    )
                ) { "Failed to save image file" }
            }

            image.startsWith("content:") -> {
                val uri = image.toUri()
                val fileName = getFileNameFromUri(uri) ?: "RikkaHub_${System.currentTimeMillis()}"
                val mimeType = normalizeImageMimeType(
                    getFileMimeType(uri)
                        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            fileName.substringAfterLast('.', "").lowercase()
                        )
                )
                val tempFile = createTempImageFile(mimeType)
                try {
                    activityContext.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: error("Image content not found")
                    check(
                        activityContext.exportImageFile(
                            activity = activity,
                            file = tempFile,
                            fileName = ensureImageExtension(fileName, mimeType),
                            mimeType = mimeType,
                        )
                    ) { "Failed to save content image" }
                } finally {
                    tempFile.delete()
                }
            }

            image.startsWith("http") -> {
                val connection = (URL(image).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                var tempFile: File? = null
                try {
                    connection.connect()
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "Failed to download image, response code: ${connection.responseCode}"
                    }
                    val mimeType = normalizeImageMimeType(connection.contentType)
                    val fileName = resolveRemoteImageFileName(
                        image = image,
                        mimeType = mimeType,
                        contentDisposition = connection.getHeaderField("Content-Disposition"),
                    )
                    tempFile = createTempImageFile(mimeType)
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    check(
                        activityContext.exportImageFile(
                            activity = activity,
                            file = tempFile,
                            fileName = fileName,
                            mimeType = mimeType,
                        )
                    ) { "Failed to save downloaded image" }
                } finally {
                    connection.disconnect()
                    tempFile?.delete()
                }
            }

            else -> {
                val file = File(image)
                check(file.exists()) { "Image file not found" }
                check(
                    activityContext.exportImageFile(
                        activity = activity,
                        file = file,
                        fileName = file.name,
                        mimeType = guessMimeType(file, file.name),
                    )
                ) { "Failed to save local image file" }
            }
        }
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): Int = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) return@withContext 0
        val files = dir.listFiles()?.filter { it.isFile } ?: return@withContext 0
        var inserted = 0
        files.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            val existing = repository.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                val displayName = file.name
                val mimeType = guessMimeType(file, displayName)
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                )
                inserted += 1
            }
        }
        inserted
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (deleteFromDisk) {
            runCatching { getFile(entity).delete() }
        }
        repository.deleteById(id) > 0
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String {
        val extFromName = displayName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it != displayName }
            ?.lowercase()
        val extFromMime = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.lowercase()) }
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
        val ext = extFromName ?: extFromMime ?: "bin"
        return "${Uuid.random()}.$ext"
    }

    private fun trackUploadFile(file: File, displayName: String, mimeType: String) {
        val relativePath = "${FileFolders.UPLOAD}/${file.name}"
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = repository.getByPath(relativePath)
                if (existing != null) {
                    return@runCatching
                }
                val now = System.currentTimeMillis()
                repository.insert(
                    ManagedFileEntity(
                        folder = FileFolders.UPLOAD,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }.onFailure {
                Log.e(TAG, "trackUploadFile: Failed to track file ${file.absolutePath}", it)
                Logging.log(
                    TAG,
                    "trackUploadFile: Failed to track file ${file.absolutePath} ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
    }

    private fun getRelativePathInFilesDir(file: File): String? {
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val canonicalFilesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return null
        val basePath = canonicalFilesDir.path
        val filePath = canonicalFile.path
        if (!filePath.startsWith("$basePath${File.separator}")) {
            return null
        }
        return canonicalFile.relativeTo(canonicalFilesDir).path.replace(File.separatorChar, '/')
    }

    fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val documentDisplayNameIndex =
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (documentDisplayNameIndex != -1) {
                    fileName = cursor.getString(documentDisplayNameIndex)
                } else {
                    val openableDisplayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (openableDisplayNameIndex != -1) {
                        fileName = cursor.getString(openableDisplayNameIndex)
                    }
                }
            }
        }
        return fileName
    }

    fun getFileMimeType(uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> context.contentResolver.getType(uri)
            else -> null
        }
    }

    private fun guessMimeType(file: File, fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            return when (ext) {
                "svg" -> "image/svg+xml"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            } ?: "application/octet-stream"
        }
        return sniffMimeType(file)
    }

    private fun sniffMimeType(file: File): String {
        val header = ByteArray(16)
        val read = runCatching {
            FileInputStream(file).use { input ->
                input.read(header)
            }
        }.getOrDefault(-1)

        if (read <= 0) return "application/octet-stream"

        // Magic numbers
        if (header.startsWithBytes(0x89, 0x50, 0x4E, 0x47)) return "image/png"
        if (header.startsWithBytes(0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (header.startsWithBytes(0x47, 0x49, 0x46, 0x38)) return "image/gif"
        if (header.startsWithBytes(0x25, 0x50, 0x44, 0x46)) return "application/pdf"
        if (header.startsWithBytes(0x50, 0x4B, 0x03, 0x04)) return "application/zip"
        if (header.startsWithBytes(0x50, 0x4B, 0x05, 0x06)) return "application/zip"
        if (header.startsWithBytes(0x50, 0x4B, 0x07, 0x08)) return "application/zip"
        if (header.startsWithBytes(0x52, 0x49, 0x46, 0x46) && header.sliceArray(8..11)
                .contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))
        ) {
            return "image/webp"
        }

        // Heuristic: treat mostly printable UTF-8 as text/plain
        val textSample = runCatching {
            val sample = ByteArray(512)
            FileInputStream(file).use { input ->
                val len = input.read(sample)
                if (len <= 0) return@runCatching null
                sample.copyOf(len)
            }
        }.getOrNull()
        if (textSample != null && isLikelyText(textSample)) {
            return "text/plain"
        }

        return "application/octet-stream"
    }

    private fun isLikelyText(bytes: ByteArray): Boolean {
        var printable = 0
        var total = 0
        bytes.forEach { b ->
            val c = b.toInt() and 0xFF
            total += 1
            if (c == 0x09 || c == 0x0A || c == 0x0D) {
                printable += 1
            } else if (c in 0x20..0x7E) {
                printable += 1
            }
        }
        return total > 0 && printable.toDouble() / total >= 0.8
    }

    private fun parseInlineImage(image: String): InlineImageData {
        val header = image.substringBefore(',')
        check(header.contains(";base64")) { "Unsupported image data URL" }
        val mimeType = normalizeImageMimeType(header.substringAfter("data:").substringBefore(';'))
        val fileName = "RikkaHub_${System.currentTimeMillis()}.${extensionFromMimeType(mimeType)}"
        return InlineImageData(
            bytes = Base64.decode(image.substringAfter("base64,").toByteArray()),
            mimeType = mimeType,
            fileName = fileName,
        )
    }

    private fun createTempImageFile(mimeType: String): File {
        return File.createTempFile(
            "message_image_",
            ".${extensionFromMimeType(mimeType)}",
            context.cacheDir,
        )
    }

    private fun resolveRemoteImageFileName(
        image: String,
        mimeType: String,
        contentDisposition: String?,
    ): String {
        extractFileNameFromContentDisposition(contentDisposition)?.let {
            return ensureImageExtension(it, mimeType)
        }

        val pathName = runCatching { URL(image).path.substringAfterLast('/') }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (pathName != null) {
            return ensureImageExtension(pathName, mimeType)
        }

        return "RikkaHub_${System.currentTimeMillis()}.${extensionFromMimeType(mimeType)}"
    }

    private fun extractFileNameFromContentDisposition(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val encodedFileName = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)
            ?.groupValues
            ?.getOrNull(1)
        if (!encodedFileName.isNullOrBlank()) {
            return URLDecoder.decode(encodedFileName, Charsets.UTF_8.name())
        }

        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(contentDisposition)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun normalizeImageMimeType(mimeType: String?): String {
        return mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.startsWith("image/") }
            ?: "image/png"
    }

    private fun extensionFromMimeType(mimeType: String): String {
        return when (mimeType) {
            "image/svg+xml" -> "svg"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        } ?: "png"
    }

    private fun ensureImageExtension(fileName: String, mimeType: String): String {
        val normalizedFileName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "RikkaHub_${System.currentTimeMillis()}" }
        return if (normalizedFileName.contains('.')) {
            normalizedFileName
        } else {
            "$normalizedFileName.${extensionFromMimeType(mimeType)}"
        }
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (this.size < values.size) return false
        for (i in values.indices) {
            if ((this[i].toInt() and 0xFF) != values[i]) return false
        }
        return true
    }

    private fun Bitmap.compressToPng(): ByteArray = ByteArrayOutputStream().use {
        compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
    }

    private data class InlineImageData(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String,
    )
}

object FileFolders {
    const val UPLOAD = "upload"
}
