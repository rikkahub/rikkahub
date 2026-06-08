package me.rerere.rikkahub.voiceagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun VoiceAgentRoute(conversationId: Uuid) {
    val navController = LocalNavController.current
    val settingsStore = koinInject<SettingsStore>()
    val chatService = koinInject<ChatService>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val conversation by remember(conversationId) {
        chatService.getConversationFlow(conversationId)
    }.collectAsStateWithLifecycle()
    val configResult = remember(settings, conversation) {
        VoiceAgentConfigResolver().resolve(settings = settings, conversation = conversation)
    }

    when (val result = configResult) {
        is VoiceAgentConfigResult.Available -> {
            val context = LocalContext.current
            val callManager = koinInject<VoiceAgentCallManager>()
            VoiceAgentScreen(
                stateProvider = { callManager.state },
                title = result.config.assistantName,
                onStart = {
                    ContextCompat.startForegroundService(
                        context,
                        voiceAgentCallStartIntent(context, conversationId.toString()),
                    )
                },
                onBack = { navController.popBackStack() },
                onMuteToggle = { muted -> callManager.setMuted(!muted) },
                onInterrupt = callManager::interrupt,
                onReconnect = callManager::reconnect,
                onEnd = {
                    context.startService(voiceAgentCallEndIntent(context))
                    navController.popBackStack()
                },
            )
        }
        is VoiceAgentConfigResult.Unavailable -> {
            VoiceAgentUnavailableScreen(
                message = result.message,
                onBack = navController::popBackStack,
            )
        }
    }
}

@Composable
private fun VoiceAgentScreen(
    stateProvider: () -> StateFlow<VoiceAgentUiState>,
    title: String,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onEnd: () -> Unit,
) {
    val state by stateProvider().collectAsStateWithLifecycle()
    val muted = state.audio == VoiceAudioStatus.Muted
    val microphonePermission = rememberPermissionState(PermissionRecordAudio)
    val notificationPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(PermissionNotification)
    } else {
        null
    }
    val startGate = voiceAgentStartGate(
        hasMicrophonePermission = microphonePermission.allRequiredPermissionsGranted,
        hasNotificationPermission = notificationPermission?.allRequiredPermissionsGranted ?: true,
    )
    var requestedMicrophonePermission by remember { mutableStateOf(false) }
    var requestedNotificationPermission by remember { mutableStateOf(false) }

    PermissionManager(permissionState = microphonePermission)
    if (notificationPermission != null) {
        PermissionManager(permissionState = notificationPermission)
    }

    KeepScreenOn()

    LaunchedEffect(startGate) {
        when {
            startGate == VoiceAgentStartGate.NeedsMicrophonePermission && !requestedMicrophonePermission -> {
                requestedMicrophonePermission = true
                microphonePermission.requestPermissions()
            }
            startGate == VoiceAgentStartGate.NeedsNotificationPermission && !requestedNotificationPermission -> {
                requestedNotificationPermission = true
                notificationPermission?.requestPermissions()
            }
        }
    }

    LaunchedEffect(startGate) {
        if (startGate == VoiceAgentStartGate.Ready) {
            onStart()
        }
    }

    VoiceAgentScaffold(
        title = "Voice Agent",
        subtitle = title,
        onBack = onBack,
        primaryStatus = when (startGate) {
            VoiceAgentStartGate.NeedsMicrophonePermission -> "Microphone permission required"
            VoiceAgentStartGate.NeedsNotificationPermission -> "Notification permission required"
            VoiceAgentStartGate.Ready -> state.statusText()
        },
        error = state.error,
        actions = if (startGate == VoiceAgentStartGate.Ready) {
            {
                VoiceAgentControls(
                    muted = muted,
                    onMuteToggle = { onMuteToggle(muted) },
                    onInterrupt = onInterrupt,
                    onReconnect = onReconnect,
                    onEnd = onEnd,
                )
            }
        } else {
            null
        },
        content = {
            StateCard(label = "Call", value = state.callStatusText())
            StateCard(label = "Session", value = state.session.statusLabel())
            StateCard(label = "Audio", value = state.audio.statusLabel())
            StateCard(label = "Hermes/MS-agent", value = state.tool.visibleStatusLabel())
            StateCard(label = "History", value = state.persistence.statusLabel())
            if (startGate == VoiceAgentStartGate.NeedsMicrophonePermission) {
                MicrophonePermissionCard(
                    onRequestPermission = {
                        requestedMicrophonePermission = true
                        microphonePermission.requestPermissions()
                    },
                )
                DiagnosticsCard(diagnostics = state.diagnostics)
                return@VoiceAgentScaffold
            }
            if (startGate == VoiceAgentStartGate.NeedsNotificationPermission) {
                NotificationPermissionCard(
                    onRequestPermission = {
                        requestedNotificationPermission = true
                        notificationPermission?.requestPermissions()
                    },
                )
                DiagnosticsCard(diagnostics = state.diagnostics)
                return@VoiceAgentScaffold
            }
            TranscriptCard(
                inputTranscript = state.inputTranscript,
                outputTranscript = state.outputTranscript,
            )
            DiagnosticsCard(diagnostics = state.diagnostics)
        },
    )
}

