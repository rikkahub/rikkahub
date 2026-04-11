package com.hefengfan.hffchat.ui.pages.developer

import androidx.lifecycle.ViewModel
import com.hefengfan.hffchat.data.ai.AILoggingManager

class DeveloperVM(
    private val aiLoggingManager: AILoggingManager
) : ViewModel() {
    val logs = aiLoggingManager.getLogs()
}
