package me.rerere.rikkahub.voiceagent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.livekit.LiveKitExperimentalVoiceCallException
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

class VoiceAgentCallService : Service() {
    private val controller: VoiceAgentCallServiceController by inject()
    private val settingsStore: SettingsStore by inject()
    private val chatService: ChatService by inject()
    private val notificationFactory: VoiceAgentNotificationFactory by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wakeLockController by lazy(LazyThreadSafetyMode.NONE) {
        VoiceAgentWakeLockController {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            AndroidVoiceAgentWakeLock(
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "rikkahub:voice_agent_call",
                ),
            )
        }
    }
    private val lifecycle by lazy(LazyThreadSafetyMode.NONE) {
        VoiceAgentCallServiceLifecycle(
            controller = controller,
            serviceScope = serviceScope,
            host = VoiceAgentCallServiceHost(
                wakeLockController = wakeLockController,
                cancelNotificationAction = {},
                startForegroundAction = ::startForegroundFor,
                endCompletedAction = { conversationId ->
                    VoiceAgentLog.d(TAG, "end completed conversationId=${conversationId ?: "none"}")
                },
                stopForegroundAction = { stopForeground(STOP_FOREGROUND_REMOVE) },
                stopSelfAction = { this@VoiceAgentCallService.stopSelf() },
                reportFailureAction = { error ->
                    VoiceAgentLog.w(TAG, error.toVoiceAgentServiceLogMessage())
                },
                destroyBaseServiceAction = ::destroyBaseService,
            ),
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

internal interface VoiceAgentWakeLock {
    fun acquire()
    fun release()
    val isHeld: Boolean
}

internal class AndroidVoiceAgentWakeLock(
    private val wakeLock: PowerManager.WakeLock,
) : VoiceAgentWakeLock {
    override fun acquire() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
    }

    override fun release() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    override val isHeld: Boolean
        get() = wakeLock.isHeld
}

internal class VoiceAgentWakeLockController(
    private val wakeLockProvider: () -> VoiceAgentWakeLock,
) {
    private var wakeLock: VoiceAgentWakeLock? = null

    fun acquireLock() {
        if (wakeLock == null) {
            try {
                val lock = wakeLockProvider()
                lock.acquire()
                wakeLock = lock
            } catch (error: Throwable) {
                VoiceAgentLog.w("VoiceAgentWakeLock", "Failed to acquire wake lock: ${error.message}")
            }
        }
    }

    fun releaseLock() {
        try {
            wakeLock?.release()
        } finally {
            wakeLock = null
        }
    }

    val isHeld: Boolean
        get() = wakeLock?.isHeld == true
}

internal class VoiceAgentCallServiceHost(
    private val wakeLockController: VoiceAgentWakeLockController,
    private val cancelNotificationAction: () -> Unit,
    private val startForegroundAction: (String, VoiceAgentTransport, VoiceAgentUiState) -> Unit,
    private val endCompletedAction: (Uuid?) -> Unit,
    private val stopForegroundAction: () -> Unit,
    private val stopSelfAction: () -> Unit,
    private val reportFailureAction: (Throwable) -> Unit,
    private val destroyBaseServiceAction: () -> Unit,
) : VoiceAgentCallServiceLifecycleHost {
    override fun cancelNotification() = cancelNotificationAction()

    override fun startForeground(
        conversationId: String,
        transport: VoiceAgentTransport,
        state: VoiceAgentUiState,
    ) {
        startForegroundAction(conversationId, transport, state)
        wakeLockController.acquireLock()
    }

    override fun endCompleted(conversationId: Uuid?) {
        try {
            endCompletedAction(conversationId)
        } finally {
            wakeLockController.releaseLock()
        }
    }

    override fun stopForeground() = stopForegroundAction()

    override fun stopSelf() {
        try {
            stopSelfAction()
        } finally {
            wakeLockController.releaseLock()
        }
    }

    override fun reportFailure(error: Throwable) = reportFailureAction(error)

    override fun destroyBaseService() {
        try {
            destroyBaseServiceAction()
        } finally {
            wakeLockController.releaseLock()
        }
    }
}

internal class VoiceAgentCallConfigurationException(message: String) : IllegalStateException(message)

internal fun shouldStartForegroundForVoiceAgentEnd(activeConversationId: Uuid?): Boolean = true

internal fun Throwable.toVoiceAgentServiceLogMessage(): String {
    val liveKitCategory =
        (this as? LiveKitExperimentalVoiceCallException)?.failureCategory?.wireName
    return if (liveKitCategory == null) {
        "service operation failed: ${toVoiceAgentLogDetail()}"
    } else {
        "service operation failed category=$liveKitCategory"
    }
}

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
