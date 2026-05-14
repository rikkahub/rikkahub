package me.rerere.rikkahub.data.telegram

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal Telegram Bot API client. Direct HTTPS calls per https://core.telegram.org/bots/api —
 * no third-party bot framework. Returns parsed JSON `result` on success, throws TelegramApiException
 * on Telegram-side errors. Long-poll-friendly: getUpdates uses a separate OkHttpClient with a
 * read-timeout long enough to honor the server-side timeout.
 */
class TelegramApiException(val errorCode: Int, val description: String) :
    RuntimeException("Telegram API $errorCode: $description")

class TelegramBotClient(
    private val tokenProvider: () -> String,
) {
    private val tag = "TelegramBotClient"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val shortClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // End-to-end cap. readTimeout only catches gaps BETWEEN bytes; downloadFile streams
        // Telegram-hosted files up to the 50 MB cap, so a path that trickles bytes just under
        // the 30s readTimeout window could stay alive far longer than intended. 10 min is
        // generous enough for a large file on slow mobile data yet bounds the worst case so a
        // stuck call can never wedge the request forever.
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
        .also { me.rerere.rikkahub.utils.NetworkChangeMonitor.register(it) }

    private val pollClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // server-side max 50s + headroom
        // End-to-end cap on the whole call. readTimeout only catches gaps BETWEEN bytes;
        // a hostile/buggy network path can keep the connection alive with periodic single
        // bytes below the readTimeout window and the long-poll never returns. callTimeout
        // is enforced regardless of byte arrival pattern, so the poll-loop can never sit
        // silently for more than ~2 minutes — the catch in pollLoop bumps the backoff and
        // retries. Without this the bot looks dead from the user's POV until the OS
        // eventually evicts the socket.
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
        .also { me.rerere.rikkahub.utils.NetworkChangeMonitor.register(it) }

    private fun base() = "https://api.telegram.org/bot${tokenProvider()}"

    /**
     * Replace the live bot token in any string with "***REDACTED***". Used at the boundary
     * of every network call so the token can't escape into a stack trace, IOException
     * message, or crash report. OkHttp generally doesn't include the request URL in its
     * IOException messages, but better safe than sorry — a wrong logging interceptor or a
     * future OkHttp change could otherwise expose the token.
     */
    private fun redactToken(s: String?): String {
        if (s.isNullOrEmpty()) return s.orEmpty()
        val token = try { tokenProvider() } catch (t: Throwable) {
            Log.w(tag, "tokenProvider failed while redacting token", t)
            ""
        }
        return if (token.isNotBlank() && s.contains(token)) s.replace(token, "***REDACTED***") else s
    }

    /** Like [redactToken] but produces a fresh IOException so the (immutable) message is scrubbed. */
    private fun redactException(e: IOException): IOException {
        val msg = e.message
        val token = try { tokenProvider() } catch (t: Throwable) {
            Log.w(tag, "tokenProvider failed while redacting exception", t)
            ""
        }
        if (token.isBlank() || msg == null || !msg.contains(token)) return e
        return IOException(redactToken(msg), e.cause)
    }

    /** Verify the token by calling getMe. Returns the bot info JSON. */
    suspend fun getMe(): JsonObject = call(shortClient, "getMe", buildJsonObject {}).jsonObject

    /**
     * Long-poll for new updates. Suspends up to ~50s on the server side. Returns the array
     * of updates (possibly empty) and the recommended next offset.
     */
    suspend fun getUpdates(offset: Long, timeoutSec: Int = 30): JsonArray {
        val body = buildJsonObject {
            put("offset", offset)
            put("timeout", timeoutSec)
            // We now handle callback_query updates for tool-approval inline keyboards.
            // The poll-loop's offset bump catches and skips any other update type.
            put("allowed_updates", buildJsonArray {
                add("message")
                add("callback_query")
            })
        }
        val res = call(pollClient, "getUpdates", body)
        return res.jsonArray
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: String? = null,
        replyToMessageId: Long? = null,
        disableWebPagePreview: Boolean = false,
        replyMarkup: JsonObject? = null,
    ): JsonObject = call(shortClient, "sendMessage", buildJsonObject {
        put("chat_id", chatId)
        put("text", text)
        if (parseMode != null) put("parse_mode", parseMode)
        if (replyToMessageId != null) put("reply_to_message_id", replyToMessageId)
        if (disableWebPagePreview) put("disable_web_page_preview", true)
        // reply_markup carries the inline keyboard JSON used for tool-approval prompts.
        // Format: {"inline_keyboard": [[{"text": "...", "callback_data": "..."}, ...]]}.
        // Telegram caps callback_data at 64 bytes per button.
        if (replyMarkup != null) put("reply_markup", replyMarkup)
    }).jsonObject

    /**
     * Acknowledge a callback_query (inline-keyboard tap). Telegram REQUIRES this within
     * ~15s of the callback or the user sees a perpetual loading spinner on the button.
     * The optional [text] shows as a small toast over the chat for ~5s.
     */
    suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false,
    ): Boolean = try {
        call(shortClient, "answerCallbackQuery", buildJsonObject {
            put("callback_query_id", callbackQueryId)
            if (text != null) put("text", text)
            put("show_alert", showAlert)
        }).jsonPrimitive.boolean
    } catch (_: Throwable) {
        false  // best-effort; the flow doesn't depend on the ack
    }

    /** Show the "typing..." indicator in the user's Telegram chat. Auto-clears after ~5s on
     *  Telegram's side, so callers should re-send periodically while doing long work.
     *  Telegram returns a bare Bool for this method — calling .jsonObject on a JsonPrimitive
     *  throws, which is why earlier revisions silently swallowed every typing-indicator call. */
    suspend fun sendChatAction(chatId: Long, action: String = "typing"): Boolean =
        call(shortClient, "sendChatAction", buildJsonObject {
            put("chat_id", chatId)
            put("action", action)
        }).jsonPrimitive.boolean

    suspend fun sendPhoto(chatId: Long, file: File, caption: String? = null): JsonObject =
        callMultipart(shortClient, "sendPhoto", chatId, "photo", file, "image/jpeg", caption)

    suspend fun sendDocument(chatId: Long, file: File, caption: String? = null): JsonObject =
        callMultipart(shortClient, "sendDocument", chatId, "document", file, "application/octet-stream", caption)

    /** Telegram returns Bool for setMyCommands / deleteMyCommands. Calling .jsonObject on a
     *  JsonPrimitive throws — that bug surfaced as "issues setting /commands". */
    suspend fun setMyCommands(commands: List<Pair<String, String>>): Boolean =
        call(shortClient, "setMyCommands", buildJsonObject {
            put("commands", buildJsonArray {
                commands.forEach { (cmd, desc) ->
                    addJsonObject {
                        put("command", cmd)
                        put("description", desc)
                    }
                }
            })
        }).jsonPrimitive.boolean

    suspend fun getMyCommands(): JsonArray =
        call(shortClient, "getMyCommands", buildJsonObject {}).jsonArray

    suspend fun deleteMyCommands(): Boolean =
        call(shortClient, "deleteMyCommands", buildJsonObject {}).jsonPrimitive.boolean

    /**
     * Edit the text of a previously sent bot message in place. Returns the message object on
     * success. Treats Telegram's "message is not modified" error as a benign no-op — that
     * happens during streaming when the latest chunk equals the previously rendered text.
     * Returns null on rate-limit / "message_too_long" / network failure so the caller can
     * fall back to deleting + resending as chunks.
     */
    suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        parseMode: String? = null,
        replyMarkup: JsonObject? = null,
    ): JsonObject? = try {
        call(shortClient, "editMessageText", buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
            if (parseMode != null) put("parse_mode", parseMode)
            if (replyMarkup != null) put("reply_markup", replyMarkup)
        }).jsonObject
    } catch (e: TelegramApiException) {
        // 400 with "message is not modified" / "message_too_long" — surface as null so the
        // streaming consumer can decide whether to ignore (no-op) or fall back (overflow).
        null
    }

    suspend fun deleteMessage(chatId: Long, messageId: Long): Boolean = try {
        call(shortClient, "deleteMessage", buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
        }).jsonPrimitive.boolean
    } catch (_: Throwable) {
        false
    }

    /** Resolve a file_id to a downloadable file_path (Telegram returns it under .result.file_path). */
    suspend fun getFile(fileId: String): JsonObject =
        call(shortClient, "getFile", buildJsonObject { put("file_id", fileId) }).jsonObject

    /** Download a Telegram-hosted file by its relative file_path (returned from getFile). */
    suspend fun downloadFile(filePath: String, dest: File): Unit = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/file/bot${tokenProvider()}/$filePath"
        val req = Request.Builder().url(url).get().build()
        try {
            shortClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("downloadFile $filePath -> HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IOException("downloadFile $filePath -> empty body")
                body.byteStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
            }
        } catch (e: IOException) {
            throw redactException(e)
        }
    }

    /* ------------ low-level dispatch ------------ */

    private suspend fun call(client: OkHttpClient, method: String, body: JsonObject): kotlinx.serialization.json.JsonElement {
        val req = Request.Builder()
            .url("${base()}/$method")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val raw = client.newCall(req).awaitString()
        val obj = json.parseToJsonElement(raw).jsonObject
        if (obj["ok"]?.jsonPrimitive?.boolean != true) {
            val code = obj["error_code"]?.jsonPrimitive?.intOrNull ?: -1
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            throw TelegramApiException(code, desc)
        }
        return obj["result"]!!
    }

    private suspend fun callMultipart(
        client: OkHttpClient,
        method: String,
        chatId: Long,
        fieldName: String,
        file: File,
        mime: String,
        caption: String?,
    ): JsonObject {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .also { if (caption != null) it.addFormDataPart("caption", caption) }
            .addFormDataPart(fieldName, file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val req = Request.Builder()
            .url("${base()}/$method")
            .post(multipart)
            .build()
        val raw = client.newCall(req).awaitString()
        val obj = json.parseToJsonElement(raw).jsonObject
        if (obj["ok"]?.jsonPrimitive?.boolean != true) {
            val code = obj["error_code"]?.jsonPrimitive?.intOrNull ?: -1
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            throw TelegramApiException(code, desc)
        }
        return obj["result"]!!.jsonObject
    }

    private suspend fun Call.awaitString(): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Redact the token from any IOException OkHttp surfaces. OkHttp doesn't
                    // typically include the URL in its messages, but a SocketTimeoutException
                    // chain or a debug interceptor could, and the URL contains the bot token.
                    if (cont.isActive) cont.resumeWithException(redactException(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        if (cont.isActive) {
                            try {
                                cont.resume(r.body?.string() ?: "")
                            } catch (e: IOException) {
                                cont.resumeWithException(redactException(e))
                            }
                        }
                    }
                }
            })
            cont.invokeOnCancellation { try { cancel() } catch (_: Throwable) {} }
        }
    }
}

