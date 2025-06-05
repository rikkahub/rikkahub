package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.R
import java.io.OutputStream
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.mcp.McpManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val conversationRepository: ConversationRepository
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun exportConversations(context: Context, uri: Uri?) {
        viewModelScope.launch {
            try {
                if (uri == null) {
                    Toast.makeText(context, context.getString(R.string.export_conversations_uri_null), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val conversations = conversationRepository.getAllConversations().first()
                val jsonString = Json.encodeToString(conversations)
                Log.d("Export", "JSON: $jsonString") // Log the JSON string for debugging

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonString.toByteArray())
                    Toast.makeText(context, context.getString(R.string.export_conversations_success, uri.path), Toast.LENGTH_LONG).show()
                } ?: run {
                    Toast.makeText(context, "Failed to open output stream for ${uri.path}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("Export", "Export failed", e)
                Toast.makeText(context, context.getString(R.string.export_conversations_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
}