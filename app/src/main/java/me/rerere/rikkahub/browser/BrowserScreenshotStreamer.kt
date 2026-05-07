package me.rerere.rikkahub.browser

/**
 * Pass 3 outbound hook the [BrowserController] uses to push a screenshot + caption into
 * the calling chat after every state-changing tool in headless mode.
 *
 * Decoupling this behind an interface lets the controller (which lives in the `browser`
 * package, no awareness of Telegram or any other transport) dispatch into a
 * Telegram-specific implementation registered via Koin. The same interface can be reused
 * by future cron / sub-agent surfaces that want their own screenshot stream.
 *
 * Implementations:
 *  - [me.rerere.rikkahub.data.telegram.TelegramBrowserScreenshotStreamer]: looks up the
 *    Telegram chat id bound to [callerConvId] via TelegramChatRepository and posts the
 *    screenshot file + URL caption via TelegramBotClient.sendPhoto.
 *  - [NoOp]: no-op default — used in JVM unit tests and any context where Koin hasn't
 *    registered a real streamer.
 *
 * Wiring contract:
 *  - The controller resolves this interface lazily via
 *    `KoinJavaComponent.getKoin().getOrNull<BrowserScreenshotStreamer>()` so we don't
 *    introduce a constructor cycle through TelegramBotService → LocalTools → controller.
 *  - The implementation MUST be best-effort: a missing chat mapping, a Telegram outage,
 *    or any other transport failure MUST NOT throw. Log and return.
 */
interface BrowserScreenshotStreamer {

    /**
     * Send [screenshotPath] (an absolute file path to a PNG on disk) into the chat
     * identified by [callerConvId] with a one-line caption derived from [actionLabel] and
     * the destination [currentUrl]. Best-effort — implementations log failures and return.
     *
     * Implementations should NOT delete the file after sending; the [BrowserController]
     * writes screenshots to a cache subdir that is swept by the OS (and the user's manual
     * "Clear cache" Doctor row).
     */
    suspend fun send(
        callerConvId: String,
        screenshotPath: String,
        actionLabel: String,
        currentUrl: String?,
    )

    /**
     * Default implementation used when no real streamer is registered (JVM tests, or a
     * device build that has Telegram disabled). Eats the call without ceremony.
     */
    object NoOp : BrowserScreenshotStreamer {
        override suspend fun send(
            callerConvId: String,
            screenshotPath: String,
            actionLabel: String,
            currentUrl: String?,
        ) {
            // intentional no-op
        }
    }
}
