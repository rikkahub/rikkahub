package me.rerere.rikkahub.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

internal fun backgroundGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
) = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

class ConversationPostProcessor(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val runtimeStore: ConversationRuntimeStore,
) {
    suspend fun generateTitle(conversationId: Uuid, force: Boolean = false) {
        val conversation = runtimeStore.require(conversationId).conversation
        if (!force && conversation.title.isNotBlank()) return

        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
            ?: throw configurationFailure(conversationId, "Title model not found")
        val provider = model.findProvider(settings.providers)
            ?: throw configurationFailure(conversationId, "Title provider not found")
        val result = providerManager.getProviderByType(provider).generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    settings.titlePrompt.applyPlaceholders(
                        "locale" to Locale.getDefault().displayName,
                        "content" to conversation.currentMessages.takeLast(4)
                            .joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                    )
                )
            ),
            params = backgroundGenerationParams(model),
        )
        val title = result.message.toText().trim()
        if (title.isNotBlank()) {
            runtimeStore.mutate(conversationId, persist = true) { latest -> latest.copy(title = title) }
        }
    }

    suspend fun generateSuggestions(conversationId: Uuid) {
        val settings = settingsStore.settingsFlow.value
        if (!settings.enableSuggestion) return
        val conversation = runtimeStore.require(conversationId).conversation
        val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId)
            ?: throw configurationFailure(conversationId, "Suggestion model not found")
        val provider = model.findProvider(settings.providers)
            ?: throw configurationFailure(conversationId, "Suggestion provider not found")
        val result = providerManager.getProviderByType(provider).generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    settings.suggestionPrompt.applyPlaceholders(
                        "locale" to Locale.getDefault().displayName,
                        "content" to conversation.currentMessages.takeLast(8)
                            .joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                    )
                )
            ),
            params = backgroundGenerationParams(model),
        )
        val suggestions = result.message.toText().lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(10)
            .toList()
        runtimeStore.mutate(conversationId, persist = true) { latest ->
            latest.copy(chatSuggestions = suggestions)
        }
    }

    suspend fun compress(
        conversationId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
    ) {
        val conversation = runtimeStore.require(conversationId).conversation
        if (runtimeStore.require(conversationId).generation.isBusy) {
            throw ChatCommandException(
                ChatFailure(
                    code = ChatFailureCode.Conflict,
                    message = "Cannot compress while generation is active",
                    conversationId = conversationId,
                )
            )
        }
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw configurationFailure(conversationId, "Compression model not found")
        val provider = model.findProvider(settings.providers)
            ?: throw configurationFailure(conversationId, "Compression provider not found")
        val providerHandler = providerManager.getProviderByType(provider)

        val allMessages = conversation.currentMessages
        val (messagesToCompress, messagesToKeep) = when {
            keepRecentMessages > 0 && allMessages.size > keepRecentMessages ->
                allMessages.dropLast(keepRecentMessages) to allMessages.takeLast(keepRecentMessages)

            keepRecentMessages > 0 -> throw ChatCommandException(
                ChatFailure(
                    code = ChatFailureCode.InvalidRequest,
                    message = "Not enough messages to compress",
                    conversationId = conversationId,
                )
            )

            else -> allMessages to emptyList()
        }

        suspend fun compressChunk(messages: List<UIMessage>): String {
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) },
                "target_tokens" to targetTokens.toString(),
                "additional_context" to additionalPrompt.takeIf(String::isNotBlank)
                    ?.let { "Additional instructions from user: $it" }.orEmpty(),
                "locale" to Locale.getDefault().displayName,
            )
            return providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundGenerationParams(model),
            ).message.toText().trim().ifBlank {
                throw IllegalStateException("Failed to generate compressed summary")
            }
        }

        fun split(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= 256) return listOf(messages)
            val middle = messages.size / 2
            return split(messages.subList(0, middle)) + split(messages.subList(middle, messages.size))
        }

        val summaries = coroutineScope {
            split(messagesToCompress).map { chunk -> async { compressChunk(chunk) } }.awaitAll()
        }
        runtimeStore.mutate(conversationId, requireIdle = true, persist = true) { latest ->
            latest.copy(
                messageNodes = summaries.map { UIMessage.user(it).toMessageNode() } +
                    messagesToKeep.map(UIMessage::toMessageNode),
                chatSuggestions = emptyList(),
            )
        }
    }

    private fun configurationFailure(conversationId: Uuid, message: String) = ChatCommandException(
        ChatFailure(
            code = ChatFailureCode.Configuration,
            message = message,
            conversationId = conversationId,
        )
    )
}
