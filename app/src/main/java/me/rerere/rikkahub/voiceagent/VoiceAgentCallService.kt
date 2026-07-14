package me.rerere.rikkahub.voiceagent

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

class VoiceAgentCallService : Service() {
    private val manager: VoiceAgentCallManager by inject()
    private val settingsStore: SettingsStore by inject()
    private val chatService: ChatService by inject()
    private val notificationFactory: VoiceAgentNotificationFactory by inject()
    private val telecomAdapter: VoiceAgentTelecomAdapter by inject()
    private val telecomCallRegistry: VoiceAgentTelecomCallRegistry by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null
    private var endJob: Job? = null
    private var callGeneration = 0L
    private var telecomConversationId: Uuid? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            VoiceAgentCallContract.ACTION_START -> startCall(intent)
            VoiceAgentCallContract.ACTION_END -> endCall()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        endJob = null
        telecomConversationId = null
        telecomCallRegistry.disconnectActive()
        manager.closeNow()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCall(intent: Intent) {
        val id = intent.getStringExtra(VoiceAgentCallContract.EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: run {
                VoiceAgentLog.w(TAG, "start ignored: missing or invalid conversation id")
                stopSelf()
                return
        }
        VoiceAgentLog.d(TAG, "start requested conversationId=$id")
        callGeneration += 1
        val startGeneration = callGeneration
        endJob = null
        notificationJob?.cancel()
        val preserveDegradedStatus = manager.activeConversationId.value == id &&
            manager.state.value.call is VoiceCallStatus.Degraded
        if (!preserveDegradedStatus) {
            manager.updateCallStatus(VoiceCallStatus.ForegroundStarting)
        }
        startForegroundFor(id.toString(), manager.state.value)
        serviceScope.launch {
            try {
                VoiceAgentLog.d(TAG, "loading settings and conversation")
                val settings = settingsStore.settingsFlow.first()
                val conversation = chatService.getConversationFlow(id).value
                if (startGeneration != callGeneration) {
                    VoiceAgentLog.d(TAG, "start canceled before config resolution")
                    return@launch
                }
                when (val result = VoiceAgentConfigResolver().resolve(settings = settings, conversation = conversation)) {
                    is VoiceAgentConfigResult.Available -> {
                        VoiceAgentLog.d(
                            TAG,
                            "config available voiceModelId=${result.config.voiceModelId} " +
                                "baseUrl=${result.config.hermesVoiceBaseUrl}",
                        )
                        val startedNewSession = manager.start(
                            conversationId = id,
                            config = result.config,
                            scope = serviceScope,
                        )
                        VoiceAgentLog.d(TAG, "manager start returned startedNewSession=$startedNewSession")
                        notificationJob = serviceScope.launch {
                            manager.state.collect { state ->
                                startForegroundFor(id.toString(), state)
                            }
                        }
                        val serviceReconnect = !startedNewSession &&
                            manager.state.value.session is VoiceSessionStatus.Error
                        if (serviceReconnect) {
                            VoiceAgentLog.d(TAG, "existing session is error; reconnecting")
                            manager.reconnect()
                        }
                        val hasActiveTelecomCall = telecomConversationId == id &&
                            telecomCallRegistry.hasActiveConnection()
                        val needsTelecomSetup = startedNewSession || !hasActiveTelecomCall
                        if (needsTelecomSetup) {
                            startTelecomCall(id)
                        }
                        if (needsTelecomSetup) {
                            VoiceAgentLog.d(TAG, "waiting for startup state")
                            var observedReconnectAttempt = !serviceReconnect
                            val startupState = manager.state.first { state ->
                                if (startGeneration != callGeneration) {
                                    return@first true
                                }
                                if (!observedReconnectAttempt && state.session is VoiceSessionStatus.Error) {
                                    return@first false
                                }
                                observedReconnectAttempt = true
                                state.session == VoiceSessionStatus.Connected ||
                                    state.session is VoiceSessionStatus.Error ||
                                    state.session == VoiceSessionStatus.Ended
                            }
                            if (startGeneration != callGeneration) {
                                VoiceAgentLog.d(TAG, "start canceled while waiting for startup state")
                                return@launch
                            }
                            VoiceAgentLog.d(TAG, "startup state=${startupState.session}")
                            when (val session = startupState.session) {
                                VoiceSessionStatus.Connected -> Unit
                                is VoiceSessionStatus.Error -> {
                                    tearDownFailedStart(
                                        conversationId = id.toString(),
                                        error = IllegalStateException(session.message),
                                        preserveSession = true,
                                    )
                                    return@launch
                                }
                                else -> {
                                    tearDownFailedStart(
                                        conversationId = id.toString(),
                                        error = IllegalStateException("Voice call ended before startup completed"),
                                    )
                                    return@launch
                                }
                            }
                        }
                        if (!needsTelecomSetup && manager.state.value.call !is VoiceCallStatus.Degraded) {
                            manager.updateCallStatus(VoiceCallStatus.BackgroundCapable)
                        }
                    }
                    is VoiceAgentConfigResult.Unavailable -> {
                        VoiceAgentLog.w(TAG, "config unavailable: ${result.message.redactForVoiceAgentLog()}")
                        manager.updateCallStatus(VoiceCallStatus.Degraded(result.message))
                        startForegroundFor(id.toString(), manager.state.value)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (startGeneration == callGeneration) {
                    VoiceAgentLog.w(TAG, "start failed: ${error.toVoiceAgentLogDetail()}")
                    tearDownFailedStart(
                        conversationId = id.toString(),
                        error = error,
                        preserveSession = manager.activeConversationId.value == id,
                    )
                }
            }
        }
    }

    private fun endCall() {
        if (endJob?.isActive == true) {
            return
        }
        notificationJob?.cancel()
        notificationJob = null
        val endingConversationId = manager.activeConversationId.value
        if (shouldStartForegroundForVoiceAgentEnd(endingConversationId)) {
            startForegroundFor(
                conversationId = endingConversationId?.toString() ?: FALLBACK_END_NOTIFICATION_CONVERSATION_ID,
                state = manager.state.value.copy(call = VoiceCallStatus.Ending),
            )
        }
        callGeneration += 1
        val endGeneration = callGeneration
        val session = manager.detachForEndAndDrain()
        if (session == null && endingConversationId == null) {
            telecomConversationId = null
            telecomCallRegistry.disconnectActive()
            VoiceAgentLog.d(TAG, "end completed conversationId=none")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        endJob = serviceScope.launch {
            if (endGeneration != callGeneration) {
                return@launch
            }
            try {
                telecomConversationId = null
                telecomCallRegistry.disconnectActive()
                session?.endAndDrain()
                VoiceAgentLog.d(TAG, "end completed conversationId=${endingConversationId ?: "none"}")
            } finally {
                if (endGeneration == callGeneration) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    endJob = null
                }
            }
        }
    }

    private fun tearDownFailedStart(conversationId: String, error: Throwable, preserveSession: Boolean = false) {
        val detail = error.message ?: error.javaClass.simpleName
        VoiceAgentLog.w(
            TAG,
            "tearing down failed start preserveSession=$preserveSession detail=${detail.redactForVoiceAgentLog()}",
        )
        notificationJob?.cancel()
        notificationJob = null
        manager.recordDiagnostic("voice_call_start_failed", detail)
        telecomConversationId = null
        telecomCallRegistry.disconnectActive()
        if (!preserveSession) {
            manager.closeNow()
        }
        manager.updateCallStatus(VoiceCallStatus.Degraded("Voice call startup failed: $detail"))
        startForegroundFor(conversationId, manager.state.value)
        if (!preserveSession) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startTelecomCall(conversationId: Uuid) {
        VoiceAgentLog.d(TAG, "starting telecom call conversationId=$conversationId")
        telecomConversationId = null
        telecomCallRegistry.disconnectActive()
        telecomAdapter.register()
            .onFailure {
                val detail = it.message ?: it.javaClass.simpleName
                VoiceAgentLog.w(TAG, "telecom register failed: ${detail.redactForVoiceAgentLog()}")
                manager.recordDiagnostic("telecom_register_failed", detail)
                manager.updateCallStatus(VoiceCallStatus.Degraded("Telecom unavailable: $detail"))
                return
            }
        telecomAdapter.startCall()
            .onSuccess {
                telecomConversationId = conversationId
                if (telecomCallRegistry.hasActiveConnection()) {
                    manager.updateCallStatus(VoiceCallStatus.BackgroundCapable)
                }
            }
            .onFailure {
                val detail = it.message ?: it.javaClass.simpleName
                VoiceAgentLog.w(TAG, "telecom start failed: ${detail.redactForVoiceAgentLog()}")
                manager.recordDiagnostic("telecom_start_failed", detail)
                manager.updateCallStatus(VoiceCallStatus.Degraded("Telecom call unavailable: $detail"))
            }
    }

    private fun startForegroundFor(conversationId: String, state: VoiceAgentUiState) {
        val notification = notificationFactory.activeNotification(conversationId = conversationId, state = state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                VoiceAgentCallContract.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(VoiceAgentCallContract.NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "VoiceAgentCallService"
        const val FALLBACK_END_NOTIFICATION_CONVERSATION_ID = "voice-agent"
    }
}

internal fun shouldStartForegroundForVoiceAgentEnd(activeConversationId: Uuid?): Boolean =
    true

internal fun Throwable.toVoiceAgentLogDetail(): String =
    "${javaClass.simpleName}: ${(message ?: "").redactForVoiceAgentLog()}"

internal fun String.redactForVoiceAgentLog(): String =
    replace(Regex("""(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]+"""), "$1[redacted]")
        .replace(
            Regex(
                """(?i)\b(api[_-]?key|key|token|secret|password|client[_-]?id|client[_-]?secret|""" +
                    """websocket[_-]?url|session[_-]?url)\s*[:=]\s*[^,\s;}]+"""
            ),
            "$1=[redacted]",
        )
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(512)
