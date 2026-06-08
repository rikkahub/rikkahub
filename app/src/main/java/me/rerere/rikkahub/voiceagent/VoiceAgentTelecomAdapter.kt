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

class VoiceAgentTelecomAdapter(
    private val context: Context,
) {
    private val handle: PhoneAccountHandle
        get() = PhoneAccountHandle(
            ComponentName(context, VoiceAgentConnectionService::class.java),
            "rikka-voice-agent",
        )

    fun register(): Result<Unit> = runCatching {
        val telecomManager = requireTelecomManager()
        val account = PhoneAccount.builder(handle, "RikkaHub Voice Agent")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme(VOICE_AGENT_CALL_URI_SCHEME)
            .build()
        telecomManager.registerPhoneAccount(account)
    }

    fun startCall(): Result<Unit> = runCatching {
        val telecomManager = requireTelecomManager()
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        check(context.checkSelfPermission(Manifest.permission.MANAGE_OWN_CALLS) == PackageManager.PERMISSION_GRANTED) {
            "MANAGE_OWN_CALLS permission is required to start a Voice Agent call"
        }
        telecomManager.placeCall(Uri.fromParts(VOICE_AGENT_CALL_URI_SCHEME, "voice-agent", null), extras)
    }

    private fun requireTelecomManager(): TelecomManager =
        context.getSystemService(TelecomManager::class.java)
            ?: error("TelecomManager unavailable")

    private companion object {
        const val VOICE_AGENT_CALL_URI_SCHEME = "rikkahub-voice"
    }
}
