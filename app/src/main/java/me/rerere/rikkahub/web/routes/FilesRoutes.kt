package me.rerere.rikkahub.web.routes

import android.content.Context
import androidx.core.net.toUri
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.UploadFilesResponseDto
import me.rerere.rikkahub.web.dto.UploadedFileDto
import java.io.ByteArrayOutputStream
import java.io.File

private const val MAX_UPLOAD_FILE_SIZE_BYTES = 20 * 1024 * 1024 // 20 MB

fun Route.filesRoutes(
    filesManager: FilesManager,
    context: Context
) {
    route("/files") {
        // POST /api/files/upload - Upload files
        post("/upload") {
            val multipart = call.receiveMultipart()
            val uploadedFiles = mutableListOf<UploadedFileDto>()

            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FileItem -> {
                            val originalFileName = part.originalFileName
                                ?.takeIf { it.isNotBlank() }
                                ?: "file"
                            val displayName = sanitizeDisplayName(originalFileName)
                            val mimeType = part.contentType?.toString() ?: "application/octet-stream"
                            val bytes = readPartBytes(part, MAX_UPLOAD_FILE_SIZE_BYTES)
                            if (bytes.isEmpty()) {
                                throw BadRequestException("Uploaded file is empty")
                            }

                            val entity = filesManager.saveUploadFromBytes(
                                bytes = bytes,
                                displayName = displayName,
                                mimeType = mimeType,
                            )
                            uploadedFiles.add(entity.toUploadedFileDto(filesManager))
                        }

                        else -> {
                            // Ignore non-file fields.
                        }
                    }
                } finally {
                    part.dispose()
                }
            }

            if (uploadedFiles.isEmpty()) {
                throw BadRequestException("No files uploaded")
            }

            call.respond(
                status = HttpStatusCode.Created,
                message = UploadFilesResponseDto(files = uploadedFiles)
            )
        }

        // DELETE /api/files/{id} - Delete uploaded file
        delete("/{id}") {
            val idParam = call.pathParameters["id"]
                ?: throw BadRequestException("Missing file id")
            val id = idParam.toLongOrNull()
                ?: throw BadRequestException("Invalid file id")

            val deleted = filesManager.delete(id, deleteFromDisk = true)
            if (!deleted) {
                throw NotFoundException("File not found")
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        // GET /api/files/id/{id} - Get file by ID
        get("/id/{id}") {
            val idParam = call.pathParameters["id"]
                ?: throw BadRequestException("Missing file id")
            val id = idParam.toLongOrNull()
                ?: throw BadRequestException("Invalid file id")

            val entity = filesManager.get(id)
                ?: throw NotFoundException("File not found")

            val file = filesManager.getFile(entity)
            if (!file.exists()) {
                throw NotFoundException("File not found on disk")
            }

            call.response.header("Content-Type", entity.mimeType)
            call.respondFile(file)
        }

        // GET /api/files/path/{...} - Get file by relative path
        get("/path/{path...}") {
            val relativePath = call.pathParameters.getAll("path")?.joinToString("/")
                ?: throw BadRequestException("Missing file path")

            // Validate path to prevent directory traversal attacks
            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                throw BadRequestException("Invalid file path")
            }

            val filesDir = context.filesDir
            val file = File(filesDir, relativePath)

            // Ensure the file is within the app's files directory
            if (!file.canonicalPath.startsWith(filesDir.canonicalPath)) {
                throw BadRequestException("Invalid file path")
            }

            if (!file.exists() || !file.isFile) {
                throw NotFoundException("File not found")
            }

            // Determine content type from file extension
            val contentType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> ContentType.Image.JPEG
                "png" -> ContentType.Image.PNG
                "gif" -> ContentType.Image.GIF
                "webp" -> ContentType("image", "webp")
                "svg" -> ContentType.Image.SVG
                "pdf" -> ContentType.Application.Pdf
                "json" -> ContentType.Application.Json
                "txt" -> ContentType.Text.Plain
                "html" -> ContentType.Text.Html
                "mp4" -> ContentType("video", "mp4")
                "webm" -> ContentType("video", "webm")
                "mp3" -> ContentType.Audio.MPEG
                "wav" -> ContentType("audio", "wav")
                "ogg" -> ContentType("audio", "ogg")
                else -> ContentType.Application.OctetStream
            }

            call.response.header("Content-Type", contentType.toString())
            call.respondFile(file)
        }
    }
}

// GET /api/assets/{...} - Get file from app assets
fun Route.assetsRoutes(context: Context) {
    route("/assets") {
        get("/{path...}") {
            val relativePath = call.pathParameters.getAll("path")?.joinToString("/")
                ?: throw BadRequestException("Missing asset path")

            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                throw BadRequestException("Invalid asset path")
            }

            val contentType = when (relativePath.substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> ContentType.Image.JPEG
                "png" -> ContentType.Image.PNG
                "gif" -> ContentType.Image.GIF
                "webp" -> ContentType("image", "webp")
                "svg" -> ContentType.Image.SVG
                "json" -> ContentType.Application.Json
                "html" -> ContentType.Text.Html
                "txt" -> ContentType.Text.Plain
                else -> ContentType.Application.OctetStream
            }

            try {
                val inputStream = context.assets.open(relativePath)
                call.response.header("Content-Type", contentType.toString())
                call.respondOutputStream {
                    inputStream.use { it.copyTo(this) }
                }
            } catch (_: Exception) {
                throw NotFoundException("Asset not found: $relativePath")
            }
        }
    }
}

private suspend fun readPartBytes(part: PartData.FileItem, maxBytes: Int): ByteArray {
    val input = part.provider()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0

    while (true) {
        val read = input.readAvailable(buffer, 0, buffer.size)
        if (read <= 0) break

        totalBytes += read
        if (totalBytes > maxBytes) {
            throw BadRequestException("File too large: max ${maxBytes / (1024 * 1024)} MB")
        }

        output.write(buffer, 0, read)
    }

    return output.toByteArray()
}

private fun sanitizeDisplayName(fileName: String): String {
    val normalized = fileName.substringAfterLast('/').substringAfterLast('\\')
    return normalized
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        .ifBlank { "file" }
}

private fun ManagedFileEntity.toUploadedFileDto(filesManager: FilesManager) = UploadedFileDto(
    id = id,
    url = filesManager.getFile(this).toUri().toString(),
    fileName = displayName,
    mime = mimeType,
    size = sizeBytes,
)
