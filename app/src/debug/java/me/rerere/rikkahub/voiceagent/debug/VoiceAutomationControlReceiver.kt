package me.rerere.rikkahub.voiceagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import me.rerere.rikkahub.voiceagent.VoiceAgentCallEndpointType
import me.rerere.rikkahub.voiceagent.VoiceAgentTelecomCallRegistry
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifactPaths
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventInput
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventName
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventValidation
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationLifecycle
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationNetwork
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunBinding
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationStatus
import org.koin.core.context.GlobalContext

class VoiceAutomationControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = runCatching {
            decodeStringExtras(intent)?.let { extras ->
                val koin = GlobalContext.get()
                val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
                VoiceAutomationControl(
                    runtime = koin.get<VoiceAutomationRuntime>(),
                    routeRequester = koin.get<VoiceAgentTelecomCallRegistry>()::requestActiveAudioRoute,
                    connectivityReader = { connectivityManager.readAutomationConnectivity() },
                    artifactFile = { status -> context.automationArtifactFile(status) },
                ).handle(intent.action, extras)
            } ?: VoiceAutomationControl.invalidRequest()
        }.getOrElse {
            VoiceAutomationControl.runtimeFailure()
        }
        setResult(result.resultCode, result.resultData, null)
    }

    private fun decodeStringExtras(intent: Intent): Map<String, String>? {
        val extras = intent.extras ?: return emptyMap()
        return extras.keySet().associateWith { key ->
            @Suppress("DEPRECATION")
            (extras.get(key) as? String) ?: return null
        }
    }

    private fun ConnectivityManager.readAutomationConnectivity(): VoiceAutomationConnectivity {
        val network = activeNetwork
        val capabilities = network?.let(::getNetworkCapabilities)
        val observed = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                VoiceAutomationNetwork.WIFI
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                VoiceAutomationNetwork.CELLULAR
            else -> VoiceAutomationNetwork.NONE
        }
        val validated =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return VoiceAutomationConnectivity(observed, validated)
    }

    private fun Context.automationArtifactFile(status: VoiceAutomationStatus): File? {
        if (status.state != VoiceAutomationRunState.Finalized) return null
        val runHash = status.runHash ?: return null
        VoiceAutomationEventValidation.validateHash("runHash", runHash)
        return File(
            noBackupFilesDir,
            "${VoiceE2EArtifactPaths.ROOT_DIRECTORY_NAME}/" +
                "${runHash.removePrefix("sha256:")}/automation-events.jsonl",
        ).takeIf(File::isFile)
    }
}

internal data class VoiceAutomationConnectivity(
    val network: VoiceAutomationNetwork,
    val validated: Boolean,
)

internal data class VoiceAutomationControlResult(
    val resultCode: Int,
    val resultData: String,
)