/** Convenience: pull msg id, chat id, sender id, text, and any inbound photo file IDs. */
data class TelegramIncomingMessage(
    val updateId: Long,
    val messageId: Long,
    val chatId: Long,
    val senderId: Long?,
    val text: String,
    val photoFileIds: List<String> = emptyList(),
    /** Non-photo attachments. Populated separately so callers can choose whether to
     *  download them (we currently always do; future surfaces might prefer a metadata-only
     *  prompt instead). */
    val attachments: List<TelegramAttachment> = emptyList(),
)

/** Kind of a non-photo inbound Telegram attachment. */
enum class AttachmentKind { DOCUMENT, AUDIO, VIDEO, VOICE, VIDEO_NOTE }

/** Metadata for a single inbound non-photo attachment. */
data class TelegramAttachment(
    val fileId: String,
    val kind: AttachmentKind,
    val originalFileName: String?,      // null for voice/video_note (Telegram auto-names them)
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationSec: Int?,              // audio / video / voice / video_note only
)

/** Inline-keyboard button tap. We use these for tool-approval prompts only. */
data class TelegramCallbackQuery(
    val updateId: Long,
    val callbackQueryId: String,
    val chatId: Long,
    val messageId: Long,
    val senderId: Long?,
    val data: String,
)

/** Returns the parsed callback_query if [update] is one, else null. */
fun parseCallbackQuery(update: JsonObject): TelegramCallbackQuery? {
    val updateId = update["update_id"]?.jsonPrimitive?.longOrNull ?: return null
    val cq = update["callback_query"]?.jsonObject ?: return null
    val cqId = cq["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val msg = cq["message"]?.jsonObject ?: return null
    val messageId = msg["message_id"]?.jsonPrimitive?.longOrNull ?: return null
    val chatId = msg["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return null
    val senderId = cq["from"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
    val data = cq["data"]?.jsonPrimitive?.contentOrNull ?: ""
    return TelegramCallbackQuery(updateId, cqId, chatId, messageId, senderId, data)
}

fun parseIncoming(update: JsonObject): TelegramIncomingMessage? {
    val updateId = update["update_id"]?.jsonPrimitive?.longOrNull ?: return null
    val msg = update["message"]?.jsonObject ?: return null
    val messageId = msg["message_id"]?.jsonPrimitive?.longOrNull ?: return null
    val chatId = msg["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return null
    val senderId = msg["from"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull

    // Telegram sends photo as an array of PhotoSize entries, ordered smallest -> largest.
    // Take the last entry (highest resolution) from a single inbound photo. Multi-photo
    // messages arrive as separate "media group" updates, so this single-array shape is enough.
    val photoFileIds = msg["photo"]?.jsonArray?.lastOrNull()?.jsonObject
        ?.get("file_id")?.jsonPrimitive?.contentOrNull?.let { listOf(it) }
        ?: emptyList()

    // Extract non-photo attachments (document, audio, video, voice, video_note).
    val attachments = buildList {
        msg["document"]?.jsonObject?.let { doc ->
            val fileId = doc["file_id"]?.jsonPrimitive?.contentOrNull ?: return@let
            add(TelegramAttachment(
                fileId = fileId,
                kind = AttachmentKind.DOCUMENT,
                originalFileName = doc["file_name"]?.jsonPrimitive?.contentOrNull,
                mimeType = doc["mime_type"]?.jsonPrimitive?.contentOrNull,
                sizeBytes = doc["file_size"]?.jsonPrimitive?.longOrNull,
                durationSec = null,
            ))
        }
        msg["audio"]?.jsonObject?.let { audio ->
            val fileId = audio["file_id"]?.jsonPrimitive?.contentOrNull ?: return@let
            add(TelegramAttachment(
                fileId = fileId,
                kind = AttachmentKind.AUDIO,
                originalFileName = audio["file_name"]?.jsonPrimitive?.contentOrNull,
                mimeType = audio["mime_type"]?.jsonPrimitive?.contentOrNull,
                sizeBytes = audio["file_size"]?.jsonPrimitive?.longOrNull,
                durationSec = audio["duration"]?.jsonPrimitive?.intOrNull,
            ))
        }
        msg["video"]?.jsonObject?.let { video ->
            val fileId = video["file_id"]?.jsonPrimitive?.contentOrNull ?: return@let
            add(TelegramAttachment(
                fileId = fileId,
                kind = AttachmentKind.VIDEO,
                originalFileName = video["file_name"]?.jsonPrimitive?.contentOrNull,
                mimeType = video["mime_type"]?.jsonPrimitive?.contentOrNull,
                sizeBytes = video["file_size"]?.jsonPrimitive?.longOrNull,
                durationSec = video["duration"]?.jsonPrimitive?.intOrNull,
            ))
        }
        msg["voice"]?.jsonObject?.let { voice ->
            val fileId = voice["file_id"]?.jsonPrimitive?.contentOrNull ?: return@let
            add(TelegramAttachment(
                fileId = fileId,
                kind = AttachmentKind.VOICE,
                originalFileName = null,  // Telegram does not provide a user-visible name
                mimeType = voice["mime_type"]?.jsonPrimitive?.contentOrNull,
                sizeBytes = voice["file_size"]?.jsonPrimitive?.longOrNull,
                durationSec = voice["duration"]?.jsonPrimitive?.intOrNull,
            ))
        }
        msg["video_note"]?.jsonObject?.let { vn ->
            val fileId = vn["file_id"]?.jsonPrimitive?.contentOrNull ?: return@let
            add(TelegramAttachment(
                fileId = fileId,
                kind = AttachmentKind.VIDEO_NOTE,
                originalFileName = null,
                mimeType = "video/mp4",
                sizeBytes = vn["file_size"]?.jsonPrimitive?.longOrNull,
                durationSec = vn["duration"]?.jsonPrimitive?.intOrNull,
            ))
        }
    }

    // Captions ride alongside photos/documents/etc. Plain-text messages use the "text" field.
    val caption = msg["caption"]?.jsonPrimitive?.contentOrNull
    val plain = msg["text"]?.jsonPrimitive?.contentOrNull
    val text = caption ?: plain ?: ""

    // Drop the update only if there is nothing actionable: no text, no photos, no attachments.
    if (text.isEmpty() && photoFileIds.isEmpty() && attachments.isEmpty()) return null

    return TelegramIncomingMessage(updateId, messageId, chatId, senderId, text, photoFileIds, attachments)
}
