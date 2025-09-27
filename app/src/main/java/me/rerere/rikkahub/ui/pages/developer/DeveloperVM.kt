package me.rerere.rikkahub.ui.pages.developer

import androidx.lifecycle.ViewModel
import me.rerere.rikkahub.data.ai.AILoggingManager

class DeveloperVM(
    private val aiLoggingManager: AILoggingManager
) : ViewModel() {
    val logs = aiLoggingManager.getLogs()
}