internal class VoiceAutomationControl(
    private val runtime: VoiceAutomationRuntime,
    private val routeRequester: (VoiceAgentCallEndpointType) -> Boolean,
    private val connectivityReader: () -> VoiceAutomationConnectivity,
    private val artifactFile: (VoiceAutomationStatus) -> File?,
) {
    fun handle(action: String?, extras: Map<String, String>): VoiceAutomationControlResult =
        try {
            when (action) {
                ACTION_PREPARE -> prepare(extras)
                ACTION_STATUS -> status(extras)
                ACTION_MARK -> mark(extras)
                ACTION_ROUTE -> route(extras)
                ACTION_FINALIZE -> finalize(extras)
                ACTION_DUMP -> dump(extras)
                else -> invalidRequest()
            }
        } catch (_: IllegalArgumentException) {
            invalidRequest()
        } catch (_: IllegalStateException) {
            error("invalid_state")
        } catch (_: Throwable) {
            error("runtime_failure")
        }

    private fun prepare(extras: Map<String, String>): VoiceAutomationControlResult {
        requireExactKeys(
            extras,
            setOf(EXTRA_RUN_HASH, EXTRA_COMPARISON_HASH, EXTRA_TRANSPORT, EXTRA_LIFECYCLE),
        )
        val runHash = extras.getValue(EXTRA_RUN_HASH)
        val comparisonHash = extras.getValue(EXTRA_COMPARISON_HASH)
        VoiceAutomationEventValidation.validateHash("runHash", runHash)
        VoiceAutomationEventValidation.validateHash("comparisonHash", comparisonHash)
        val transport = when (extras.getValue(EXTRA_TRANSPORT)) {
            VoiceAgentTransport.DirectGemini.wireName -> VoiceAgentTransport.DirectGemini
            VoiceAgentTransport.LiveKitExperimental.wireName -> VoiceAgentTransport.LiveKitExperimental
            else -> throw IllegalArgumentException("Invalid transport")
        }
        val lifecycle = when (extras.getValue(EXTRA_LIFECYCLE)) {
            VoiceAutomationLifecycle.FOREGROUND.wireName -> VoiceAutomationLifecycle.FOREGROUND
            VoiceAutomationLifecycle.BACKGROUND.wireName -> VoiceAutomationLifecycle.BACKGROUND
            else -> throw IllegalArgumentException("Invalid lifecycle")
        }
        runtime.prepare(VoiceAutomationRunBinding(runHash, comparisonHash, transport))
        runtime.record(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.LIFECYCLE_REQUESTED,
                lifecycle = lifecycle,
            ),
        )
        return success("prepare")
    }

    private fun status(extras: Map<String, String>): VoiceAutomationControlResult {
        requireExactKeys(extras, emptySet())
        val connectivity = connectivityReader()
        if (runtime.status().state == VoiceAutomationRunState.Active) {
            runtime.record(
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.NETWORK_OBSERVED,
                    network = connectivity.network,
                    succeeded = connectivity.validated,
                ),
            )
        }
        val status = runtime.status()
        return VoiceAutomationControlResult(
            resultCode = RESULT_OK,
            resultData = output(
                "status" to "ok",
                "action" to "status",
                "run_state" to status.state.wireName(),
                "run_hash" to (status.runHash ?: "none"),
                "comparison_hash" to (status.comparisonHash ?: "none"),
                "requested_transport" to (status.requestedTransport?.wireName ?: "none"),
                "event_count" to status.eventCount.toString(),
                "network" to connectivity.network.wireName,
                "validated" to connectivity.validated.toString(),
            ),
        )
    }

    private fun mark(extras: Map<String, String>): VoiceAutomationControlResult {
        requireExactKeys(extras, setOf(EXTRA_BOUNDARY, EXTRA_RUN_HASH))
        requireActiveRun()
        val boundary = SCENARIO_BOUNDARIES[extras.getValue(EXTRA_BOUNDARY)]
            ?: throw IllegalArgumentException("Invalid scenario boundary")
        val runHash = extras.getValue(EXTRA_RUN_HASH)
        VoiceAutomationEventValidation.validateHash("runHash", runHash)
        val event = when (boundary) {
            VoiceAutomationEventName.HANDOVER_CELLULAR_OBSERVED -> VoiceAutomationEventInput(
                name = boundary,
                network = VoiceAutomationNetwork.CELLULAR,
            )
            VoiceAutomationEventName.HANDOVER_WIFI_RESTORED -> VoiceAutomationEventInput(
                name = boundary,
                network = VoiceAutomationNetwork.WIFI,
            )
            else -> VoiceAutomationEventInput(name = boundary)
        }
        check(runtime.recordIfActiveRun(runHash = runHash, event = event)) {
            "Automation run owner changed"
        }
        return success("mark", "boundary" to boundary.wireName)
    }

    private fun route(extras: Map<String, String>): VoiceAutomationControlResult {
        requireExactKeys(extras, setOf(EXTRA_ROUTE))
        requireActiveRun()
        val type = when (extras.getValue(EXTRA_ROUTE)) {
            "speaker" -> VoiceAgentCallEndpointType.Speaker
            "earpiece" -> VoiceAgentCallEndpointType.Earpiece
            else -> throw IllegalArgumentException("Invalid route")
        }
        runtime.record(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.ROUTE_REQUESTED,
                route = type,
            ),
        )
        val accepted = routeRequester(type)
        return success(
            "route",
            "route" to extras.getValue(EXTRA_ROUTE),
            "accepted" to accepted.toString(),
        )
    }

    private fun finalize(extras: Map<String, String>): VoiceAutomationControlResult {
        requireExactKeys(extras, emptySet())
        requireActiveRun()
        runtime.finalizeRun()
        return success("finalize")
    }

    private fun dump(extras: Map<String, String>): VoiceAutomationControlResult {
        requireExactKeys(extras, emptySet())
        val file = artifactFile(runtime.status()) ?: throw IllegalStateException("Artifact unavailable")
        return VoiceAutomationControlResult(
            resultCode = RESULT_OK,
            resultData = output(
                "artifact_path" to file.absolutePath,
                "artifact_content" to file.readText(),
            ),
        )
    }

    private fun requireActiveRun() {
        check(runtime.status().state == VoiceAutomationRunState.Active) {
            "Automation run is not active"
        }
    }

    private fun requireExactKeys(extras: Map<String, String>, expected: Set<String>) {
        require(extras.keys == expected && extras.values.none(String::isBlank)) {
            "Missing or unexpected extras"
        }
    }

    private fun success(
        action: String,
        vararg fields: Pair<String, String>,
    ) = VoiceAutomationControlResult(
        resultCode = RESULT_OK,
        resultData = output("status" to "ok", "action" to action, *fields),
    )

    private fun VoiceAutomationRunState.wireName(): String = when (this) {
        VoiceAutomationRunState.Idle -> "idle"
        VoiceAutomationRunState.Active -> "active"
        VoiceAutomationRunState.Finalized -> "finalized"
    }

    companion object {
        const val ACTION_PREPARE = "me.rerere.rikkahub.voiceagent.automation.PREPARE"
        const val ACTION_STATUS = "me.rerere.rikkahub.voiceagent.automation.STATUS"
        const val ACTION_MARK = "me.rerere.rikkahub.voiceagent.automation.MARK"
        const val ACTION_ROUTE = "me.rerere.rikkahub.voiceagent.automation.ROUTE"
        const val ACTION_FINALIZE = "me.rerere.rikkahub.voiceagent.automation.FINALIZE"
        const val ACTION_DUMP = "me.rerere.rikkahub.voiceagent.automation.DUMP"

        const val EXTRA_RUN_HASH = "run_hash"
        const val EXTRA_COMPARISON_HASH = "comparison_hash"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_LIFECYCLE = "lifecycle"
        const val EXTRA_BOUNDARY = "boundary"
        const val EXTRA_ROUTE = "route"

        const val RESULT_OK = 0
        const val RESULT_ERROR = 1

        private val SCENARIO_BOUNDARIES = listOf(
            VoiceAutomationEventName.PROMPT_ENDED,
            VoiceAutomationEventName.INTERRUPT_STARTED,
            VoiceAutomationEventName.RECONNECT_STARTED,
            VoiceAutomationEventName.HANDOVER_STARTED,
            VoiceAutomationEventName.HANDOVER_CELLULAR_OBSERVED,
            VoiceAutomationEventName.HANDOVER_WIFI_RESTORED,
        ).associateBy { it.wireName }

        fun invalidRequest() = error("invalid_request")

        fun runtimeFailure() = error("runtime_failure")

        private fun error(reason: String) = VoiceAutomationControlResult(
            resultCode = RESULT_ERROR,
            resultData = output("status" to "error", "error" to reason),
        )

        private fun output(vararg fields: Pair<String, String>): String =
            fields.joinToString("\n") { (key, value) ->
                "$key=${value.escapeOutputValue()}"
            }

        private fun String.escapeOutputValue(): String =
            replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
    }
}
