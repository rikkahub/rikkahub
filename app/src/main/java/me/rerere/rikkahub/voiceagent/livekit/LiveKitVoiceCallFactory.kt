package me.rerere.rikkahub.voiceagent.livekit

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.VoiceAgentCallFactory
import me.rerere.rikkahub.voiceagent.VoiceAgentCallRequest
import me.rerere.rikkahub.voiceagent.VoiceAgentRouteLease
import me.rerere.rikkahub.voiceagent.VoiceAgentSessionCreationResult
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureArming
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource
import me.rerere.rikkahub.voiceagent.finishFailedOwnedVoiceSessionCreation
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceTraceHeaders
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.newVoiceTraceContext
import me.rerere.rikkahub.voiceagent.voiceAgentRouteCleanupOperation

internal class LiveKitVoiceCallFactory internal constructor(
    private val context: Context,
    private val traceContextFactory: () -> VoiceTraceContext = ::newVoiceTraceContext,
    private val sessionDetailsFactory: suspend (VoiceAgentCallRequest, VoiceTraceContext) -> LiveKitSessionDetails =
        { request, trace ->
            HermesVoiceApi(
                baseUrl = request.config.hermesVoiceBaseUrl,
                credentials = request.config.credentials,
                traceHeaders = HermesVoiceTraceHeaders.from(trace),
            ).createLiveKitSession(
                conversationId = request.conversationId.toString(),
                traceId = trace.traceId,
            )
        },
    private val roomFactory: () -> LiveKitRoomFacade = { AndroidLiveKitRoomFacade(context) },
    private val sessionCreationTimeoutMillis: Long = DEFAULT_LIVEKIT_SESSION_CREATION_TIMEOUT_MS,
) : VoiceAgentCallFactory {
    init {
        require(sessionCreationTimeoutMillis > 0) { "sessionCreationTimeoutMillis must be positive" }
    }

    override suspend fun createOwned(
        request: VoiceAgentCallRequest,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
        endDrainTimeoutMillis: Long,
    ): VoiceAgentSessionCreationResult {
        val cleanup = voiceAgentRouteCleanupOperation(routeLease)
        var captureSource: VoiceCaptureSource? = null
        return try {
            captureSource = VoiceCaptureFixtureArming.claimSource(request.captureFixtureToken)
                .getOrElse { cause ->
                    throw LiveKitExperimentalVoiceCallException(
                        message = "Capture fixture token is not armed",
                        cause = cause,
                    )
                }
            val trace = traceContextFactory()
            val details = withTimeout(sessionCreationTimeoutMillis) {
                sessionDetailsFactory(request, trace)
            }
            VoiceAgentSessionCreationResult.Created(
                LiveKitVoiceCallSession(
                    details = details,
                    traceId = trace.traceId,
                    room = roomFactory(),
                    routeLease = routeLease,
                    scope = scope,
                    captureSource = checkNotNull(captureSource),
                ),
            )
        } catch (_: TimeoutCancellationException) {
            captureSource?.close()
            finishFailedOwnedVoiceSessionCreation(
                LiveKitExperimentalVoiceCallException("LiveKit experimental voice session request timed out"),
                cleanup,
            )
        } catch (creationError: Throwable) {
            captureSource?.close()
            finishFailedOwnedVoiceSessionCreation(
                if (creationError is CancellationException) {
                    creationError
                } else {
                    LiveKitExperimentalVoiceCallException(
                        message = "LiveKit experimental voice session request failed",
                        cause = creationError,
                    )
                },
                cleanup,
            )
        }
    }

    private companion object {
        const val DEFAULT_LIVEKIT_SESSION_CREATION_TIMEOUT_MS = 15_000L
    }
}

internal class LiveKitExperimentalVoiceCallException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
