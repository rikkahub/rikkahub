package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.telegram.AttachmentKind
import me.rerere.rikkahub.data.telegram.TelegramAttachment
import me.rerere.rikkahub.data.telegram.TelegramBotClient
import java.io.File

/**
 * Helpers for downloading inbound Telegram photos / documents / voice notes to a per-chat
 * shared-storage inbox, plus the structured "notes" appended to the user message so the
 * LLM sees an inventory of what arrived.
 *
 * Path policy: `/sdcard/Download/telegram_inbox/<chatId>/`. Shared storage (NOT app cache)
 * so Termux, file tools, and the user can reach the files.
 *
 * Inbox hygiene: files older than 24 h are pruned, then total size is capped at
 * [INBOUND_ATTACHMENT_INBOX_CAP_BYTES] (oldest first).
 */
private const val TAG = "TelegramInboundAttachments"

/** Maximum file size for auto-downloading inbound non-photo attachments.
 *  Telegram allows up to 2 GB; we cap at 50 MB to avoid surprise storage use. */
const val INBOUND_ATTACHMENT_SIZE_CAP_BYTES: Long = 50L * 1024 * 1024   // 50 MB

/** Total size cap for the per-chat attachment inbox. Oldest files are pruned when
 *  this is exceeded. */
private const val INBOUND_ATTACHMENT_INBOX_CAP_BYTES: Long = 500L * 1024 * 1024  // 500 MB

/**
 * Result of a single attachment download attempt.
 *
 * [savedPath] is set when the file was successfully downloaded. [skipReason] is set when we
 * intentionally did NOT download (e.g. size cap) — the LLM still gets a note about the file.
 * Both being null means an unexpected error occurred (logged separately; omitted from the note).
 */
internal data class DownloadedAttachment(
    val attachment: TelegramAttachment,
    val savedPath: String?,
    val skipReason: String?,
)

/** The per-chat inbound-attachment inbox on shared storage. Termux / file tools can reach it. */
internal fun inboxDirFor(chatId: Long): File =
    File("/sdcard/Download/telegram_inbox/$chatId").apply { mkdirs() }

/**
 * Prune a per-chat inbox: drop files older than 24h, then cap total size at
 * [INBOUND_ATTACHMENT_INBOX_CAP_BYTES] (oldest first). Shared by the photo and
 * non-photo download paths so both kinds get the same hygiene.
 */
internal fun pruneInbox(inboxDir: File) {
    val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    inboxDir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) f.delete() }

    val allFiles = inboxDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
    if (allFiles != null) {
        var totalSize = allFiles.sumOf { it.length() }
        for (f in allFiles) {
            if (totalSize <= INBOUND_ATTACHMENT_INBOX_CAP_BYTES) break
            totalSize -= f.length()
            f.delete()
        }
    }
}

/**
 * Resolve each Telegram photo file_id to a downloaded file in the per-chat shared-storage
 * inbox (so Termux / file tools can also reach it, not just the in-process vision pipeline),
 * then return both the UIMessagePart.Image entries (file:// URIs, for vision-capable models)
 * AND the saved absolute paths (so [buildPhotoNote] can surface them in the message text for
 * text-only models). Failures on individual photos are logged and skipped so a transient
 * network blip on one image does not drop the whole message.
 */
internal suspend fun downloadInboundPhotos(
    client: TelegramBotClient,
    chatId: Long,
    fileIds: List<String>,
): Pair<List<UIMessagePart.Image>, List<String>> {
    if (fileIds.isEmpty()) return emptyList<UIMessagePart.Image>() to emptyList()
    val inboxDir = inboxDirFor(chatId)
    pruneInbox(inboxDir)

    val images = mutableListOf<UIMessagePart.Image>()
    val paths = mutableListOf<String>()
    for (fileId in fileIds) {
        try {
            val info = client.getFile(fileId)
            val filePath = info["file_path"]?.jsonPrimitive?.contentOrNull
            if (filePath == null) {
                Log.w(TAG, "downloadInboundPhotos: getFile returned no file_path for id=$fileId")
                continue
            }
            val ext = filePath.substringAfterLast('.', "jpg")
            // fileId suffix keeps two photos in the same message unique even within one ms.
            val dest = uniqueFile(inboxDir, "photo_${System.currentTimeMillis()}_${fileId.takeLast(6)}.$ext")
            client.downloadFile(filePath, dest)
            images.add(UIMessagePart.Image(url = "file://${dest.absolutePath}"))
            paths.add(dest.absolutePath)
            Log.i(TAG, "downloadInboundPhotos: saved ${dest.name} (${dest.length()} bytes)")
        } catch (e: Throwable) {
            Log.w(TAG, "downloadInboundPhotos: failed for $fileId", e)
        }
    }
    return images to paths
}

