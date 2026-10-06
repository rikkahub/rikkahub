package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.MediaCreationSession
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import java.io.File
import kotlin.uuid.Uuid

class MediaCreationSessionsVM(
    private val repository: MediaCreationRepository,
    private val service: MediaCreationService,
) : ViewModel() {
    /** 最近用过的排在前面；null 表示还在加载。 */
    val sessions: StateFlow<List<MediaCreationSession>?> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 准备一个空会话交给 [onReady] 打开。 */
    fun newSession(onReady: (Uuid) -> Unit) {
        viewModelScope.launch { onReady(repository.newSession().id) }
    }

    fun resolve(path: String): File = repository.resolve(path)

    fun rename(id: Uuid, title: String) {
        viewModelScope.launch { repository.renameSession(id, title.trim()) }
    }

    fun delete(id: Uuid) {
        viewModelScope.launch { service.deleteSession(id) }
    }
}
