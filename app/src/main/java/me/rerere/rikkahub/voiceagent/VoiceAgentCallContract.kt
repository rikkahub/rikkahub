package me.rerere.rikkahub.voiceagent

import android.content.Context
import android.content.Intent
import kotlin.uuid.Uuid

object VoiceAgentCallContract {
    const val ACTION_START = "me.rerere.rikkahub.voiceagent.action.START"
    const val ACTION_END = "me.rerere.rikkahub.voiceagent.action.END"
    const val ACTION_END_BOUND = "me.rerere.rikkahub.voiceagent.action.END_BOUND"
    const val EXTRA_CONVERSATION_ID = "conversationId"
    const val EXTRA_TRANSPORT = "transport"
    const val EXTRA_CAPTURE_FIXTURE_TOKEN = "captureFixtureToken"
    const val EXTRA_RUN_HASH = "run_hash"
    const val EXTRA_COMPARISON_HASH = "comparison_hash"
    const val EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID = "voiceAgentConversationId"
    const val EXTRA_ROUTE_VOICE_AGENT_TRANSPORT = "voiceAgentTransport"
    const val NOTIFICATION_ID = 2401
}

fun voiceAgentCallStartIntent(
    context: Context,
    conversationId: String,
    transport: VoiceAgentTransport,
): Intent {
    val fields = encodeVoiceAgentCallStartFields(conversationId, transport)
    return Intent(context, VoiceAgentCallService::class.java)
        .setAction(VoiceAgentCallContract.ACTION_START)
        .putExtra(VoiceAgentCallContract.EXTRA_CONVERSATION_ID, fields.conversationId)
        .putExtra(VoiceAgentCallContract.EXTRA_TRANSPORT, fields.transportWireName)
}

fun voiceAgentCallEndIntent(context: Context): Intent =
    Intent(context, VoiceAgentCallService::class.java)
        .setAction(VoiceAgentCallContract.ACTION_END)

internal data class VoiceAgentCallStartFields(
    val conversationId: Uuid,
    val transport: VoiceAgentTransport,
    val captureFixtureToken: String?,
    val automationBinding: VoiceAgentAutomationBinding?,
)

internal data class EncodedVoiceAgentCallStartFields(
    val conversationId: String,
    val transportWireName: String,
)

internal data class VoiceAgentNotificationRouteFields(
    val conversationId: Uuid,
    val transport: VoiceAgentTransport,
)

internal data class EncodedVoiceAgentNotificationRouteFields(
    val conversationId: String,
    val transportWireName: String,
)

internal fun encodeVoiceAgentCallStartFields(
    conversationId: String,
    transport: VoiceAgentTransport,
): EncodedVoiceAgentCallStartFields = EncodedVoiceAgentCallStartFields(
    conversationId = conversationId,
    transportWireName = transport.wireName,
)

internal fun decodeVoiceAgentCallStartFields(
    conversationId: String?,
    transportWireName: String?,
    captureFixtureToken: String?,
    runHash: String? = null,
    comparisonHash: String? = null,
): VoiceAgentCallStartFields? {
    val parsedConversationId = conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val transport = VoiceAgentTransport.fromWireName(transportWireName) ?: return null
    val normalizedFixtureToken = captureFixtureToken?.trim()?.takeIf(String::isNotEmpty)
    if (captureFixtureToken != null && normalizedFixtureToken == null) return null
    val automationBinding = decodeVoiceAgentAutomationBinding(runHash, comparisonHash) ?: when {
        runHash == null && comparisonHash == null -> null
        else -> return null
    }
    return VoiceAgentCallStartFields(
        parsedConversationId,
        transport,
        normalizedFixtureToken,
        automationBinding,
    )
}

internal fun decodeVoiceAgentBoundCallIdentity(
    conversationId: String?,
    transportWireName: String?,
    runHash: String?,
    comparisonHash: String?,
): VoiceAgentBoundCallIdentity? {
    val parsedConversationId = conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val transport = VoiceAgentTransport.fromWireName(transportWireName) ?: return null
    val automationBinding = decodeVoiceAgentAutomationBinding(runHash, comparisonHash) ?: return null
    return VoiceAgentBoundCallIdentity(parsedConversationId, transport, automationBinding)
}

internal fun decodeVoiceAgentBoundCallIdentity(
    extras: Map<String, String?>,
): VoiceAgentBoundCallIdentity? {
    val expectedKeys = setOf(
        VoiceAgentCallContract.EXTRA_CONVERSATION_ID,
        VoiceAgentCallContract.EXTRA_TRANSPORT,
        VoiceAgentCallContract.EXTRA_RUN_HASH,
        VoiceAgentCallContract.EXTRA_COMPARISON_HASH,
    )
    if (extras.keys != expectedKeys) return null
    return decodeVoiceAgentBoundCallIdentity(
        conversationId = extras[VoiceAgentCallContract.EXTRA_CONVERSATION_ID],
        transportWireName = extras[VoiceAgentCallContract.EXTRA_TRANSPORT],
        runHash = extras[VoiceAgentCallContract.EXTRA_RUN_HASH],
        comparisonHash = extras[VoiceAgentCallContract.EXTRA_COMPARISON_HASH],
    )
}

private fun decodeVoiceAgentAutomationBinding(
    runHash: String?,
    comparisonHash: String?,
): VoiceAgentAutomationBinding? {
    if (runHash == null || comparisonHash == null) return null
    if (!VOICE_AGENT_AUTOMATION_HASH.matches(runHash) || !VOICE_AGENT_AUTOMATION_HASH.matches(comparisonHash)) {
        return null
    }
    return VoiceAgentAutomationBinding(runHash, comparisonHash)
}

internal fun encodeVoiceAgentNotificationRouteFields(
    conversationId: String,
    transport: VoiceAgentTransport,
): EncodedVoiceAgentNotificationRouteFields = EncodedVoiceAgentNotificationRouteFields(
    conversationId = conversationId,
    transportWireName = transport.wireName,
)

internal fun decodeVoiceAgentNotificationRouteFields(
    conversationId: String?,
    transportWireName: String?,
): VoiceAgentNotificationRouteFields? {
    val parsedConversationId = conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val transport = if (transportWireName == null) {
        VoiceAgentTransport.DirectGemini
    } else {
        VoiceAgentTransport.fromWireName(transportWireName) ?: return null
    }
    return VoiceAgentNotificationRouteFields(parsedConversationId, transport)
}

private val VOICE_AGENT_AUTOMATION_HASH = Regex("^sha256:[0-9a-f]{64}$")
