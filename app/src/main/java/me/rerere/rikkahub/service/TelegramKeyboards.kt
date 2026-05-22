package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.telegram.TelegramHtmlRenderer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Inline-keyboard primitives for the Telegram bot — tool-approval cards, the two-step
 * /model picker, and the registries that map UUIDs to short callback_data tokens (since
 * Telegram caps callback_data at 64 bytes).
 *
 * Lifecycle: every function here is pure (no service state). The state-bearing pieces —
 * sendApprovalPrompt, handleProviderPickCallback, handleModelPickCallback — remain on
 * TelegramBotService because they mutate settings, edit messages, and coordinate with
 * the chat service.
 */

/** Inline-keyboard callback_data prefix and per-scope discriminators for tool-
 *  approval prompts. Telegram caps callback_data at 64 bytes; "apv:N:<uuid>" is
 *  4 + 36 = 40 bytes, comfortably under. */
const val APPROVAL_CB_PREFIX: String = "apv:"
const val APPROVAL_CB_ONCE: String = "1"
const val APPROVAL_CB_CHAT: String = "2"
const val APPROVAL_CB_ALWAYS: String = "3"
const val APPROVAL_CB_DENY: String = "4"

/** Inline-keyboard prefix for /model interactive picker. callback_data is
 *  "mdl:<short-token>" where the token is a numeric handle into ModelPickRegistry —
 *  some provider model_ids are too long to fit Telegram's 64-byte cap directly. */
const val MODEL_CB_PREFIX: String = "mdl:"

/** Inline-keyboard prefix for the /model provider step (two-step picker). callback_data
 *  is "mdp:<short-token>" → ProviderPickRegistry resolves to a provider id. A trailing
 *  "mdp:back" entry re-shows the provider step from the model step. The two-step layout
 *  exists because users with many models per provider blew past Telegram's per-message
 *  inline-keyboard cap (#1) and got no response at all. */
const val PROVIDER_CB_PREFIX: String = "mdp:"
const val PROVIDER_CB_BACK: String = "mdp:back"

/** How many models to show per page in the /model picker. Issue #1 escalation:
 *  one user reported a provider with ~256 models was rendered as a 30-row vertical
 *  wall (the prior `MODEL_PICKER_BUTTON_CAP` truncated to 30 with no way to see
 *  the rest from inside Telegram). 10 per page keeps the keyboard short and adds
 *  prev/next navigation that scales to arbitrary model counts. */
const val MODEL_PICKER_PAGE_SIZE: Int = 10

/**
 * Maximum characters of provider/model display text that we render inside one inline-
 * keyboard button label. The Bot API doesn't publish a per-button text cap, but a very
 * long user-set name causes Telegram to silently drop the WHOLE keyboard, leaving the
 * user with a message and no buttons. 40 chars + checkmark/circle marker fits comfortably
 * on a phone keyboard row. Strings longer than this are tail-elided with "…".
 */
private const val BUTTON_TEXT_MAX_CHARS = 40

/** Truncate [s] to [BUTTON_TEXT_MAX_CHARS] with a trailing ellipsis when needed. */
private fun clampForButton(s: String): String =
    if (s.length <= BUTTON_TEXT_MAX_CHARS) s
    else s.take(BUTTON_TEXT_MAX_CHARS - 1) + "…"

/**
 * Process-scoped registry mapping short numeric tokens to full model IDs. The
 * /model picker registers each visible button's model id under a fresh token, and
 * the callback handler resolves the token back. We can't put the model_id straight
 * into callback_data because Telegram caps it at 64 bytes and some provider model
 * IDs exceed the budget when combined with the prefix. Reset on every /model call.
 */
internal object ModelPickRegistry {
    private val byToken = ConcurrentHashMap<String, String>()
    private val nextId = AtomicInteger(0)
    fun register(modelId: String): String {
        val token = nextId.incrementAndGet().toString()
        byToken[token] = modelId
        return token
    }
    fun resolve(token: String): String? = byToken[token]
    fun clear() { byToken.clear() }
}

/**
 * Process-scoped registry mapping short numeric tokens to provider IDs for the
 * /model two-step picker. Same shape and rationale as ModelPickRegistry — provider
 * IDs are UUIDs and would overflow Telegram's 64-byte callback_data cap when
 * combined with the prefix. Reset on every fresh /model invocation.
 */
internal object ProviderPickRegistry {
    private val byToken = ConcurrentHashMap<String, String>()
    private val nextId = AtomicInteger(0)
    fun register(providerId: String): String {
        val token = nextId.incrementAndGet().toString()
        byToken[token] = providerId
        return token
    }
    fun resolve(token: String): String? = byToken[token]
    fun clear() { byToken.clear() }
}

/**
 * Build the tool-approval inline keyboard. Two rows:
 *  - Row 1: ✅ Allow (always) + ∞ Always Allow (only when ToolApprovalDefaults
 *    permits — privilege-escalation surfaces like mcp_add and skill_install_* opt out
 *    of the always-allow path).
 *  - Row 2: 💬 Allow for this chat + ❌ Deny.
 */
