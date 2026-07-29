package me.rerere.rikkahub.voiceagent

import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import kotlin.uuid.Uuid

internal class OrchestratorFakeRoute(
    private val onDisconnect: () -> Unit = {},
) {
    private val registry = VoiceAgentTelecomCallRegistry()
    private val attempt = registry.beginAttempt().requireAllocatedAttemptId()
    var retirementCalls = 0
    val lease: VoiceAgentRouteLease

    init {
        check(
            registry.activate(
                attempt,
                object : VoiceAgentTelecomCall {
                    override fun disconnectFromApp() {
                        retirementCalls += 1
                        onDisconnect()
                    }
                },
            ),
        )
        lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()
    }
}

internal class OrchestratorFakeSession(
    initialState: VoiceAgentUiState = VoiceAgentUiState(session = VoiceSessionStatus.Connected),
    routeMetadata: VoiceAgentRouteMetadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom),
    override val cleanupOperation: VoiceAgentCleanupOperation = OrchestratorFakeCleanupOperation(),
    private val onStart: () -> Unit = {},
    private val onRouteMetadataRead: () -> Unit = {},
    private val onInterrupt: () -> Unit = {},
    private val onSetMuted: (Boolean) -> Unit = {},
    private val onReconnect: () -> Unit = {},
    private val onDiagnostic: (String, String) -> Unit = { _, _ -> },
) : RouteOwnedManagedVoiceCallSession {
    private val mutableState = MutableStateFlow(initialState)
    private val configuredRouteMetadata = routeMetadata
    override val state: StateFlow<VoiceAgentUiState> = mutableState
    override val routeMetadata: VoiceAgentRouteMetadata
        get() {
            onRouteMetadataRead()
            return configuredRouteMetadata
        }
    override var isRouteUsable: Boolean = true
    var startCalls = 0
    var interruptCalls = 0
    var reconnectCalls = 0
    var mutedValues = mutableListOf<Boolean>()
    var diagnostics = mutableListOf<Pair<String, String>>()

    fun emit(value: VoiceAgentUiState) {
        mutableState.value = value
    }

    fun collectorCount(): Int = mutableState.subscriptionCount.value

    override fun start() {
        startCalls += 1
        onStart()
    }

    override fun interrupt() {
        interruptCalls += 1
        onInterrupt()
    }

    override fun setMuted(value: Boolean) {
        mutedValues += value
        onSetMuted(value)
    }

    override fun reconnect() {
        reconnectCalls += 1
        onReconnect()
    }

    override fun recordDiagnostic(name: String, detail: String) {
        diagnostics += name to detail
        onDiagnostic(name, detail)
    }

}

internal class OrchestratorFakeCleanupOperation(
    private val block: suspend (VoiceAgentCleanupMode) -> VoiceAgentCleanupResult = {
        VoiceAgentCleanupResult.Completed
    },
) : VoiceAgentCleanupOperation {
    override val token: Any = Any()
    val modes: MutableList<VoiceAgentCleanupMode> = Collections.synchronizedList(mutableListOf())

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        modes += mode
        return block(mode)
    }
}

internal class OrchestratorCleanupDelegate : ManagedVoiceCallSession {
    override val state: StateFlow<VoiceAgentUiState> = MutableStateFlow(VoiceAgentUiState())
    var endCalls = 0
    var drainCalls = 0
    var closeCalls = 0
    var endFailure: Throwable? = null
    var drainFailure: Throwable? = null
    var closeFailure: Throwable? = null

    override fun start() = Unit

    override fun interrupt() = Unit

    override fun setMuted(value: Boolean) = Unit

    override fun reconnect() = Unit

    override fun recordDiagnostic(name: String, detail: String) = Unit

    override fun end() {
        endCalls += 1
        endFailure?.let { throw it }
    }

    override suspend fun endAndDrain() {
        drainCalls += 1
        drainFailure?.let { throw it }
    }

    override fun closeNow() {
        closeCalls += 1
        closeFailure?.let { throw it }
    }
}

internal class OrchestratorFakeFactory(
    private val createResult: suspend (
        VoiceAgentCallRequest,
        VoiceAgentRouteLease,
        CoroutineScope,
    ) -> VoiceAgentSessionCreationResult,
) : VoiceAgentCallFactory {
    var calls = 0
    val requests = mutableListOf<VoiceAgentCallRequest>()
    val leases = mutableListOf<VoiceAgentRouteLease>()
    val scopes = mutableListOf<CoroutineScope>()
    val endDrainTimeouts = mutableListOf<Long>()

    override suspend fun createOwned(
        request: VoiceAgentCallRequest,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
        endDrainTimeoutMillis: Long,
    ): VoiceAgentSessionCreationResult {
        calls += 1
        requests += request
        leases += routeLease
        scopes += scope
        endDrainTimeouts += endDrainTimeoutMillis
        return createResult(request, routeLease, scope)
    }
}

internal fun orchestratorRequest(label: String): VoiceAgentCallRequest = VoiceAgentCallRequest(
    conversationId = Uuid.random(),
    transport = VoiceAgentTransport.DirectGemini,
    config = VoiceAgentLaunchConfig(
        hermesVoiceBaseUrl = "https://$label.voice.test",
        credentials = HermesVoiceCredentials(deviceApiKey = "test-key"),
        voiceModelId = label,
        assistantName = "Assistant $label",
        assistantPrompt = "Prompt $label",
        directAccountConfigurationHash = "sha256:" + "a".repeat(64),
    ),
)
