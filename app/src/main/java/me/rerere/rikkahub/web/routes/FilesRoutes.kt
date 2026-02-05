package me.rerere.rikkahub.web.routes

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import java.io.File

fun Route.filesRoutes(
    filesManager: FilesManager,
    context: Context
) {
    route("/files") {
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
