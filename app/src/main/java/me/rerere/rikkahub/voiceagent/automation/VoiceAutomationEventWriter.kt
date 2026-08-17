package me.rerere.rikkahub.voiceagent.automation

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifactPaths

internal interface VoiceAutomationEventSink {
    fun append(event: VoiceAutomationEvent)
}

internal class VoiceAutomationEventWriter private constructor(
    val file: File,
    private val runHash: String,
) : VoiceAutomationEventSink {
    private var previousMonotonicMs: Long? = null

    @Synchronized
    override fun append(event: VoiceAutomationEvent) {
        VoiceAutomationEventValidation.validate(event)
        require(event.runHash == runHash) { "Automation event run hash does not match its artifact" }
        check(previousMonotonicMs == null || event.monotonicMs > previousMonotonicMs!!) {
            "Automation event timestamps must be strictly monotonic"
        }

        val line = serialize(event)
        FileOutputStream(file, true).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.append(line)
            writer.append('\n')
            writer.flush()
        }
        previousMonotonicMs = event.monotonicMs
    }

    private fun serialize(event: VoiceAutomationEvent): String = buildString {
        appendNumber("schemaVersion", event.schemaVersion)
        appendNumber("monotonicMs", event.monotonicMs)
        appendNumber("wallClockMs", event.wallClockMs)
        appendString("runHash", event.runHash)
        appendString("comparisonHash", event.comparisonHash)
        appendString("requestedTransport", event.requestedTransport.wireName)
        appendNullableString("observedTransport", event.observedTransport?.wireName)
        appendString("name", event.name.wireName)
        appendNullableString("route", event.route?.name)
        appendNullableString("network", event.network?.wireName)
        appendNullableString("lifecycle", event.lifecycle?.wireName)
        appendNullableNumber("playbackEpoch", event.playbackEpoch)
        appendNullableNumber("byteCount", event.byteCount)
        appendNullableBoolean("rmsActive", event.rmsActive)
        appendNullableNumber("audioWindowMicros", event.audioWindowMicros)
        appendNullableBoolean("succeeded", event.succeeded)
        appendNullableNumber("reconnect_duration_ms", event.reconnectDurationMs)
        appendNullableString("failure_category", event.failureCategory)
        appendNullableString("failure_message", event.failureMessage)
        appendNullableString("correlationKind", event.correlationKind?.wireName)
        appendNullableString("correlationHash", event.correlationHash)
        appendNullableString("requestedModelHash", event.requestedModelHash)
        appendNullableString("observedModelHash", event.observedModelHash)
        appendNullableString("voiceHash", event.voiceHash)
        appendNullableString("instructionHash", event.instructionHash)
        appendNullableString("directAccountConfigurationHash", event.directAccountConfigurationHash)
        appendNullableString("conversationHash", event.conversationHash)
        appendNullableString("captureSource", event.captureSource)
        appendNullableNumber("micBytes", event.micBytes)
        appendNullableNumber("fixtureBytes", event.fixtureBytes)
        append('}')
    }

    private fun StringBuilder.appendNumber(name: String, value: Number) {
        appendFieldPrefix(name)
        append(value)
    }

    private fun StringBuilder.appendNullableNumber(name: String, value: Number?) {
        appendFieldPrefix(name)
        append(value ?: "null")
    }

    private fun StringBuilder.appendNullableBoolean(name: String, value: Boolean?) {
        appendFieldPrefix(name)
        append(value ?: "null")
    }

    private fun StringBuilder.appendString(name: String, value: String) {
        appendFieldPrefix(name)
        append('"').append(value).append('"')
    }

    private fun StringBuilder.appendNullableString(name: String, value: String?) {
        appendFieldPrefix(name)
        if (value == null) append("null") else append('"').append(value).append('"')
    }

    private fun StringBuilder.appendFieldPrefix(name: String) {
        if (isNotEmpty()) append(',') else append('{')
        append('"').append(name).append("\":")
    }

    companion object {
        fun create(noBackupFilesDir: File, runHash: String): VoiceAutomationEventWriter {
            VoiceAutomationEventValidation.validateHash("runHash", runHash)
            val file = File(
                noBackupFilesDir,
                "${VoiceE2EArtifactPaths.ROOT_DIRECTORY_NAME}/${runHash.removePrefix("sha256:")}/automation-events.jsonl",
            )
            val parent = checkNotNull(file.parentFile)
            check(parent.exists() || parent.mkdirs()) { "Unable to create automation artifact directory" }
            restrictToOwner(parent, directory = true)
            check(!file.exists() || file.length() == 0L) {
                "Automation event artifact already contains a run"
            }
            if (!file.exists()) check(file.createNewFile()) { "Unable to create automation event artifact" }
            restrictToOwner(file, directory = false)
            return VoiceAutomationEventWriter(file, runHash)
        }

        private fun restrictToOwner(file: File, directory: Boolean) {
            check(file.setReadable(false, false)) { "Unable to restrict automation artifact readability" }
            check(file.setWritable(false, false)) { "Unable to restrict automation artifact writability" }
            check(file.setExecutable(false, false)) { "Unable to restrict automation artifact executability" }
            check(file.setReadable(true, true)) { "Unable to set automation artifact owner readability" }
            check(file.setWritable(true, true)) { "Unable to set automation artifact owner writability" }
            if (directory) check(file.setExecutable(true, true)) {
                "Unable to set automation artifact owner directory access"
            }
        }
    }
}