/**
 * Download non-photo inbound attachments to /sdcard/Download/telegram_inbox/<chatId>/.
 *
 * Differences vs downloadInboundPhotos:
 * - Saves to shared storage (not app cache) so the LLM can point file-manager / Termux at the paths.
 * - Preserves original filenames (sanitized); falls back to tg-<ts>-<suffix>.<ext>.
 * - Skips files > INBOUND_ATTACHMENT_SIZE_CAP_BYTES and surfaces a note instead.
 * - Prunes files older than 24 h and caps total inbox size at 500 MB.
 * - Handles filename collisions by appending a timestamp suffix before the extension.
 */
internal suspend fun downloadInboundAttachments(
    client: TelegramBotClient,
    chatId: Long,
    attachments: List<TelegramAttachment>,
): List<DownloadedAttachment> {
    if (attachments.isEmpty()) return emptyList()

    val inboxDir = inboxDirFor(chatId)
    pruneInbox(inboxDir)

    val out = mutableListOf<DownloadedAttachment>()
    for (att in attachments) {
        // Skip over-sized attachments without downloading.
        if (att.sizeBytes != null && att.sizeBytes > INBOUND_ATTACHMENT_SIZE_CAP_BYTES) {
            val sizeMb = att.sizeBytes / (1024.0 * 1024.0)
            out.add(DownloadedAttachment(att, savedPath = null, skipReason = "exceeds 50 MB cap (${String.format("%.1f", sizeMb)} MB)"))
            continue
        }

        try {
            val info = client.getFile(att.fileId)
            val filePath = info["file_path"]?.jsonPrimitive?.contentOrNull
            if (filePath == null) {
                Log.w(TAG, "downloadInboundAttachments: no file_path for ${att.fileId}")
                continue
            }
            val ext = filePath.substringAfterLast('.', "bin")
            val safeName = sanitizeAttachmentFilename(att.originalFileName, att.fileId, ext)
            val dest = uniqueFile(inboxDir, safeName)
            client.downloadFile(filePath, dest)
            out.add(DownloadedAttachment(att, savedPath = dest.absolutePath, skipReason = null))
            Log.i(TAG, "downloadInboundAttachments: saved ${dest.name} (${dest.length()} bytes)")
        } catch (e: Throwable) {
            Log.w(TAG, "downloadInboundAttachments: failed for ${att.fileId}", e)
            // Don't add to out — error is silently skipped so other attachments still download.
        }
    }
    return out
}

/**
 * Build the structured attachment note that is appended to the user message text so the LLM
 * sees a clear inventory of every file that arrived with this message.
 *
 * Format:
 * ```
 * [User attached N file(s) with this message:
 * - documents/Invoice.pdf  (application/pdf, 1.2 MB) → saved to /sdcard/Download/telegram_inbox/123/Invoice.pdf
 * - voice/voice.ogg  (audio/ogg, 30s, 0.3 MB) → saved to /sdcard/Download/telegram_inbox/123/voice.ogg
 * - documents/huge.zip  (application/zip, 200 MB) → SKIPPED: exceeds 50 MB cap
 * ]
 * ```
 */
