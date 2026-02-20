package me.rerere.rikkahub.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.repository.ConversationRepository

class SearchVM(
    private val conversationRepo: ConversationRepository,
) : ViewModel() {
    var searchQuery by mutableStateOf("")
        private set
    var results by mutableStateOf<List<MessageSearchResult>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set

    fun onQueryChange(query: String) {
        searchQuery = query
    }

    fun search() {
        viewModelScope.launch {
            if (searchQuery.isBlank()) {
                results = emptyList()
                return@launch
            }
            isLoading = true
            try {
                results = conversationRepo.searchMessages(searchQuery)
            } finally {
                isLoading = false
            }
        }
    }
}
