package me.rerere.rikkahub.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.Instant

sealed interface SearchResult {
    val conversationId: String
    val title: String
    val updateAt: Instant

    data class Title(
        override val conversationId: String,
        override val title: String,
        override val updateAt: Instant,
    ) : SearchResult

    data class Message(
        val nodeId: String,
        val messageId: String,
        override val conversationId: String,
        override val title: String,
        override val updateAt: Instant,
        val snippet: String,
    ) : SearchResult
}

class SearchVM(
    private val conversationRepo: ConversationRepository,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")

    var searchQuery by mutableStateOf("")
        private set
    var results by mutableStateOf<List<SearchResult>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isRebuilding by mutableStateOf(false)
        private set
    var rebuildProgress by mutableStateOf(0 to 0)
        private set

    init {
        viewModelScope.launch {
            _searchQuery
                .debounce(300L)
                .collectLatest { query -> performSearch(query) }
        }
    }

    fun onQueryChange(query: String) {
        searchQuery = query
        _searchQuery.value = query
    }

    fun search() {
        viewModelScope.launch {
            performSearch(searchQuery)
        }
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            isRebuilding = true
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
            } finally {
                isRebuilding = false
            }
        }
    }

    private suspend fun performSearch(query: String) {
        if (query.isBlank()) {
            results = emptyList()
            return
        }
        isLoading = true
        try {
            results = coroutineScope {
                val titleResults = async {
                    conversationRepo.searchConversationTitles(query).map { it.toSearchResult() }
                }
                val messageResults = async {
                    conversationRepo.searchMessages(query).map { it.toSearchResult() }
                }
                titleResults.await() + messageResults.await()
            }
        } finally {
            isLoading = false
        }
    }
}

private fun Conversation.toSearchResult() = SearchResult.Title(
    conversationId = id.toString(),
    title = title,
    updateAt = updateAt,
)

private fun MessageSearchResult.toSearchResult() = SearchResult.Message(
    nodeId = nodeId,
    messageId = messageId,
    conversationId = conversationId,
    title = title,
    updateAt = updateAt,
    snippet = snippet,
)
