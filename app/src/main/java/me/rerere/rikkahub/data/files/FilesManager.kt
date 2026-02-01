package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.getFileMimeType
import me.rerere.rikkahub.utils.getFileNameFromUri
import java.io.File
import kotlin.uuid.Uuid

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
) {
    suspend fun saveUploadFromUri(
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: context.getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: context.getFileMimeType(uri) ?: "application/octet-stream"
        val target = createTargetFile(FileFolders.UPLOAD, resolvedName)
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
        val target = createTargetFile(FileFolders.UPLOAD, displayName)
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
        val target = createTargetFile(FileFolders.UPLOAD, displayName)
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

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder)

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

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

    private fun createTargetFile(folder: String, displayName: String): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val ext = displayName.substringAfterLast('.', "")
        val name = if (ext.isNotEmpty() && ext != displayName) {
            "${Uuid.random()}.$ext"
        } else {
            Uuid.random().toString()
        }
        return File(dir, name)
    }

    private fun guessMimeType(file: File, fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
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

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (this.size < values.size) return false
        for (i in values.indices) {
            if ((this[i].toInt() and 0xFF) != values[i]) return false
        }
        return true
    }
}

object FileFolders {
    const val UPLOAD = "upload"
}