internal fun buildAttachmentNote(downloads: List<DownloadedAttachment>): String {
    if (downloads.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("[User attached ${downloads.size} file(s) with this message:\n")
    for (dl in downloads) {
        val att = dl.attachment
        val kindSlug = att.kind.name.lowercase()
        val nameForDisplay = att.originalFileName ?: when (att.kind) {
            AttachmentKind.VOICE -> "voice.ogg"
            AttachmentKind.VIDEO_NOTE -> "video_note.mp4"
            else -> "attachment"
        }
        val sizePart = att.sizeBytes?.let { bytes ->
            val mb = bytes / (1024.0 * 1024.0)
            if (mb >= 0.1) String.format("%.1f MB", mb) else String.format("%d KB", bytes / 1024)
        }
        val durPart = att.durationSec?.let { "${it}s" }
        val mimePart = att.mimeType
        val metaParts = listOfNotNull(mimePart, durPart, sizePart).joinToString(", ")
        val destination = when {
            dl.savedPath != null -> "saved to ${dl.savedPath}"
            dl.skipReason != null -> "SKIPPED: ${dl.skipReason}"
            else -> "download failed"
        }
        val metaSuffix = if (metaParts.isNotEmpty()) "  ($metaParts)" else ""
        sb.append("- $kindSlug/$nameForDisplay$metaSuffix → $destination\n")
    }
    sb.append("]")
    return sb.toString()
}

/**
 * Build the structured note appended to the user message when inbound photos arrive, so the
 * LLM learns the saved file path(s). Mirrors [buildAttachmentNote] — it lets the model OCR /
 * process the image via file tools or Termux even when the configured model has no vision
 * pipeline. Returns "" when no photo was saved.
 */
internal fun buildPhotoNote(paths: List<String>): String {
    if (paths.isEmpty()) return ""
    val noun = if (paths.size == 1) "photo" else "photos"
    val sb = StringBuilder()
    sb.append("[User attached ${paths.size} $noun with this message:\n")
    for (p in paths) {
        sb.append("- photo → saved to $p\n")
    }
    sb.append("View it directly if you have vision, or process the file at that path (e.g. OCR with `tesseract` via Termux).]")
    return sb.toString()
}

/**
 * Sanitize a Telegram-provided filename before using it as the on-disk filename in the
 * per-chat inbox. Cleans:
 * 1. Strip directory separators (use last path component only).
 * 2. Strip leading dots to avoid hidden-file names.
 * 3. Remove ASCII control characters (0x00–0x1F) and null bytes.
 * 4. Trim whitespace from both ends.
 * 5. Truncate to 200 characters preserving the file extension.
 * 6. If the result is empty after sanitization, fall back to `tg-<timestamp>-<fileIdSuffix>.<ext>`.
 */
internal fun sanitizeAttachmentFilename(
    raw: String?,
    fileId: String,
    fallbackExt: String,
): String {
    if (!raw.isNullOrBlank()) {
        // 1. Take the last path component only (strip directory separators).
        var name = raw.replace('\\', '/').substringAfterLast('/')
        // 2. Strip leading dots.
        name = name.trimStart('.')
        // 3. Remove control characters.
        name = name.filter { it.code > 0x1F }
        // 4. Trim whitespace.
        name = name.trim()
        // 5. Truncate to 200 chars, preserving extension.
        if (name.length > 200) {
            val ext = if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""
            val base = name.substringBeforeLast('.').take(200 - ext.length)
            name = "$base$ext"
        }
        if (name.isNotEmpty()) return name
    }
    // 6. Fallback.
    return "tg-${System.currentTimeMillis()}-${fileId.takeLast(8)}.$fallbackExt"
}

/**
 * Return a [File] in [dir] with [preferredName] that does not already exist.
 * If a file with that name already exists, appends `-<timestamp>` before the extension.
 */
internal fun uniqueFile(dir: File, preferredName: String): File {
    val candidate = File(dir, preferredName)
    if (!candidate.exists()) return candidate
    val ts = System.currentTimeMillis()
    val ext = if (preferredName.contains('.')) ".${preferredName.substringAfterLast('.')}" else ""
    val base = if (ext.isNotEmpty()) preferredName.substringBeforeLast('.') else preferredName
    return File(dir, "$base-$ts$ext")
}
