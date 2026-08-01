package me.rerere.rikkahub.voiceagent

import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.os.ParcelUuid
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.DisconnectCause
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventInput
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventName
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import org.koin.core.context.GlobalContext

internal class VoiceAgentTelecomConnection private constructor(
    private val onCallEndRequested: () -> Unit,
    private val endpointRequestExecutor: Executor,
    private val automationRuntimeProvider: () -> VoiceAutomationRuntime?,
    onRetiring: (VoiceAgentTelecomConnection) -> Unit,
    private val retirementSetDisconnected: ((DisconnectCause) -> Unit)?,
    private val retirementDestroy: (() -> Unit)?,
    onRetired: (VoiceAgentTelecomConnection, Result<Unit>) -> Unit,
) : Connection(), VoiceAgentTelecomCall, VoiceAgentAutomationRoutableCall {
    constructor(
        context: Context,
        onRetiring: (VoiceAgentTelecomConnection) -> Unit,
        onRetired: (VoiceAgentTelecomConnection, Result<Unit>) -> Unit,
    ) : this(
        onCallEndRequested = { context.startService(voiceAgentCallEndIntent(context)) },
        endpointRequestExecutor = ContextCompat.getMainExecutor(context),
        automationRuntimeProvider = {
            runCatching { GlobalContext.get().get<VoiceAutomationRuntime>() }.getOrNull()
        },
        onRetiring = onRetiring,
        retirementSetDisconnected = null,
        retirementDestroy = null,
        onRetired = onRetired,
    )

    internal constructor(
        onCallEndRequested: () -> Unit,
        onRetiring: () -> Unit,
        setDisconnected: (DisconnectCause) -> Unit,
        destroy: () -> Unit,
        onRetired: (Result<Unit>) -> Unit,
    ) : this(
        onCallEndRequested = onCallEndRequested,
        endpointRequestExecutor = Executor(Runnable::run),
        automationRuntimeProvider = { null },
        onRetiring = { onRetiring() },
        retirementSetDisconnected = setDisconnected,
        retirementDestroy = destroy,
        onRetired = { _, result -> onRetired(result) },
    )

    private var requestedBluetoothEndpointId: ParcelUuid? = null
    private var requestedLegacyBluetoothRoute = false
    private var availableAutomationEndpoints: List<CallEndpoint> = emptyList()
    private val retirement = VoiceAgentTelecomRetirement<DisconnectCause>(
        onRetiring = { onRetiring(this) },
        setDisconnected = { cause ->
            retirementSetDisconnected?.invoke(cause) ?: setDisconnected(cause)
        },
        destroy = {
            retirementDestroy?.invoke() ?: destroy()
        },
        onRetired = { result -> onRetired(this, result) },
    )

    override fun onDisconnect() {
        onCallEndRequested()
        retirement.retire(cause = DisconnectCause(DisconnectCause.LOCAL))
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        availableAutomationEndpoints = availableEndpoints

        val candidates = availableEndpoints.map { it.toCandidate() }
        VoiceAgentLog.d(
            TAG,
            "available call endpoints=${candidates.joinToString { it.debugLabel() }}",
        )
        val selected = selectPreferredCallEndpoint(candidates) ?: run {
            requestLegacyBluetoothRouteBestEffort()
            return
        }
        val endpoint = availableEndpoints.firstOrNull { it.identifier.toString() == selected.id } ?: return
        if (!shouldRequestBluetoothCallEndpoint(
                currentEndpoint = currentCallEndpointOrNull(),
                requestedBluetoothEndpointId = requestedBluetoothEndpointId?.toString(),
                selectedBluetoothEndpointId = endpoint.identifier.toString(),
            )
        ) {
            return
        }
        requestedBluetoothEndpointId = endpoint.identifier
        requestCallEndpointChange(
            endpoint,
            endpointRequestExecutor,
            object : OutcomeReceiver<Void?, CallEndpointException> {
                override fun onResult(result: Void?) {
                    VoiceAgentLog.d(TAG, "Bluetooth call endpoint request accepted endpoint=${endpoint.safeLabel()}")
                }

                override fun onError(error: CallEndpointException) {
                    requestedBluetoothEndpointId = null
                    VoiceAgentLog.w(
                        TAG,
                        "Bluetooth call endpoint request failed endpoint=${endpoint.safeLabel()} code=${error.code}",
                    )
                }
            },
        )
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            VoiceAgentLog.d(TAG, "call endpoint changed endpoint=${callEndpoint.safeLabel()}")
            recordObservedAutomationRoute(callEndpoint.toCurrentEndpoint().type)
            if (callEndpoint.endpointType == CallEndpoint.TYPE_BLUETOOTH) {
                requestedBluetoothEndpointId = null
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        VoiceAgentLog.d(TAG, "call audio state changed route=${state.route} supported=${state.supportedRouteMask}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            recordObservedAutomationRoute(
                when (state.route) {
                    CallAudioState.ROUTE_EARPIECE -> VoiceAgentCallEndpointType.Earpiece
                    CallAudioState.ROUTE_SPEAKER -> VoiceAgentCallEndpointType.Speaker
                    else -> null
                },
            )
        }
        if (state.route == CallAudioState.ROUTE_BLUETOOTH) {
            requestedLegacyBluetoothRoute = false
        }
    }

    override fun disconnectFromApp() {
        retirement.retryFromRoute(cause = DisconnectCause(DisconnectCause.LOCAL))
    }

    override fun requestAutomationRoute(type: VoiceAgentCallEndpointType): Boolean {
        val legacyRoute = when (type) {
            VoiceAgentCallEndpointType.Speaker -> CallAudioState.ROUTE_SPEAKER
            VoiceAgentCallEndpointType.Earpiece -> CallAudioState.ROUTE_EARPIECE
            else -> return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return runCatching {
                @Suppress("DEPRECATION")
                setAudioRoute(legacyRoute)
            }.isSuccess
        }

        val endpoint = availableAutomationEndpoints.firstOrNull {
            it.toCandidate().type == type
        } ?: return false
        return runCatching {
            requestCallEndpointChange(
                endpoint,
                endpointRequestExecutor,
                object : OutcomeReceiver<Void?, CallEndpointException> {
                    override fun onResult(result: Void?) {
                        VoiceAgentLog.d(
                            TAG,
                            "automation call endpoint request accepted endpoint=${endpoint.safeLabel()}",
                        )
                    }

                    override fun onError(error: CallEndpointException) {
                        VoiceAgentLog.w(
                            TAG,
                            "automation call endpoint request failed " +
                                "endpoint=${endpoint.safeLabel()} code=${error.code}",
                        )
                    }
                },
            )
        }.isSuccess
    }

    private fun recordObservedAutomationRoute(type: VoiceAgentCallEndpointType?) {
        if (type !in setOf(VoiceAgentCallEndpointType.Speaker, VoiceAgentCallEndpointType.Earpiece)) {
            return
        }
        val runtime = automationRuntimeProvider() ?: return
        if (runtime.status().state != me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState.Active) {
            return
        }
        runtime.record(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.ROUTE_OBSERVED,
                route = type,
            ),
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun CallEndpoint.toCandidate(): VoiceAgentCallEndpointCandidate =
        VoiceAgentCallEndpointCandidate(
            id = identifier.toString(),
            type = when (endpointType) {
                CallEndpoint.TYPE_BLUETOOTH -> VoiceAgentCallEndpointType.Bluetooth
                CallEndpoint.TYPE_EARPIECE -> VoiceAgentCallEndpointType.Earpiece
                CallEndpoint.TYPE_SPEAKER -> VoiceAgentCallEndpointType.Speaker
                CallEndpoint.TYPE_WIRED_HEADSET -> VoiceAgentCallEndpointType.WiredHeadset
                CallEndpoint.TYPE_STREAMING -> VoiceAgentCallEndpointType.Streaming
                else -> VoiceAgentCallEndpointType.Unknown
            },
            name = endpointName.toString(),
        )

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun CallEndpoint.toCurrentEndpoint(): VoiceAgentCurrentCallEndpoint =
        VoiceAgentCurrentCallEndpoint(
            id = identifier.toString(),
            type = when (endpointType) {
                CallEndpoint.TYPE_BLUETOOTH -> VoiceAgentCallEndpointType.Bluetooth
                CallEndpoint.TYPE_EARPIECE -> VoiceAgentCallEndpointType.Earpiece
                CallEndpoint.TYPE_SPEAKER -> VoiceAgentCallEndpointType.Speaker
                CallEndpoint.TYPE_WIRED_HEADSET -> VoiceAgentCallEndpointType.WiredHeadset
                CallEndpoint.TYPE_STREAMING -> VoiceAgentCallEndpointType.Streaming
                else -> VoiceAgentCallEndpointType.Unknown
            },
        )

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun currentCallEndpointOrNull(): VoiceAgentCurrentCallEndpoint? =
        runCatching { currentCallEndpoint.toCurrentEndpoint() }
            .onFailure { VoiceAgentLog.d(TAG, "current call endpoint unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()

    private fun VoiceAgentCallEndpointCandidate.debugLabel(): String =
        "${type.name}:${name.ifBlank { "unnamed" }}:$id"

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun CallEndpoint.safeLabel(): String =
        "${toCandidate().type.name}:${endpointName.toString().ifBlank { "unnamed" }}:${identifier}"

    @Suppress("DEPRECATION")
    private fun requestLegacyBluetoothRouteBestEffort() {
        if (requestedLegacyBluetoothRoute) {
            return
        }
        requestedLegacyBluetoothRoute = true
        runCatching {
            setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        }.onSuccess {
            VoiceAgentLog.d(TAG, "legacy Bluetooth audio route requested")
        }.onFailure {
            requestedLegacyBluetoothRoute = false
            VoiceAgentLog.w(TAG, "legacy Bluetooth audio route request failed: ${it.toVoiceAgentLogDetail()}")
        }
    }

    private companion object {
        const val TAG = "VoiceAgentTelecomConnection"
    }
}

internal class VoiceAgentTelecomRetirement<Cause>(
    private val onRetiring: () -> Unit,
    private val setDisconnected: (Cause) -> Unit,
    private val destroy: () -> Unit,
    private val onRetired: (Result<Unit>) -> Unit,
) {
    private val lock = Any()
    private var activeAttempt: Attempt? = null
    private var terminalResult: Result<Unit>? = null

    fun retire(cause: Cause) {
        retire(cause, retryAfterFailure = false)
    }

    fun retryFromRoute(cause: Cause) {
        retire(cause, retryAfterFailure = true)
    }

    private fun retire(cause: Cause, retryAfterFailure: Boolean) {
        val currentThread = Thread.currentThread()
        val attempt = synchronized(lock) {
            activeAttempt?.also { currentAttempt ->
                if (currentAttempt.ownerThread === currentThread) return
            } ?: run {
                terminalResult?.let { result ->
                    if (result.isSuccess || !retryAfterFailure) {
                        result.getOrThrow()
                        return
                    }
                }
                Attempt().also { newAttempt ->
                    activeAttempt = newAttempt
                }
            }
        }

        val result = runCatching {
            attempt.retirement.retire {
                attempt.ownerThread = currentThread
                try {
                    runCleanup(cause)
                } finally {
                    attempt.ownerThread = null
                }
            }
        }
        synchronized(lock) {
            if (activeAttempt === attempt) {
                terminalResult = result
                activeAttempt = null
            }
        }
        result.getOrThrow()
    }

    private fun runCleanup(cause: Cause) {
        val cleanupResult = runCatching {
            runVoiceAgentCleanupStages(
                onRetiring,
                { setDisconnected(cause) },
                destroy,
            )
        }
        runVoiceAgentCleanupStages(
            { cleanupResult.getOrThrow() },
            { onRetired(cleanupResult) },
        )
    }

    private class Attempt {
        @Volatile
        var ownerThread: Thread? = null
        val retirement = RetirementBarrier()
    }
}
