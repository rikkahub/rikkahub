package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.extractQuotedContentAsText

@Composable
fun TTSAutoPlay(
    generationDoneFlow: Flow<kotlin.uuid.Uuid>,
    setting: Settings,
    lastAssistantMessage: UIMessage?,
) {
    // Auto-play TTS after generation completes
    val tts = LocalTTSState.current
    val updatedSetting by rememberUpdatedState(setting)
    val updatedLastAssistantMessage by rememberUpdatedState(lastAssistantMessage)
    LaunchedEffect(Unit) {
        generationDoneFlow.collect {
            if (updatedSetting.displaySetting.autoPlayTTSAfterGeneration) {
                val lastMessage = updatedLastAssistantMessage
                if (lastMessage != null) {
                    val text = lastMessage.toText()
                    val textToSpeak = if (updatedSetting.displaySetting.ttsOnlyReadQuoted) {
                        text.extractQuotedContentAsText() ?: text
                    } else {
                        text
                    }
                    if (textToSpeak.isNotBlank()) {
                        tts.speak(textToSpeak)
                    }
                }
            }
        }
    }
}
