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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

class VoiceAgentCallService : Service() {
    private val manager: VoiceAgentCallManager by inject()
    private val settingsStore: SettingsStore by inject()
    private val chatService: ChatService by inject()
    private val notificationFactory: VoiceAgentNotificationFactory by inject()
    private val callStartup: VoiceAgentCallStartup by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null
    private val lifecycle by lazy(LazyThreadSafetyMode.NONE) {
        VoiceAgentCallServiceLifecycle(
            manager = manager,
            serviceScope = serviceScope,
            host = object : VoiceAgentCallServiceLifecycleHost {
                override fun cancelNotification() {
                    notificationJob?.cancel()
                    notificationJob = null
                }

                override fun startForeground(conversationId: String, state: VoiceAgentUiState) {
                    startForegroundFor(conversationId, state)
                }

                override fun endCompleted(conversationId: Uuid?) {
                    VoiceAgentLog.d(TAG, "end completed conversationId=${conversationId ?: "none"}")
                }

                override fun stopForeground() {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }

                override fun stopSelf() {
                    this@VoiceAgentCallService.stopSelf()
                }

                override fun reportCleanupFailure(error: Throwable) {
                    VoiceAgentLog.w(TAG, "service cleanup failed: ${error.toVoiceAgentLogDetail()}")
                }

                override fun destroyBaseService() {
                    this@VoiceAgentCallService.destroyBaseService()
                }
            },
        )
    }

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
        lifecycle.destroy()
    }

    private fun destroyBaseService() = super.onDestroy()

    private fun startCall(intent: Intent) {
        val id = intent.getStringExtra(VoiceAgentCallContract.EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: run {
                VoiceAgentLog.w(TAG, "start ignored: missing or invalid conversation id")
                stopSelf()
                return
        }
        VoiceAgentLog.d(TAG, "start requested conversationId=$id")
        val startGeneration = lifecycle.beginStart()
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
                if (!lifecycle.isCurrent(startGeneration)) {
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
                        val startupResult = callStartup.start(
                            conversationId = id,
                            config = result.config,
                            scope = serviceScope,
                            isCurrent = { lifecycle.isCurrent(startGeneration) },
                        )
                        val started = when (startupResult) {
                            is VoiceAgentCallStartupResult.Stale -> return@launch
                            is VoiceAgentCallStartupResult.Started -> startupResult
                        }
                        val route = started.route
                        val startedNewSession = started.startedNewSession
                        VoiceAgentLog.d(TAG, "manager start returned startedNewSession=$startedNewSession")
                        route.failure?.let { failure ->
                            manager.recordDiagnostic(failure.diagnosticName, failure.detail)
                            manager.updateCallStatus(
                                VoiceCallStatus.Degraded("Telecom unavailable: ${failure.detail}"),
                            )
                        }
                        if (
                            shouldPublishVoiceCallBackgroundCapable(
                                owner = route.owner,
                                current = manager.state.value.call,
                            )
                        ) {
                            manager.updateCallStatus(VoiceCallStatus.BackgroundCapable)
                        }
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
                        if (startedNewSession) {
                            VoiceAgentLog.d(TAG, "waiting for startup state")
                            val startupState = manager.state.first { state ->
                                lifecycle.isStartupTerminal(startGeneration, state.session)
                            }
                            if (!lifecycle.isCurrent(startGeneration)) {
                                VoiceAgentLog.d(TAG, "start canceled while waiting for startup state")
                                return@launch
                            }
                            VoiceAgentLog.d(TAG, "startup state=${startupState.session}")
                            if (lifecycle.handleStartupTerminal(id, startupState.session)) {
                                return@launch
                            }
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
                if (lifecycle.isCurrent(startGeneration)) {
                    VoiceAgentLog.w(TAG, "start failed: ${error.toVoiceAgentLogDetail()}")
                    lifecycle.tearDownFailedStart(
                        conversationId = id,
                        error = error,
                        preserveSessionRequested = manager.activeConversationId.value == id,
                    )
                }
            }
        }
    }

    private fun endCall() {
        lifecycle.endCall()
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
    }
}

internal fun shouldStartForegroundForVoiceAgentEnd(activeConversationId: Uuid?): Boolean =
    true

internal fun shouldPublishVoiceCallBackgroundCapable(
    owner: VoiceAudioRouteOwner,
    current: VoiceCallStatus,
): Boolean = owner == VoiceAudioRouteOwner.Telecom && current !is VoiceCallStatus.Degraded

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
