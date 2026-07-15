package me.rerere.rikkahub.voiceagent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

internal const val VOICE_AGENT_CALL_URI_SCHEME = "rikkahub-voice"
private const val VOICE_AGENT_CALL_URI_PREFIX = "voice-agent-"

interface VoiceAgentTelecomGateway {
    fun register(): Result<Unit>

    fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit>
}

class VoiceAgentTelecomAdapter(
    private val context: Context,
) : VoiceAgentTelecomGateway {
    private val handle: PhoneAccountHandle
        get() = PhoneAccountHandle(
            ComponentName(context, VoiceAgentConnectionService::class.java),
            "rikka-voice-agent",
        )

    override fun register(): Result<Unit> = runCatching {
        val telecomManager = requireTelecomManager()
        val account = PhoneAccount.builder(handle, "RikkaHub Voice Agent")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(VOICE_AGENT_CALL_URI_SCHEME)
            .build()
        telecomManager.registerPhoneAccount(account)
    }

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> =
        startCall(
            Uri.fromParts(
                VOICE_AGENT_CALL_URI_SCHEME,
                attemptId.toVoiceAgentTelecomSchemeSpecificPart(),
                null,
            ),
        )

    private fun startCall(address: Uri): Result<Unit> = runCatching {
        val telecomManager = requireTelecomManager()
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        check(context.checkSelfPermission(Manifest.permission.MANAGE_OWN_CALLS) == PackageManager.PERMISSION_GRANTED) {
            "MANAGE_OWN_CALLS permission is required to start a Voice Agent call"
        }
        telecomManager.placeCall(address, extras)
    }

    private fun requireTelecomManager(): TelecomManager =
        context.getSystemService(TelecomManager::class.java)
            ?: error("TelecomManager unavailable")
}

internal fun Uri?.voiceAgentTelecomAttemptIdOrNull(): VoiceAgentTelecomAttemptId? =
    voiceAgentTelecomAttemptIdOrNull(
        scheme = this?.scheme,
        schemeSpecificPart = this?.schemeSpecificPart,
    )

internal fun VoiceAgentTelecomAttemptId.toVoiceAgentTelecomSchemeSpecificPart(): String =
    "$VOICE_AGENT_CALL_URI_PREFIX$value"

internal fun voiceAgentTelecomAttemptIdOrNull(
    scheme: String?,
    schemeSpecificPart: String?,
): VoiceAgentTelecomAttemptId? {
    if (scheme != VOICE_AGENT_CALL_URI_SCHEME) return null
    if (schemeSpecificPart?.startsWith(VOICE_AGENT_CALL_URI_PREFIX) != true) return null
    val rawId = schemeSpecificPart.removePrefix(VOICE_AGENT_CALL_URI_PREFIX)
    if (rawId.isEmpty() || rawId.any { it !in '0'..'9' }) return null
    val value = rawId.toLongOrNull()?.takeIf { it > 0 } ?: return null
    return VoiceAgentTelecomAttemptId(value)
}
