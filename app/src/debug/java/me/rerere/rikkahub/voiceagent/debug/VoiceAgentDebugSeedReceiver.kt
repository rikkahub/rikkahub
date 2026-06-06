package me.rerere.rikkahub.voiceagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext

class VoiceAgentDebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEED_HERMES_PROVIDER) return

        val pendingResult = goAsync()
        thread(name = "voice-agent-debug-seed") {
            try {
                val apiKey = intent.getStringExtra(EXTRA_API_KEY)?.takeIf { it.isNotBlank() }
                    ?: error("Missing $EXTRA_API_KEY")
                val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_BASE_URL

                val settingsStore = GlobalContext.get().get<SettingsStore>()
                runBlocking {
                    val current = settingsStore.settingsFlowRaw.first { !it.init }
                    val model = Model(
                        id = HERMES_MODEL_ID,
                        modelId = "hermes-agent",
                        displayName = "Hermes Agent",
                        inputModalities = listOf(Modality.TEXT),
                        outputModalities = listOf(Modality.TEXT),
                        abilities = listOf(ModelAbility.TOOL),
                    )
                    val provider = ProviderSetting.OpenAI(
                        id = HERMES_PROVIDER_ID,
                        name = "Hermes Mobile API",
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        enabled = true,
                        models = listOf(model),
                    )
                    settingsStore.update(
                        current.copy(
                            chatModelId = HERMES_MODEL_ID,
                            fastModelId = HERMES_MODEL_ID,
                            providers = current.providers
                                .filterNot { it.id == HERMES_PROVIDER_ID || it.name == provider.name }
                                .plus(provider),
                            assistants = current.assistants.map { assistant ->
                                if (assistant.id == DEFAULT_ASSISTANT_ID) {
                                    assistant.copy(chatModelId = HERMES_MODEL_ID)
                                } else {
                                    assistant
                                }
                            },
                        )
                    )
                }
                Log.i(TAG, "debug_seed_hermes_provider result=success baseUrl=$baseUrl")
            } catch (error: Throwable) {
                Log.e(TAG, "debug_seed_hermes_provider failed: ${error.message ?: error.javaClass.simpleName}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SEED_HERMES_PROVIDER = "me.rerere.rikkahub.debug.voiceagent.SEED_HERMES_PROVIDER"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_BASE_URL = "base_url"
        const val DEFAULT_BASE_URL = "https://muly-hermes-api.core8.co/v1"
        val HERMES_PROVIDER_ID: Uuid = Uuid.parse("7fb50d0d-3d06-4e4d-9f8a-f7c1a2e4b201")
        val HERMES_MODEL_ID: Uuid = Uuid.parse("22b11ed9-91b7-44a7-a0d2-3e939dca89b2")
        private const val TAG = "VoiceAgentDebugSeed"
    }
}
