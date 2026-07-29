package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventValidation
import me.rerere.rikkahub.voiceagent.telemetry.sha256Hex

internal data class VoiceDirectConfigurationBinding(
    val directAccountConfigurationHash: String,
    val conversationHash: String,
) {
    init {
        VoiceAutomationEventValidation.validateHash(
            "directAccountConfigurationHash",
            directAccountConfigurationHash,
        )
        VoiceAutomationEventValidation.validateHash("conversationHash", conversationHash)
    }
}

internal fun voiceConfigurationIdentity(value: String): String = "sha256:${sha256Hex(value)}"

internal fun voiceConfigurationStateIdentity(
    domain: String,
    vararg values: String,
): String = voiceConfigurationIdentity(
    buildString {
        append(domain.length).append(':').append(domain)
        values.forEach { value -> append(value.length).append(':').append(value) }
    },
)

internal fun JsonObject.directVoiceNameOrNull(): String? {
    val generationConfig = this["generationConfig"]?.jsonObject ?: this
    return generationConfig["speechConfig"]
        ?.jsonObject
        ?.get("voiceConfig")
        ?.jsonObject
        ?.get("prebuiltVoiceConfig")
        ?.jsonObject
        ?.get("voiceName")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
}
