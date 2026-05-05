package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Process-scoped registry for per-conversation system-prompt addenda — text the host
 * surface (Telegram bot, in-app, web API, cron worker) wants the model to see but does
 * NOT belong in the user message.
 *
 * Why this exists: [TelegramBotService.handleIncoming] used to prepend a `[agent_context …]`
 * block to every inbound user text. That worked, but it persisted the preamble inside
 * the user's `UIMessagePart.Text` — so the preamble was re-sent verbatim to the provider
 * on every subsequent turn AND every agentic-loop step. A 20-turn chat sent ~20 copies
 * of the same ~80-token framing, polluting context and burning tokens.
 *
 * The system prompt, by contrast, is rebuilt fresh per generation from
 * [GenerationHandler.generateInternal] and never accumulates. Runtime hints belong there.
 *
 * Lifecycle: writers `set` before triggering a generation; readers (GenerationHandler)
 * `get` during system-prompt construction. Process-only, no persistence — if the app
 * restarts, the next inbound message rewrites it. Cleared on `/new` and on conversation
 * reset so a fresh chat doesn't inherit a stale preamble.
 */
object ConversationSystemAddendum {

    private val byConv = ConcurrentHashMap<Uuid, String>()

    /** Set the addendum for [conversationId]. Blank values are treated as a clear. */
    fun set(conversationId: Uuid, addendum: String) {
        if (addendum.isBlank()) byConv.remove(conversationId)
        else byConv[conversationId] = addendum
    }

    /** Read the current addendum, or null if none is registered. */
    fun get(conversationId: Uuid): String? = byConv[conversationId]

    /** Drop the addendum for [conversationId]. Called on /new + conversation reset paths. */
    fun clear(conversationId: Uuid) {
        byConv.remove(conversationId)
    }
}
