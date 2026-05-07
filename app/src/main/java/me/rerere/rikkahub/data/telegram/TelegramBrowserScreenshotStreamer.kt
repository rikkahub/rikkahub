package me.rerere.rikkahub.data.telegram

import android.util.Log
import me.rerere.rikkahub.browser.BrowserScreenshotStreamer
import me.rerere.rikkahub.data.repository.TelegramChatRepository
import java.io.File

private const val TAG = "TelegramBrowserStreamer"
private const val MAX_CAPTION_CHARS = 1024  // Telegram's hard photo-caption cap

/**
 * Pass 3 implementation of [BrowserScreenshotStreamer] for the Telegram bot path.
 *
 * Translation flow:
 *   1. The browser tool wraps up its action (click, type, submit, ...) and calls
 *      [me.rerere.rikkahub.browser.BrowserController.streamScreenshotIfHeadless].
 *   2. The controller writes the PNG to its `browser-stream/` cache subdir and resolves
 *      this streamer via Koin.
 *   3. We map the conversation id -> Telegram chat id via [TelegramChatRepository].
 *   4. [TelegramBotClient.sendPhoto] uploads the file with a one-line caption.
 *
 * Best-effort: every step that can fail (no chat mapping, sendPhoto throws, file gone) is
 * logged and swallowed. The user-visible cost of a missed screenshot is one less push
 * into the chat — not a tool error, not a stack trace, not a crashed FGS.
 */
class TelegramBrowserScreenshotStreamer(
    private val client: TelegramBotClient,
    private val chatRepo: TelegramChatRepository,
) : BrowserScreenshotStreamer {

    override suspend fun send(
        callerConvId: String,
        screenshotPath: String,
        actionLabel: String,
        currentUrl: String?,
    ) {
        // Resolve the Telegram chat id from the conversation id. If the mapping doesn't
        // exist the convId came from cron / sub-agent / external automation — not a
        // Telegram conversation — and there's nothing to send. That's not an error; just
        // the wrong streamer.
        val mapping = runCatching { chatRepo.getByConversationId(callerConvId) }
            .onFailure { Log.w(TAG, "send: chatRepo.getByConversationId failed for $callerConvId", it) }
            .getOrNull() ?: return

        val file = File(screenshotPath)
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "send: screenshot file does not exist: $screenshotPath")
            return
        }

        val caption = buildCaption(actionLabel, currentUrl)
        runCatching {
            client.sendPhoto(mapping.chatId, file, caption)
            Log.i(TAG, "send: posted browser screenshot ($actionLabel) to chat=${mapping.chatId} url=$currentUrl")
        }.onFailure { Log.w(TAG, "send: sendPhoto failed", it) }
    }

    private fun buildCaption(actionLabel: String, currentUrl: String?): String {
        // Two lines, capped at Telegram's caption limit. The "Now on:" prefix mirrors the
        // spec's wording so the user sees a consistent format across foreground / headless.
        val urlLine = currentUrl?.takeIf { it.isNotBlank() }?.let { "Now on: $it" }.orEmpty()
        val raw = if (urlLine.isEmpty()) actionLabel else "$actionLabel\n$urlLine"
        return if (raw.length <= MAX_CAPTION_CHARS) raw else raw.substring(0, MAX_CAPTION_CHARS - 1) + "…"
    }
}
