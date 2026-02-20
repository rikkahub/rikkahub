package me.rerere.rikkahub.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.repository.ConversationRepository

class SearchVM(
    private val conversationRepo: ConversationRepository,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")

    var searchQuery by mutableStateOf("")
        private set
    var results by mutableStateOf<List<MessageSearchResult>>(emptyList())
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
            results = conversationRepo.searchMessages(query)
        } finally {
            isLoading = false
        }
    }
}
