package me.rerere.rikkahub.web.routes

import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ConversationListInvalidateEvent
import me.rerere.rikkahub.web.dto.FolderListEvent
import me.rerere.rikkahub.web.dto.toDto
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Multiplexed server-sent events stream.
 *
 * A single `/api/events` connection carries several event types, distinguished by the
 * SSE `event:` field, so new event kinds can be added without opening a new connection:
 *  - `settings`                     -> full Settings snapshot
 *  - `conversation_list_invalidate` -> the conversation list for an assistant changed
 *  - `folders`                      -> the folder list for an assistant changed
 *
 * Per-conversation streaming (generation updates) keeps its own dedicated connection
 * at `/api/conversations/{id}/stream`.
 */
fun Route.eventsRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
) {
    sse("/events") {
        heartbeat {
            period = 15.seconds
        }

        // Full settings snapshot; StateFlow emits the current value immediately on connect.
        val settingsEvents = settingsStore.settingsFlow.map { settings ->
            EventPayload(event = "settings", json = JsonInstant.encodeToString(settings))
        }

        // Conversation list invalidation, scoped to the currently selected assistant.
        val conversationListEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                combine(
                    conversationRepo.getConversationsOfAssistant(assistantId),
                    chatService.getConversationJobs()
                ) { conversations, generationJobs ->
                    conversationListFingerprint(conversations, generationJobs.keys)
                }
                    .distinctUntilChanged()
                    .map { assistantId }
            }
            .map { assistantId ->
                EventPayload(
                    event = "conversation_list_invalidate",
                    json = JsonInstant.encodeToString(
                        ConversationListInvalidateEvent(
                            assistantId = assistantId.toString(),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                )
            }

        // Folder list for the currently selected assistant (Room flow emits on any change).
        val folderEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                folderRepo.getFoldersOfAssistant(assistantId).map { folders ->
                    EventPayload(
                        event = "folders",
                        json = JsonInstant.encodeToString(
                            FolderListEvent(
                                assistantId = assistantId.toString(),
                                folders = folders.map { it.toDto() }
                            )
                        )
                    )
                }
            }

        merge(settingsEvents, conversationListEvents, folderEvents).collect { payload ->
            send(data = payload.json, event = payload.event)
        }
    }
}

private data class EventPayload(
    val event: String,
    val json: String,
)

internal fun conversationListFingerprint(
    conversations: List<Conversation>,
    generatingConversationIds: Set<Uuid>,
): List<ConversationListItemFingerprint> = conversations.map { conversation ->
    ConversationListItemFingerprint(
        id = conversation.id,
        assistantId = conversation.assistantId,
        title = conversation.title,
        isPinned = conversation.isPinned,
        folderId = conversation.folderId,
        createAtMillis = conversation.createAt.toEpochMilli(),
        updateAtMillis = conversation.updateAt.toEpochMilli(),
        isGenerating = conversation.id in generatingConversationIds,
    )
}

internal data class ConversationListItemFingerprint(
    val id: Uuid,
    val assistantId: Uuid,
    val title: String,
    val isPinned: Boolean,
    val folderId: Uuid?,
    val createAtMillis: Long,
    val updateAtMillis: Long,
    val isGenerating: Boolean,
)
