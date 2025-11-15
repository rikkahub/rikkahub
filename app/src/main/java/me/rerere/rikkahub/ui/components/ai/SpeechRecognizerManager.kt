package me.rerere.rikkahub.ui.components.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.operit.api.speech.SpeechService
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.hooks.ChatInputState

/**
 * 手动控制的语音识别管理器
 * 点击开始录音 → 用户说话 → 再次点击停止
 */
@Composable
fun VoiceInputButtonWithSpeechService(
    state: ChatInputState,
    speechService: SpeechService,
    context: Context
) {
    val coroutineScope = rememberCoroutineScope()
    val audioPermission = Manifest.permission.RECORD_AUDIO

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    var isListening by remember { mutableStateOf(false) }

    // 收集识别结果
    LaunchedEffect(speechService) {
        launch {
            speechService.recognitionResultFlow.collect { result ->
                state.textContent.edit { replace(0, length, result.text) }
            }
        }
        launch {
            speechService.recognitionErrorFlow.collect { error ->
                if (error.message.isNotBlank()) {
                    Toast.makeText(context, "识别错误: ${error.message}", Toast.LENGTH_SHORT).show()
                    isListening = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(
                if (isListening) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.primary
            )
            .clickable {
                if (ContextCompat.checkSelfPermission(context, audioPermission)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    isListening = !isListening
                    if (isListening) {
                        coroutineScope.launch {
                            try {
                                speechService.startRecognition(
                                    languageCode = "zh-CN",
                                    continuousMode = true,
                                    partialResults = true
                                )
                            } catch (e: Exception) {
                                isListening = false
                                Toast.makeText(context, "开始识别失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        coroutineScope.launch {
                            try {
                                speechService.stopRecognition()
                            } catch (e: Exception) {
                                Toast.makeText(context, "停止识别失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    launcher.launch(audioPermission)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isListening) "停止语音" else "语音输入",
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}
