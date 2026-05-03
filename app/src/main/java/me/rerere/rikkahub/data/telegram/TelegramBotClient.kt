package me.rerere.rikkahub.data.telegram

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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val shortClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val pollClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // server-side max 50s + headroom
        .build()

    private fun base() = "https://api.telegram.org/bot${tokenProvider()}"

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
    ): JsonObject = call(shortClient, "sendMessage", buildJsonObject {
        put("chat_id", chatId)
        put("text", text)
        if (parseMode != null) put("parse_mode", parseMode)
        if (replyToMessageId != null) put("reply_to_message_id", replyToMessageId)
        if (disableWebPagePreview) put("disable_web_page_preview", true)
    }).jsonObject

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
    ): JsonObject? = try {
        call(shortClient, "editMessageText", buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
            if (parseMode != null) put("parse_mode", parseMode)
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
        shortClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("downloadFile $filePath -> HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("downloadFile $filePath -> empty body")
            body.byteStream().use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
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
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        if (cont.isActive) {
                            try {
                                cont.resume(r.body?.string() ?: "")
                            } catch (e: IOException) {
                                cont.resumeWithException(e)
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
)

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

    // Captions ride alongside photos. Plain-text messages use the "text" field.
    val caption = msg["caption"]?.jsonPrimitive?.contentOrNull
    val plain = msg["text"]?.jsonPrimitive?.contentOrNull
    val text = caption ?: plain ?: ""

    // Drop the update only if there is nothing actionable: neither text nor a photo.
    if (text.isEmpty() && photoFileIds.isEmpty()) return null

    return TelegramIncomingMessage(updateId, messageId, chatId, senderId, text, photoFileIds)
}