internal fun buildApprovalKeyboard(toolCallId: String, toolName: String? = null): JsonObject = buildJsonObject {
    val allowAlways = toolName == null ||
        me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.allowsAlwaysAllow(toolName)
    put("inline_keyboard", buildJsonArray {
        // Row 1: positive scopes
        addJsonArray {
            addJsonObject {
                put("text", "✅ Allow")
                put("callback_data", "$APPROVAL_CB_PREFIX${APPROVAL_CB_ONCE}:$toolCallId")
            }
            if (allowAlways) {
                addJsonObject {
                    put("text", "∞ Always Allow")
                    put("callback_data", "$APPROVAL_CB_PREFIX${APPROVAL_CB_ALWAYS}:$toolCallId")
                }
            }
        }
        // Row 2: chat-scope + deny
        addJsonArray {
            addJsonObject {
                put("text", "💬 Allow for this chat")
                put("callback_data", "$APPROVAL_CB_PREFIX${APPROVAL_CB_CHAT}:$toolCallId")
            }
            addJsonObject {
                put("text", "❌ Deny")
                put("callback_data", "$APPROVAL_CB_PREFIX${APPROVAL_CB_DENY}:$toolCallId")
            }
        }
    })
}

/**
 * Build the inline keyboard for the /model interactive picker — step 2 of the
 * two-step flow (or the only step when only one provider is enabled). One button
 * per model, one button per row, paginated at MODEL_PICKER_PAGE_SIZE.
 *
 * Telegram caps callback_data at 64 bytes; provider/model UUIDs would overflow with
 * the prefix, so ModelPickRegistry / ProviderPickRegistry map them to short tokens.
 * Caller manages registry lifetime: re-clear ModelPickRegistry between renders of
 * different pages so stale model tokens from a prior page can't fire; ProviderPick
 * tokens stay valid through the whole picker session so back/prev/next all resolve.
 *
 * Pagination row (when totalPages > 1) reuses the PROVIDER_CB_PREFIX with the form
 * `mdp:<provider-token>:<page>` — handleProviderPickCallback parses both legacy
 * `mdp:<token>` (page 0) and the paged form.
 */
internal fun buildModelKeyboard(
    allModels: List<Pair<ProviderSetting, Model>>,
    page: Int,
    providerToken: String,
    currentModelId: Uuid?,
    showBackButton: Boolean,
): JsonObject {
    val pageStart = page * MODEL_PICKER_PAGE_SIZE
    val pageSlice = allModels.drop(pageStart).take(MODEL_PICKER_PAGE_SIZE)
    val hasPrev = page > 0
    val hasNext = pageStart + MODEL_PICKER_PAGE_SIZE < allModels.size
    return buildJsonObject {
        put("inline_keyboard", buildJsonArray {
            pageSlice.forEach { (_, model) ->
                val name = clampForButton(model.displayName.ifBlank { model.modelId })
                val marker = if (model.id == currentModelId) "✅" else "◯"
                val token = ModelPickRegistry.register(model.id.toString())
                addJsonArray {
                    addJsonObject {
                        put("text", "$marker $name")
                        put("callback_data", "$MODEL_CB_PREFIX$token")
                    }
                }
            }
            if (hasPrev || hasNext) {
                // Prev + Next on the SAME row so the keyboard stays compact even on
                // small phone screens; absent buttons are simply omitted (Telegram
                // renders the surviving button(s) full-width).
                addJsonArray {
                    if (hasPrev) {
                        addJsonObject {
                            put("text", "← Prev")
                            put("callback_data", "$PROVIDER_CB_PREFIX$providerToken:${page - 1}")
                        }
                    }
                    if (hasNext) {
                        addJsonObject {
                            put("text", "Next →")
                            put("callback_data", "$PROVIDER_CB_PREFIX$providerToken:${page + 1}")
                        }
                    }
                }
            }
            if (showBackButton) {
                addJsonArray {
                    addJsonObject {
                        put("text", "← Back to providers")
                        put("callback_data", PROVIDER_CB_BACK)
                    }
                }
            }
        })
    }
}

/** Build the "Models in <provider> — page X/Y — tap to switch:" header. Page count
 *  is suppressed when totalPages == 1 so users with small model lists don't see
 *  noise. */
internal fun buildModelPickerText(
    currentHeader: String,
    providerName: String?,  // null in single-provider mode (header doesn't repeat the name)
    modelCount: Int,
    page: Int,
): String {
    val totalPages = maxOf(1, (modelCount + MODEL_PICKER_PAGE_SIZE - 1) / MODEL_PICKER_PAGE_SIZE)
    return buildString {
        append(currentHeader)
        if (providerName != null) {
            append("Models in <b>")
            append(TelegramHtmlRenderer.escape(providerName))
            append("</b>")
        } else {
            append("Tap to switch")
        }
        if (totalPages > 1) {
            append(" — page ")
            append(page + 1)
            append("/")
            append(totalPages)
        }
        append(":")
    }
}

/**
 * Build the step-1 keyboard for the two-step /model picker — one button per
 * enabled chat-model-bearing provider. Tapping fires PROVIDER_CB_PREFIX + token.
 * Same registry/token rationale as [buildModelKeyboard]: provider IDs are UUIDs
 * and would overflow callback_data when combined with the prefix.
 */
internal fun buildProviderKeyboard(
    providers: List<ProviderSetting>,
    currentProviderId: Uuid?,
): JsonObject {
    return buildJsonObject {
        put("inline_keyboard", buildJsonArray {
            providers.forEach { p ->
                val marker = if (p.id == currentProviderId) "✅" else "◯"
                val token = ProviderPickRegistry.register(p.id.toString())
                val chatModelCount = p.models.count { it.type == ModelType.CHAT }
                // Clamp the provider name only, not the count suffix — a clamped name
                // followed by " (12)" stays under the per-button cap even if the user
                // gave the provider an absurdly long name.
                val clampedName = clampForButton(p.name)
                addJsonArray {
                    addJsonObject {
                        put("text", "$marker $clampedName ($chatModelCount)")
                        put("callback_data", "$PROVIDER_CB_PREFIX$token")
                    }
                }
            }
        })
    }
}
