package me.rerere.rikkahub.voiceagent

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

class VoiceAgentCallService : Service() {
    private val controller: VoiceAgentCallServiceController by inject()
    private val settingsStore: SettingsStore by inject()
    private val chatService: ChatService by inject()
    private val notificationFactory: VoiceAgentNotificationFactory by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lifecycle by lazy(LazyThreadSafetyMode.NONE) {
        VoiceAgentCallServiceLifecycle(
            controller = controller,
            serviceScope = serviceScope,
            host = object : VoiceAgentCallServiceLifecycleHost {
                override fun cancelNotification() = Unit

                override fun startForeground(
                    conversationId: String,
                    transport: VoiceAgentTransport,
                    state: VoiceAgentUiState,
                ) {
                    startForegroundFor(conversationId, transport, state)
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

                override fun reportFailure(error: Throwable) {
                    VoiceAgentLog.w(TAG, "service operation failed: ${error.toVoiceAgentLogDetail()}")
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
            VoiceAgentCallContract.ACTION_END -> lifecycle.endCall()
            VoiceAgentCallContract.ACTION_END_BOUND -> endBoundCall(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lifecycle.destroy()
    }

    private fun destroyBaseService() = super.onDestroy()

    private fun startCall(intent: Intent) {
        val fields = parseStartFields(intent) ?: return
        val conversationId = fields.conversationId
        VoiceAgentLog.d(TAG, "start requested conversationId=$conversationId")
        val generation = lifecycle.beginStart(conversationId, fields.transport)
        lifecycle.launchStartConfiguration(generation, conversationId) {
            VoiceAgentLog.d(TAG, "loading settings and conversation")
            val settings = settingsStore.settingsFlow.first()
            val conversation = chatService.getConversationFlow(conversationId).value
            when (val result = VoiceAgentConfigResolver().resolve(settings, conversation)) {
                is VoiceAgentConfigResult.Available -> {
                    VoiceAgentLog.d(TAG, "config available voiceModelId=${result.config.voiceModelId}")
                    VoiceAgentCallRequest(
                        conversationId = conversationId,
                        config = result.config,
                        transport = fields.transport,
                        captureFixtureToken = fields.captureFixtureToken,
                        automationBinding = fields.automationBinding,
                    )
                }
                is VoiceAgentConfigResult.Unavailable -> {
                    val safeMessage = result.message.redactForVoiceAgentLog()
                    VoiceAgentLog.w(TAG, "config unavailable: $safeMessage")
                    throw VoiceAgentCallConfigurationException(safeMessage)
                }
            }
        }
    }

    private fun parseStartFields(intent: Intent): VoiceAgentCallStartFields? {
        val fields = decodeVoiceAgentCallStartFields(
            conversationId = intent.getStringExtra(VoiceAgentCallContract.EXTRA_CONVERSATION_ID),
            transportWireName = intent.getStringExtra(VoiceAgentCallContract.EXTRA_TRANSPORT),
            captureFixtureToken = intent.getStringExtra(
                VoiceAgentCallContract.EXTRA_CAPTURE_FIXTURE_TOKEN,
            ),
            runHash = intent.getStringExtra(VoiceAgentCallContract.EXTRA_RUN_HASH),
            comparisonHash = intent.getStringExtra(VoiceAgentCallContract.EXTRA_COMPARISON_HASH),
        )
        if (fields == null) {
            VoiceAgentLog.w(TAG, "start ignored: missing or invalid start fields")
            lifecycle.rejectInvalidStart(
                VoiceAgentCallConfigurationException("Missing or invalid voice call start fields"),
            )
        }
        return fields
    }

    private fun endBoundCall(intent: Intent) {
        val extras = intent.extras
        val stringExtras = mutableMapOf<String, String?>()
        extras?.keySet()?.forEach { key ->
            @Suppress("DEPRECATION")
            stringExtras[key] = extras.get(key) as? String
        }
        val identity = decodeVoiceAgentBoundCallIdentity(stringExtras)
        if (identity == null || !lifecycle.endCallIfMatches(identity)) {
            VoiceAgentLog.w(TAG, "bound end ignored: missing, invalid, or stale identity")
        }
    }

    private fun startForegroundFor(
        conversationId: String,
        transport: VoiceAgentTransport,
        state: VoiceAgentUiState,
    ) {
        val notification = notificationFactory.activeNotification(
            conversationId = conversationId,
            transport = transport,
            state = state,
        )
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

internal class VoiceAgentCallConfigurationException(message: String) : IllegalStateException(message)

internal fun shouldStartForegroundForVoiceAgentEnd(activeConversationId: Uuid?): Boolean = true

internal fun Throwable.toVoiceAgentLogDetail(): String =
    "${javaClass.simpleName}: ${(message ?: "").redactForVoiceAgentLog()}".take(512)

internal fun String.redactForVoiceAgentLog(): String =
    replace(Regex("""(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]+"""), "$1[redacted]")
        .replace(
            Regex(
                """(?i)\b(api[_-]?key|key|token|secret|password|client[_-]?id|client[_-]?secret|""" +
                    """websocket[_-]?url|session[_-]?url)\s*[:=]\s*[^,\s;}]+""",
            ),
            "$1=[redacted]",
        )
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(512)
