package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.runtimeRegexes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    private data class MemorySubscription(
        val id: Uuid,
        val useGlobalMemory: Boolean,
    )

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    @OptIn(ExperimentalCoroutinesApi::class)
    val assistantMemoryCounts: StateFlow<Map<Uuid, Int>> = settingsStore.settingsFlow
        .map { settings ->
            settings.assistants
                .filter { it.enableMemory }
                .map { assistant ->
                    MemorySubscription(
                        id = assistant.id,
                        useGlobalMemory = assistant.useGlobalMemory,
                    )
                }
        }
        .distinctUntilChanged()
        .flatMapLatest { subscriptions ->
            if (subscriptions.isEmpty()) {
                flowOf(emptyMap<Uuid, Int>())
            } else {
                val globalUsers = subscriptions.filter { it.useGlobalMemory }
                val localUsers = subscriptions.filterNot { it.useGlobalMemory }
                val countFlows = buildList<Flow<Map<Uuid, Int>>> {
                    if (globalUsers.isNotEmpty()) {
                        add(
                            memoryRepository.getGlobalMemoriesFlow().map { memories ->
                                globalUsers.associate { subscription ->
                                    subscription.id to memories.size
                                }
                            }
                        )
                    }
                    localUsers.forEach { subscription ->
                        add(
                            memoryRepository.getMemoriesOfAssistantFlow(subscription.id.toString()).map { memories ->
                                mapOf(subscription.id to memories.size)
                            }
                        )
                    }
                }
                combine(countFlows) { partials: Array<Map<Uuid, Int>> ->
                    partials.fold(emptyMap<Uuid, Int>()) { acc, partial ->
                        acc + partial
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun addAssistant(assistant: Assistant) {
        addAssistantWithLorebooks(assistant = assistant, lorebooks = emptyList())
    }

    fun addAssistantWithLorebooks(
        assistant: Assistant,
        lorebooks: List<Lorebook>,
        sharedRegexes: List<AssistantRegex> = settings.value.runtimeRegexes(),
    ) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(assistant),
                    lorebooks = settings.lorebooks + lorebooks,
                    regexes = sharedRegexes,
                )
            )
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            cleanupAssistantFiles(assistant)

            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.filter { it.id != assistant.id }
                )
            )
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(copiedAssistant)
                )
            )
        }
    }
}
