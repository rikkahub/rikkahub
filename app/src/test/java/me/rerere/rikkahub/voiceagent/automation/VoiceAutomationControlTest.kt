package me.rerere.rikkahub.voiceagent.automation

import java.io.File
import kotlin.io.path.createTempDirectory
import me.rerere.rikkahub.voiceagent.VoiceAgentCallEndpointType
import me.rerere.rikkahub.voiceagent.VoiceAgentCallLifecycle
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.debug.VoiceAutomationConnectivity
import me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControl
import me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAutomationControlTest {
    @Test
    fun `binding returns the conversation for a starting or active call`() {
        val conversationId = "11111111-1111-4111-8111-111111111111"

        listOf(
            VoiceAgentCallLifecycle.Starting(Uuid.parse(conversationId)),
            VoiceAgentCallLifecycle.Active(Uuid.parse(conversationId)),
        ).forEach { lifecycle ->
            val result = control(lifecycleReader = { lifecycle }).handle(
                VoiceAutomationControl.ACTION_BINDING,
                emptyMap(),
            )

            assertEquals(VoiceAutomationControl.RESULT_OK, result.resultCode)
            assertEquals(
                "status=ok\naction=binding\nconversation_id=$conversationId",
                result.resultData,
            )
        }
    }

    @Test
    fun `binding rejects call states without a current conversation`() {
        listOf(
            VoiceAgentCallLifecycle.Idle,
            VoiceAgentCallLifecycle.Stopping(
                Uuid.parse("22222222-2222-4222-8222-222222222222"),
            ),
            VoiceAgentCallLifecycle.CleanupFailed(IllegalStateException("cleanup failed")),
        ).forEach { lifecycle ->
            val result = control(lifecycleReader = { lifecycle }).handle(
                VoiceAutomationControl.ACTION_BINDING,
                emptyMap(),
            )

            assertEquals(VoiceAutomationControl.RESULT_ERROR, result.resultCode)
            assertEquals("status=error\nerror=invalid_state", result.resultData)
        }
    }

    @Test
    fun `prepare accepts only fixed hashes transport and lifecycle`() {
        val runtime = RecordingRuntime()
        val control = control(runtime)

        assertSuccess(
            control.handle(
                action = VoiceAutomationControl.ACTION_PREPARE,
                extras = mapOf(
                    VoiceAutomationControl.EXTRA_RUN_HASH to RUN_HASH,
                    VoiceAutomationControl.EXTRA_COMPARISON_HASH to COMPARISON_HASH,
                    VoiceAutomationControl.EXTRA_TRANSPORT to "direct_gemini",
                    VoiceAutomationControl.EXTRA_LIFECYCLE to "background",
                ),
            ),
        )

        assertEquals(
            VoiceAutomationRunBinding(
                runHash = RUN_HASH,
                comparisonHash = COMPARISON_HASH,
                requestedTransport = VoiceAgentTransport.DirectGemini,
            ),
            runtime.preparedBinding,
        )
        assertEquals(
            listOf(
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.LIFECYCLE_REQUESTED,
                    lifecycle = VoiceAutomationLifecycle.BACKGROUND,
                ),
            ),
            runtime.events,
        )
    }

    @Test
    fun `prepare writes requested lifecycle through the real runtime`() {
        val root = createTempDirectory("voice-automation-control-runtime").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, IncrementingClock())
        val control = VoiceAutomationControl(
            runtime = runtime,
            routeRequester = { false },
            connectivityReader = {
                VoiceAutomationConnectivity(VoiceAutomationNetwork.WIFI, true)
            },
            artifactFile = { status ->
                File(
                    root,
                    "voice-e2e/${status.runHash?.removePrefix("sha256:")}/automation-events.jsonl",
                )
            },
            lifecycleReader = { VoiceAgentCallLifecycle.Idle },
        )

        assertSuccess(
            control.handle(
                VoiceAutomationControl.ACTION_PREPARE,
                validPrepareExtras(),
            ),
        )

        assertEquals(VoiceAutomationRunState.Active, runtime.status().state)
        assertEquals(2, runtime.status().eventCount)
        val artifact = File(
            root,
            "voice-e2e/${RUN_HASH.removePrefix("sha256:")}/automation-events.jsonl",
        ).readLines()
        assertTrue(artifact[0].contains("\"name\":\"run_prepared\""))
        assertTrue(artifact[1].contains("\"name\":\"lifecycle_requested\""))
        assertTrue(artifact[1].contains("\"lifecycle\":\"foreground\""))
    }

    @Test
    fun `prepare accepts exactly both transport wire values`() {
        listOf(
            "direct_gemini" to VoiceAgentTransport.DirectGemini,
            "livekit_experimental" to VoiceAgentTransport.LiveKitExperimental,
        ).forEach { (wireName, expected) ->
            val runtime = RecordingRuntime()

            assertSuccess(
                control(runtime).handle(
                    VoiceAutomationControl.ACTION_PREPARE,
                    validPrepareExtras().plus(VoiceAutomationControl.EXTRA_TRANSPORT to wireName),
                ),
            )

            assertEquals(expected, runtime.preparedBinding?.requestedTransport)
        }
    }

    @Test
    fun `failed next prepare preserves finalized status and dump artifact`() {
        val root = createTempDirectory("voice-automation-control-transaction").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, IncrementingClock())
        val control = VoiceAutomationControl(
            runtime = runtime,
            routeRequester = { false },
            connectivityReader = {
                VoiceAutomationConnectivity(VoiceAutomationNetwork.WIFI, true)
            },
            artifactFile = { status ->
                status.runHash?.let { runHash ->
                    File(
                        root,
                        "voice-e2e/${runHash.removePrefix("sha256:")}/automation-events.jsonl",
                    ).takeIf(File::isFile)
                }
            },
            lifecycleReader = { VoiceAgentCallLifecycle.Idle },
        )
        assertSuccess(control.handle(VoiceAutomationControl.ACTION_PREPARE, validPrepareExtras()))
        assertSuccess(control.handle(VoiceAutomationControl.ACTION_FINALIZE, emptyMap()))
        val finalizedArtifact = File(
            root,
            "voice-e2e/${RUN_HASH.removePrefix("sha256:")}/automation-events.jsonl",
        )
        val finalizedContent = finalizedArtifact.readText()
        File(
            root,
            "voice-e2e/${NEXT_RUN_HASH.removePrefix("sha256:")}/automation-events.jsonl",
        ).apply {
            checkNotNull(parentFile).mkdirs()
            writeText("occupied")
        }

        val failedPrepare = control.handle(
            VoiceAutomationControl.ACTION_PREPARE,
            validPrepareExtras().plus(VoiceAutomationControl.EXTRA_RUN_HASH to NEXT_RUN_HASH),
        )

        assertEquals(VoiceAutomationControl.RESULT_ERROR, failedPrepare.resultCode)
        assertEquals("status=error\nerror=invalid_state", failedPrepare.resultData)
        val status = control.handle(VoiceAutomationControl.ACTION_STATUS, emptyMap())
        assertEquals("finalized", status.resultData.lineValue("run_state"))
        assertEquals(RUN_HASH, status.resultData.lineValue("run_hash"))
        val dump = control.handle(VoiceAutomationControl.ACTION_DUMP, emptyMap())
        assertSuccess(dump)
        assertEquals(finalizedArtifact.absolutePath, dump.resultData.lineValue("artifact_path"))
        assertEquals(
            finalizedContent.replace("\\", "\\\\").replace("\n", "\\n"),
            dump.resultData.lineValue("artifact_content"),
        )
    }

    @Test
    fun `prepare rejects missing malformed raw and unexpected extras`() {
        val malformed = listOf(
            validPrepareExtras() - VoiceAutomationControl.EXTRA_RUN_HASH,
            validPrepareExtras() - VoiceAutomationControl.EXTRA_COMPARISON_HASH,
            validPrepareExtras() - VoiceAutomationControl.EXTRA_TRANSPORT,
            validPrepareExtras() - VoiceAutomationControl.EXTRA_LIFECYCLE,
            validPrepareExtras().plus(VoiceAutomationControl.EXTRA_RUN_HASH to "raw-run-id"),
            validPrepareExtras().plus(VoiceAutomationControl.EXTRA_COMPARISON_HASH to "comparison-1"),
            validPrepareExtras().plus(VoiceAutomationControl.EXTRA_TRANSPORT to "DirectGemini"),
            validPrepareExtras().plus(VoiceAutomationControl.EXTRA_TRANSPORT to "livekit"),
            validPrepareExtras().plus(VoiceAutomationControl.EXTRA_LIFECYCLE to "Foreground"),
            validPrepareExtras().plus(VoiceAutomationControl.EXTRA_LIFECYCLE to "steady"),
            validPrepareExtras().plus("conversation_id" to "raw-conversation-id"),
        )

        malformed.forEach { extras ->
            val runtime = RecordingRuntime()
            assertInvalid(control(runtime).handle(VoiceAutomationControl.ACTION_PREPARE, extras))
            assertEquals(null, runtime.preparedBinding)
            assertTrue(runtime.events.isEmpty())
        }
    }

    @Test
    fun `route accepts only speaker and earpiece and records request before routing`() {
        val events = mutableListOf<String>()
        val runtime = RecordingRuntime(onRecord = { events += "record:${it.route}" })
        val control = control(runtime) { route ->
            events += "route:$route"
            true
        }

        assertEquals(
            listOf("speaker", "earpiece"),
            listOf(VoiceAgentCallEndpointType.Speaker, VoiceAgentCallEndpointType.Earpiece).map { route ->
                events.clear()
                val result = control.handle(
                    VoiceAutomationControl.ACTION_ROUTE,
                    mapOf(VoiceAutomationControl.EXTRA_ROUTE to route.name.lowercase()),
                )
                assertSuccess(result)
                assertEquals(listOf("record:$route", "route:$route"), events)
                result.resultData.lineValue("route")
            },
        )
    }

    @Test
    fun `route rejects missing malformed and unexpected extras`() {
        listOf(
            emptyMap(),
            mapOf(VoiceAutomationControl.EXTRA_ROUTE to ""),
            mapOf(VoiceAutomationControl.EXTRA_ROUTE to "Speaker"),
            mapOf(VoiceAutomationControl.EXTRA_ROUTE to "bluetooth"),
            mapOf(VoiceAutomationControl.EXTRA_ROUTE to "speaker", "transport" to "direct_gemini"),
        ).forEach { extras ->
            assertInvalid(control().handle(VoiceAutomationControl.ACTION_ROUTE, extras))
        }
    }

    @Test
    fun `mark accepts only closed scenario boundaries`() {
        val runtime = RecordingRuntime()
        val control = control(runtime)

        val accepted = mapOf(
            "prompt_ended" to VoiceAutomationEventInput(VoiceAutomationEventName.PROMPT_ENDED),
            "interrupt_started" to VoiceAutomationEventInput(VoiceAutomationEventName.INTERRUPT_STARTED),
            "reconnect_started" to VoiceAutomationEventInput(VoiceAutomationEventName.RECONNECT_STARTED),
            "handover_started" to VoiceAutomationEventInput(VoiceAutomationEventName.HANDOVER_STARTED),
            "handover_cellular_observed" to VoiceAutomationEventInput(
                VoiceAutomationEventName.HANDOVER_CELLULAR_OBSERVED,
                network = VoiceAutomationNetwork.CELLULAR,
            ),
            "handover_wifi_restored" to VoiceAutomationEventInput(
                VoiceAutomationEventName.HANDOVER_WIFI_RESTORED,
                network = VoiceAutomationNetwork.WIFI,
            ),
        )
        accepted.forEach { (wireName, event) ->
            assertSuccess(
                control.handle(
                    VoiceAutomationControl.ACTION_MARK,
                    mapOf(
                        VoiceAutomationControl.EXTRA_BOUNDARY to wireName,
                        VoiceAutomationControl.EXTRA_RUN_HASH to RUN_HASH,
                    ),
                ),
            )
            assertEquals(event, runtime.events.last())
        }

        listOf(
            emptyMap(),
            mapOf(VoiceAutomationControl.EXTRA_BOUNDARY to "INTERRUPT_STARTED"),
            mapOf(VoiceAutomationControl.EXTRA_BOUNDARY to "call_active"),
            mapOf(VoiceAutomationControl.EXTRA_BOUNDARY to "failure"),
            mapOf(VoiceAutomationControl.EXTRA_BOUNDARY to "reconnect_media_restored"),
            mapOf(VoiceAutomationControl.EXTRA_BOUNDARY to "handover_media_restored"),
            mapOf(VoiceAutomationControl.EXTRA_BOUNDARY to "dropout_started"),
            mapOf(VoiceAutomationControl.EXTRA_BOUNDARY to "dropout_ended"),
            mapOf(
                VoiceAutomationControl.EXTRA_BOUNDARY to "prompt_ended",
                VoiceAutomationControl.EXTRA_RUN_HASH to RUN_HASH,
                "raw_id" to "secret",
            ),
        ).forEach { extras ->
            assertInvalid(control.handle(VoiceAutomationControl.ACTION_MARK, extras))
        }

        runtime.currentStatus = runtime.currentStatus.copy(runHash = NEXT_RUN_HASH)
        val stale = control.handle(
            VoiceAutomationControl.ACTION_MARK,
            mapOf(
                VoiceAutomationControl.EXTRA_BOUNDARY to "reconnect_started",
                VoiceAutomationControl.EXTRA_RUN_HASH to RUN_HASH,
            ),
        )
        assertEquals("status=error\nerror=invalid_state", stale.resultData)
    }

    @Test
    fun `status records observed connectivity and validation then returns allowlisted state`() {
        val runtime = RecordingRuntime(
            currentStatus = VoiceAutomationStatus(
                state = VoiceAutomationRunState.Active,
                runHash = RUN_HASH,
                comparisonHash = COMPARISON_HASH,
                requestedTransport = VoiceAgentTransport.LiveKitExperimental,
                eventCount = 4,
            ),
        )
        val control = VoiceAutomationControl(
            runtime = runtime,
            routeRequester = { false },
            connectivityReader = {
                VoiceAutomationConnectivity(
                    network = VoiceAutomationNetwork.CELLULAR,
                    validated = true,
                )
            },
            artifactFile = { null },
            lifecycleReader = { VoiceAgentCallLifecycle.Idle },
        )

        val result = control.handle(VoiceAutomationControl.ACTION_STATUS, emptyMap())

        assertSuccess(result)
        assertEquals(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.NETWORK_OBSERVED,
                network = VoiceAutomationNetwork.CELLULAR,
                succeeded = true,
            ),
            runtime.events.single(),
        )
        assertEquals("active", result.resultData.lineValue("run_state"))
        assertEquals(RUN_HASH, result.resultData.lineValue("run_hash"))
        assertEquals(COMPARISON_HASH, result.resultData.lineValue("comparison_hash"))
        assertEquals("livekit_experimental", result.resultData.lineValue("requested_transport"))
        assertEquals("5", result.resultData.lineValue("event_count"))
        assertEquals("cellular", result.resultData.lineValue("network"))
        assertEquals("true", result.resultData.lineValue("validated"))
        assertEquals(
            setOf(
                "status",
                "action",
                "run_state",
                "run_hash",
                "comparison_hash",
                "requested_transport",
                "event_count",
                "network",
                "validated",
            ),
            result.resultData.lineKeys(),
        )
    }

    @Test
    fun `status reports none and false when connectivity is not validated`() {
        val result = control(
            runtime = RecordingRuntime(
                currentStatus = VoiceAutomationStatus(VoiceAutomationRunState.Idle),
            ),
            connectivity = VoiceAutomationConnectivity(VoiceAutomationNetwork.NONE, false),
        ).handle(VoiceAutomationControl.ACTION_STATUS, emptyMap())

        assertSuccess(result)
        assertEquals("none", result.resultData.lineValue("network"))
        assertEquals("false", result.resultData.lineValue("validated"))
    }

    @Test
    fun `finalize and dump expose only sanitized artifact path and content`() {
        val root = createTempDirectory("voice-automation-control").toFile()
        val artifact = File(root, "automation-events.jsonl").apply {
            writeText("""{"runHash":"$RUN_HASH"}""" + "\n")
        }
        val runtime = RecordingRuntime(finalizedFile = artifact)
        val control = VoiceAutomationControl(
            runtime = runtime,
            routeRequester = { false },
            connectivityReader = {
                VoiceAutomationConnectivity(VoiceAutomationNetwork.NONE, false)
            },
            artifactFile = { artifact },
            lifecycleReader = { VoiceAgentCallLifecycle.Idle },
        )

        assertSuccess(control.handle(VoiceAutomationControl.ACTION_FINALIZE, emptyMap()))
        val dump = control.handle(VoiceAutomationControl.ACTION_DUMP, emptyMap())

        assertSuccess(dump)
        assertEquals(
            setOf("artifact_path", "artifact_content"),
            dump.resultData.lineKeys(),
        )
        assertEquals(artifact.absolutePath, dump.resultData.lineValue("artifact_path"))
        assertEquals(
            """{"runHash":"$RUN_HASH"}\n""",
            dump.resultData.lineValue("artifact_content"),
        )
        assertFalse(dump.resultData.contains("conversation"))
    }

    @Test
    fun `bound finalize rejects an exact active binding until call stopped succeeds`() {
        val root = createTempDirectory("voice-automation-control-bound-not-stopped").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, IncrementingClock())
        runtime.prepare(
            VoiceAutomationRunBinding(
                RUN_HASH,
                COMPARISON_HASH,
                VoiceAgentTransport.LiveKitExperimental,
            ),
        )
        val control = realControl(runtime)

        val notStopped = control.handle(
            VoiceAutomationControl.ACTION_FINALIZE_BOUND,
            boundFinalizeExtras(),
        )

        assertEquals(VoiceAutomationControl.RESULT_ERROR, notStopped.resultCode)
        assertEquals("status=rejected\nreason=call_not_stopped", notStopped.resultData)
        assertEquals(VoiceAutomationRunState.Active, runtime.status().state)

        assertTrue(
            runtime.recordIfActiveRun(
                RUN_HASH,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.CALL_STOPPED,
                    succeeded = false,
                ),
            ),
        )
        val failedStop = control.handle(
            VoiceAutomationControl.ACTION_FINALIZE_BOUND,
            boundFinalizeExtras(),
        )

        assertEquals(VoiceAutomationControl.RESULT_ERROR, failedStop.resultCode)
        assertEquals("status=rejected\nreason=call_not_stopped", failedStop.resultData)
        assertEquals(VoiceAutomationRunState.Active, runtime.status().state)
    }

    @Test
    fun `bound finalize reports binding mismatch before and after successful call stopped`() {
        val root = createTempDirectory("voice-automation-control-bound-stale").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, IncrementingClock())
        runtime.prepare(
            VoiceAutomationRunBinding(
                RUN_HASH,
                COMPARISON_HASH,
                VoiceAgentTransport.LiveKitExperimental,
            ),
        )

        val stale = realControl(runtime).handle(
            VoiceAutomationControl.ACTION_FINALIZE_BOUND,
            boundFinalizeExtras() + (VoiceAutomationControl.EXTRA_RUN_HASH to NEXT_RUN_HASH),
        )

        assertEquals(VoiceAutomationControl.RESULT_ERROR, stale.resultCode)
        assertEquals("status=rejected\nreason=binding_mismatch", stale.resultData)
        assertEquals(VoiceAutomationRunState.Active, runtime.status().state)

        assertTrue(
            runtime.recordIfActiveRun(
                RUN_HASH,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.CALL_STOPPED,
                    succeeded = true,
                ),
            ),
        )
        val staleAfterStop = realControl(runtime).handle(
            VoiceAutomationControl.ACTION_FINALIZE_BOUND,
            boundFinalizeExtras() + (VoiceAutomationControl.EXTRA_RUN_HASH to NEXT_RUN_HASH),
        )

        assertEquals(VoiceAutomationControl.RESULT_ERROR, staleAfterStop.resultCode)
        assertEquals("status=rejected\nreason=binding_mismatch", staleAfterStop.resultData)
        assertEquals(VoiceAutomationRunState.Active, runtime.status().state)
        assertEquals(2, runtime.status().eventCount)
    }

    @Test
    fun `bound finalize accepts only exact extras and successful call stopped`() {
        val root = createTempDirectory("voice-automation-control-bound-success").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, IncrementingClock())
        runtime.prepare(
            VoiceAutomationRunBinding(
                RUN_HASH,
                COMPARISON_HASH,
                VoiceAgentTransport.LiveKitExperimental,
            ),
        )
        val control = realControl(runtime)
        val exact = boundFinalizeExtras()

        listOf(
            exact - VoiceAutomationControl.EXTRA_RUN_HASH,
            exact - VoiceAutomationControl.EXTRA_COMPARISON_HASH,
            exact - VoiceAutomationControl.EXTRA_TRANSPORT,
            exact + ("unexpected" to "value"),
            exact + (VoiceAutomationControl.EXTRA_TRANSPORT to "livekit"),
        ).forEach { extras ->
            assertInvalid(control.handle(VoiceAutomationControl.ACTION_FINALIZE_BOUND, extras))
        }
        assertEquals(VoiceAutomationRunState.Active, runtime.status().state)
        assertTrue(
            runtime.recordIfActiveRun(
                RUN_HASH,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.CALL_STOPPED,
                    succeeded = true,
                ),
            ),
        )

        val finalized = control.handle(VoiceAutomationControl.ACTION_FINALIZE_BOUND, exact)

        assertSuccess(finalized)
        assertEquals("status=ok\naction=finalize", finalized.resultData)
        assertEquals(VoiceAutomationRunState.Finalized, runtime.status().state)
    }

    @Test
    fun `legacy finalize remains available before call stopped`() {
        val root = createTempDirectory("voice-automation-control-legacy-finalize").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, IncrementingClock())
        runtime.prepare(
            VoiceAutomationRunBinding(
                RUN_HASH,
                COMPARISON_HASH,
                VoiceAgentTransport.LiveKitExperimental,
            ),
        )

        val finalized = realControl(runtime).handle(
            VoiceAutomationControl.ACTION_FINALIZE,
            emptyMap(),
        )

        assertSuccess(finalized)
        assertEquals("status=ok\naction=finalize", finalized.resultData)
        assertEquals(VoiceAutomationRunState.Finalized, runtime.status().state)
        assertEquals(2, runtime.status().eventCount)
    }

    @Test
    fun `actions without extras reject unexpected fields and unknown actions`() {
        listOf(
            VoiceAutomationControl.ACTION_BINDING,
            VoiceAutomationControl.ACTION_STATUS,
            VoiceAutomationControl.ACTION_FINALIZE,
            VoiceAutomationControl.ACTION_DUMP,
        ).forEach { action ->
            assertInvalid(control().handle(action, mapOf("raw_id" to "secret")))
        }
        assertInvalid(control().handle(null, emptyMap()))
        assertInvalid(control().handle("me.rerere.rikkahub.voiceagent.automation.UNKNOWN", emptyMap()))
    }

    private fun control(
        runtime: RecordingRuntime = RecordingRuntime(),
        connectivity: VoiceAutomationConnectivity =
            VoiceAutomationConnectivity(VoiceAutomationNetwork.WIFI, true),
        lifecycleReader: () -> VoiceAgentCallLifecycle = { VoiceAgentCallLifecycle.Idle },
        routeRequester: (VoiceAgentCallEndpointType) -> Boolean = { true },
    ) = VoiceAutomationControl(
        runtime = runtime,
        routeRequester = routeRequester,
        connectivityReader = { connectivity },
        artifactFile = { runtime.finalizedFile },
        lifecycleReader = lifecycleReader,
    )

    private fun validPrepareExtras() = mapOf(
        VoiceAutomationControl.EXTRA_RUN_HASH to RUN_HASH,
        VoiceAutomationControl.EXTRA_COMPARISON_HASH to COMPARISON_HASH,
        VoiceAutomationControl.EXTRA_TRANSPORT to "direct_gemini",
        VoiceAutomationControl.EXTRA_LIFECYCLE to "foreground",
    )

    private fun boundFinalizeExtras() = mapOf(
        VoiceAutomationControl.EXTRA_RUN_HASH to RUN_HASH,
        VoiceAutomationControl.EXTRA_COMPARISON_HASH to COMPARISON_HASH,
        VoiceAutomationControl.EXTRA_TRANSPORT to VoiceAgentTransport.LiveKitExperimental.wireName,
    )

    private fun realControl(runtime: VoiceAutomationRuntime) = VoiceAutomationControl(
        runtime = runtime,
        routeRequester = { false },
        connectivityReader = {
            VoiceAutomationConnectivity(VoiceAutomationNetwork.NONE, false)
        },
        artifactFile = { null },
        lifecycleReader = { VoiceAgentCallLifecycle.Idle },
    )

    private fun assertSuccess(result: VoiceAutomationControlResult) {
        assertEquals(VoiceAutomationControl.RESULT_OK, result.resultCode)
    }

    private fun assertInvalid(result: VoiceAutomationControlResult) {
        assertEquals(VoiceAutomationControl.RESULT_ERROR, result.resultCode)
        assertEquals("status=error\nerror=invalid_request", result.resultData)
    }

    private fun String.lineKeys(): Set<String> =
        lineSequence().map { it.substringBefore('=') }.toSet()

    private fun String.lineValue(key: String): String =
        lineSequence().single { it.startsWith("$key=") }.substringAfter('=')

    private class RecordingRuntime(
        var currentStatus: VoiceAutomationStatus = VoiceAutomationStatus(
            state = VoiceAutomationRunState.Active,
            runHash = RUN_HASH,
            comparisonHash = COMPARISON_HASH,
            requestedTransport = VoiceAgentTransport.DirectGemini,
        ),
        val finalizedFile: File = createTempDirectory("voice-automation-runtime").resolve("events.jsonl").toFile(),
        private val onRecord: (VoiceAutomationEventInput) -> Unit = {},
    ) : VoiceAutomationRuntime {
        var preparedBinding: VoiceAutomationRunBinding? = null
        val events = mutableListOf<VoiceAutomationEventInput>()

        override fun prepare(binding: VoiceAutomationRunBinding) {
            preparedBinding = binding
            currentStatus = VoiceAutomationStatus(
                state = VoiceAutomationRunState.Active,
                runHash = binding.runHash,
                comparisonHash = binding.comparisonHash,
                requestedTransport = binding.requestedTransport,
            )
        }

        override fun record(event: VoiceAutomationEventInput) {
            events += event
            currentStatus = currentStatus.copy(eventCount = currentStatus.eventCount + 1)
            onRecord(event)
        }

        override fun status(): VoiceAutomationStatus = currentStatus

        override fun finalizeRun(): File {
            currentStatus = currentStatus.copy(state = VoiceAutomationRunState.Finalized)
            return finalizedFile
        }

        override fun finalizeRunIfMatches(binding: VoiceAutomationRunBinding): File? {
            if (
                currentStatus.state != VoiceAutomationRunState.Active ||
                currentStatus.runHash != binding.runHash ||
                currentStatus.comparisonHash != binding.comparisonHash ||
                currentStatus.requestedTransport != binding.requestedTransport
            ) {
                return null
            }
            return finalizeRun()
        }

        override fun reset() {
            currentStatus = VoiceAutomationStatus(VoiceAutomationRunState.Idle)
        }
    }

    private class IncrementingClock : VoiceAutomationClock {
        private var now = 1L

        override fun monotonicMs(): Long = now++

        override fun wallClockMs(): Long = now++
    }

    private companion object {
        const val RUN_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NEXT_RUN_HASH = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val COMPARISON_HASH =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
