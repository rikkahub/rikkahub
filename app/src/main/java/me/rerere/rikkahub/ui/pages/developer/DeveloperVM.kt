package me.rerere.rikkahub.ui.pages.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.datastore.AiLogLevel

class DeveloperVM(
    private val aiLoggingManager: AILoggingManager,
) : ViewModel() {
    val logs = aiLoggingManager.getLogs()
    val logLevel = aiLoggingManager.getLogLevel()

    fun setLogLevel(level: AiLogLevel) {
        viewModelScope.launch {
            aiLoggingManager.setLogLevel(level)
        }
    }
}
