package me.rerere.rikkahub.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FtsConsistencyResult
import me.rerere.rikkahub.utils.launchVm
import me.rerere.rikkahub.utils.shouldRethrowVmError

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
    var ftsConsistency by mutableStateOf<FtsConsistencyResult?>(null)
        private set
    var isCheckingConsistency by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<Throwable?>(null)
        private set

    fun clearError() {
        searchError = null
    }

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class) // debounce: deliberate reliance on the preview operator for search throttling
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
        launchVm(onError = { searchError = it }) {
            performSearch(searchQuery)
        }
    }

    fun rebuildIndex() {
        launchVm(onError = { searchError = it }) {
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

    fun checkConsistency() {
        launchVm(onError = { searchError = it }) {
            isCheckingConsistency = true
            try {
                ftsConsistency = conversationRepo.checkFtsConsistency()
            } finally {
                isCheckingConsistency = false
            }
        }
    }

    private suspend fun performSearch(query: String) {
        searchError = null
        if (query.isBlank()) {
            results = emptyList()
            return
        }
        isLoading = true
        try {
            results = conversationRepo.searchMessages(query)
        } catch (t: Throwable) {
            // Report the failure without letting it escape the debounce collector — a single failed
            // query must not kill the long-lived collectLatest, otherwise the next query is dead.
            if (shouldRethrowVmError(t)) throw t
            searchError = t
        } finally {
            isLoading = false
        }
    }
}