@Composable
private fun VoiceAgentUnavailableScreen(
    message: String,
    onBack: () -> Unit,
) {
    VoiceAgentScaffold(
        title = "Voice Agent",
        subtitle = "Unavailable",
        onBack = onBack,
        primaryStatus = "Cannot start voice",
        error = message,
        content = {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back to chat")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceAgentScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    primaryStatus: String,
    error: String?,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(HugeIcons.ArrowLeft01, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                subtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            },
            bottomBar = {
                if (actions != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        tonalElevation = 3.dp,
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            actions()
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    primaryStatus,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (error != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun VoiceAgentControls(
    muted: Boolean,
    onMuteToggle: () -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onEnd: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = onMuteToggle,
            modifier = Modifier.weight(1f),
        ) {
            Icon(HugeIcons.Mic01, contentDescription = null)
            Text(if (muted) "Unmute" else "Mute")
        }
        OutlinedButton(
            onClick = onInterrupt,
            modifier = Modifier.weight(1f),
        ) {
            Icon(HugeIcons.StopCircle, contentDescription = null)
            Text("Interrupt")
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = onReconnect,
            modifier = Modifier.weight(1f),
        ) {
            Icon(HugeIcons.Refresh01, contentDescription = null)
            Text("Reconnect")
        }
        Button(
            onClick = onEnd,
            modifier = Modifier.weight(1f),
        ) {
            Icon(HugeIcons.Cancel01, contentDescription = null)
            Text("End")
        }
    }
}

@Composable
private fun MicrophonePermissionCard(
    onRequestPermission: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Text("Microphone permission required", fontWeight = FontWeight.SemiBold)
            Text("Voice Agent cannot start listening until RikkaHub can access the microphone.")
            Button(onClick = onRequestPermission) {
                Text("Grant microphone")
            }
        }
    }
}

@Composable
private fun NotificationPermissionCard(
    onRequestPermission: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Text("Notification permission required", fontWeight = FontWeight.SemiBold)
            Text("Voice Agent needs notifications so you can return to and end the background call.")
            Button(onClick = onRequestPermission) {
                Text("Grant notifications")
            }
        }
    }
}

@Composable
private fun StateCard(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TranscriptCard(
    inputTranscript: String,
    outputTranscript: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Text(
                "Live transcript",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            TranscriptLine(label = "You", value = inputTranscript)
            TranscriptLine(label = "Assistant", value = outputTranscript)
        }
    }
}

@Composable
private fun TranscriptLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            Text(
                text = value.ifBlank { "..." },
                color = if (value.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: List<VoiceDiagnosticLine>) {
    var expanded by remember { mutableStateOf(false) }
    val visibleDiagnostics = if (expanded) diagnostics else diagnostics.takeLast(8)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Diagnostics",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (diagnostics.size > 8) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Show less" else "Show all")
                    }
                }
            }
            if (diagnostics.isEmpty()) {
                Text(
                    "...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                visibleDiagnostics.forEach { event ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            event.name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (event.detail.isNotBlank()) {
                            Text(
                                event.detail,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun VoiceAgentUiState.statusText(): String = when (session) {
    VoiceSessionStatus.Idle -> "Idle"
    VoiceSessionStatus.PreparingContext -> "Preparing context"
    VoiceSessionStatus.RequestingToken -> "Requesting Gemini token"
    VoiceSessionStatus.ConnectingGemini -> "Connecting to Gemini"
    VoiceSessionStatus.Connected -> audio.statusLabel()
    VoiceSessionStatus.Reconnecting -> "Reconnecting"
    VoiceSessionStatus.Ending -> "Ending"
    VoiceSessionStatus.Ended -> "Ended"
    is VoiceSessionStatus.Error -> "Error"
}

private fun VoiceAgentUiState.callStatusText(): String = when (val callStatus = call) {
    VoiceCallStatus.Idle -> "Call idle"
    VoiceCallStatus.ForegroundStarting -> "Starting call runtime"
    VoiceCallStatus.BackgroundCapable -> "Background ready"
    is VoiceCallStatus.Degraded -> "Call degraded: ${callStatus.message}"
    VoiceCallStatus.Ending -> "Ending call"
    VoiceCallStatus.Ended -> "Call ended"
}

private fun VoiceSessionStatus.statusLabel(): String = when (this) {
    VoiceSessionStatus.Idle -> "Idle"
    VoiceSessionStatus.PreparingContext -> "Preparing context"
    VoiceSessionStatus.RequestingToken -> "Requesting token"
    VoiceSessionStatus.ConnectingGemini -> "Connecting"
    VoiceSessionStatus.Connected -> "Connected"
    VoiceSessionStatus.Reconnecting -> "Reconnecting"
    VoiceSessionStatus.Ending -> "Ending"
    VoiceSessionStatus.Ended -> "Ended"
    is VoiceSessionStatus.Error -> "Error: $message"
}

private fun VoiceAudioStatus.statusLabel(): String = when (this) {
    VoiceAudioStatus.Listening -> "Listening"
    VoiceAudioStatus.UserSpeaking -> "User speaking"
    VoiceAudioStatus.AssistantSpeaking -> "Assistant speaking"
    VoiceAudioStatus.Muted -> "Muted"
    VoiceAudioStatus.PlaybackSuppressed -> "Interrupted; playback suppressed"
}

internal fun VoiceToolStatus.visibleStatusLabel(): String = when (this) {
    VoiceToolStatus.Idle -> "Idle"
    is VoiceToolStatus.CallingHermes -> "Calling Hermes/MS agent... (${callId.withElapsed(elapsedMs)})"
    is VoiceToolStatus.HermesAnswered -> "Hermes/MS agent answered (${callId.withElapsed(elapsedMs)})"
    is VoiceToolStatus.HermesFailed -> "Hermes/MS agent failed ($callId): $message"
}

private fun String.withElapsed(elapsedMs: Long): String =
    if (elapsedMs > 0L) "$this, ${elapsedMs}ms" else this

private fun VoicePersistenceStatus.statusLabel(): String = when (this) {
    VoicePersistenceStatus.Idle -> "Idle"
    VoicePersistenceStatus.Saving -> "Saving"
    VoicePersistenceStatus.Saved -> "Saved"
    is VoicePersistenceStatus.SaveFailed -> "Save failed: $message"
}
