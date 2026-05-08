package me.rerere.rikkahub.data.telegram

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.local.InteractiveToolStreamer
import me.rerere.rikkahub.data.repository.TelegramChatRepository
import me.rerere.rikkahub.service.RikkaAccessibilityService
import java.io.File
import java.io.FileOutputStream

private const val TAG = "TelegramInteractiveStreamer"
private const val STREAM_DIR = "interactive-stream"
private const val MAX_CACHED_FILES = 20
private const val MAX_CAPTION_CHARS = 1024

/**
 * [InteractiveToolStreamer] implementation that captures the real device screen via
 * [RikkaAccessibilityService] and sends the PNG to the originating Telegram chat.
 *
 * Flow:
 *  1. Guard: non-headless invocations bail immediately (no-op).
 *  2. Resolve Telegram chat id via [TelegramChatRepository.getByConversationId]. If no
 *     mapping exists (cron / sub-agent without a Telegram origin) bail silently.
 *  3. Capture the screen using the live AccessibilityService. Falls back gracefully when
 *     the service is not bound.
 *  4. Write PNG to [cacheDir]/interactive-stream/, capping the directory at
 *     [MAX_CACHED_FILES] oldest-first to prevent unbounded growth.
 *  5. POST the file to Telegram via [TelegramBotClient.sendPhoto].
 *
 * Every step is best-effort: exceptions are logged and swallowed. A missed screenshot
 * does NOT fail the originating tool.
 */
class TelegramInteractiveToolStreamer(
    private val context: Context,
    private val client: TelegramBotClient,
    private val chatRepo: TelegramChatRepository,
    private val prefs: TelegramBotPreferences,
) : InteractiveToolStreamer {

    override suspend fun streamIfHeadless(
        invocationContext: ToolInvocationContext?,
        actionLabel: String,
    ) {
        // Gate 1: only stream for headless invocations.
        if (invocationContext == null || !invocationContext.isHeadless) return

        // Gate 2: honour the user's `/stream off` toggle. Read every send so the new value
        // takes effect on the next tool firing without restarting the bot.
        val enabled = runCatching { prefs.current().streamScreenshots }.getOrDefault(true)
        if (!enabled) return

        val convId = invocationContext.callerConversationId ?: return

        // Gate 3: resolve the Telegram chat id. Missing mapping = not a Telegram conversation.
        val mapping = runCatching { chatRepo.getByConversationId(convId) }
            .onFailure { Log.w(TAG, "streamIfHeadless: chatRepo lookup failed for $convId", it) }
            .getOrNull() ?: return

        // Step 3: capture the screen.
        val bitmap = captureScreenOrNull() ?: run {
            Log.d(TAG, "streamIfHeadless: no screenshot available (accessibility service not bound or API < 28)")
            return
        }

        // Step 4: persist to cache dir.
        val file = writeToCacheOrNull(bitmap, actionLabel) ?: run {
            bitmap.recycle()
            Log.w(TAG, "streamIfHeadless: failed to write screenshot to cache")
            return
        }
        bitmap.recycle()

        // Step 5: send to Telegram.
        val caption = buildCaption(actionLabel)
        runCatching {
            client.sendPhoto(mapping.chatId, file, caption)
            Log.i(TAG, "streamIfHeadless: posted screenshot ($actionLabel) to chat=${mapping.chatId}")
        }.onFailure { Log.w(TAG, "streamIfHeadless: sendPhoto failed", it) }
    }

    // -----------------------------------------------------------------------------------------
    // Screen capture
    // -----------------------------------------------------------------------------------------

    private suspend fun captureScreenOrNull(): Bitmap? {
        return runCatching {
            val svc = RikkaAccessibilityService.instance ?: return null
            val outcome = svc.captureScreenshot(displayId = 0)
            when (outcome) {
                is RikkaAccessibilityService.ScreenshotOutcome.Success -> outcome.bitmap
                is RikkaAccessibilityService.ScreenshotOutcome.Failure -> {
                    Log.d(TAG, "captureScreenOrNull: capture failed: ${outcome.reason}")
                    null
                }
            }
        }.getOrElse {
            Log.w(TAG, "captureScreenOrNull: exception during capture", it)
            null
        }
    }

    // -----------------------------------------------------------------------------------------
    // Cache write + sweep
    // -----------------------------------------------------------------------------------------

    private fun writeToCacheOrNull(bitmap: Bitmap, label: String): File? {
        return runCatching {
            val dir = File(context.cacheDir, STREAM_DIR).apply { mkdirs() }
            sweepCache(dir)
            val ts = System.currentTimeMillis()
            val safe = label.take(30).replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            val file = File(dir, "${ts}_${safe}.png")
            FileOutputStream(file).use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            file
        }.getOrElse {
            Log.w(TAG, "writeToCacheOrNull: write failed", it)
            null
        }
    }

    /**
     * Keep at most [MAX_CACHED_FILES] files in the interactive-stream cache dir, removing
     * the oldest by last-modified time when the cap is exceeded.
     */
    private fun sweepCache(dir: File) {
        runCatching {
            val files = dir.listFiles() ?: return
            if (files.size <= MAX_CACHED_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_CACHED_FILES)
                .forEach { it.delete() }
        }.onFailure { Log.w(TAG, "sweepCache: sweep failed", it) }
    }

    // -----------------------------------------------------------------------------------------
    // Caption
    // -----------------------------------------------------------------------------------------

    private fun buildCaption(actionLabel: String): String {
        return if (actionLabel.length <= MAX_CAPTION_CHARS) actionLabel
        else actionLabel.substring(0, MAX_CAPTION_CHARS - 1) + "…"
    }
}
