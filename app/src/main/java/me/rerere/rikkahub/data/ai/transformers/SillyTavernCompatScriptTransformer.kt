package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.termux.TermuxCommandManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest
import me.rerere.rikkahub.data.ai.tools.termux.isSuccessful
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider

private const val TAG = "StCompatScript"
private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
private const val TERMUX_NODE_SETUP_HINT =
    "Setup checklist if this still fails: install Termux and install nodejs in Termux (pkg install nodejs)."

@Serializable
internal data class StCompatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class StCompatExecutionRequest(
    val scriptSource: String,
    val mainApi: String,
    val originalMainApi: String,
    val chatCompletionSource: String?,
    val providerName: String,
    val messages: List<StCompatMessage>,
    val extensionSettings: JsonObject,
)

@Serializable
private data class StCompatExecutionResponse(
    val ok: Boolean,
    val messages: List<StCompatMessage> = emptyList(),
    val extensionSettings: JsonObject = buildJsonObject { },
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

class SillyTavernCompatScriptTransformer(
    private val json: Json,
    private val settingsStore: SettingsStore,
    private val termuxCommandManager: TermuxCommandManager,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (!assistant.stCompatScriptEnabled) return messages

        val scriptSource = assistant.stCompatScriptSource.trim()
        if (scriptSource.isBlank()) return messages

        val provider = ctx.model.findProvider(ctx.settings.providers) ?: return messages
        val compatApi = provider.toCompatApi() ?: return messages
        val compatMessages = projectCompatMessages(messages) ?: run {
            Log.w(TAG, "Skipping ST compatibility script because the request contains non-text or tool messages.")
            return messages
        }

        val response = executeScript(
            request = StCompatExecutionRequest(
                scriptSource = scriptSource,
                mainApi = compatApi.mainApi,
                originalMainApi = compatApi.originalMainApi,
                chatCompletionSource = compatApi.chatCompletionSource,
                providerName = provider.name,
                messages = compatMessages,
                extensionSettings = assistant.stCompatExtensionSettings,
            )
        )

        response.logs.forEach { line ->
            Log.d(TAG, line)
        }

        if (!response.ok) {
            error("SillyTavern compatibility script failed: ${response.error.orEmpty()}")
        }

        persistExtensionSettingsIfNeeded(
            assistantId = assistant.id.toString(),
            current = assistant.stCompatExtensionSettings,
            updated = response.extensionSettings,
        )

        return applyCompatMessages(response.messages)
    }

    private suspend fun persistExtensionSettingsIfNeeded(
        assistantId: String,
        current: JsonObject,
        updated: JsonObject,
    ) {
        if (current == updated) return
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id.toString() == assistantId) {
                        assistant.copy(stCompatExtensionSettings = updated)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun executeScript(request: StCompatExecutionRequest): StCompatExecutionResponse {
        val payloadBase64 = Base64.encode(json.encodeToString(request).encodeToByteArray())
        val wrapperSource = buildNodeWrapperSource(payloadBase64)
        val settings = settingsStore.settingsFlow.value
        val result = termuxCommandManager.run(
            TermuxRunCommandRequest(
                commandPath = TERMUX_BASH_PATH,
                arguments = listOf("-lc", "node -"),
                workdir = settings.termuxWorkdir,
                stdin = wrapperSource,
                background = true,
                timeoutMs = settings.termuxTimeoutMs,
                label = "RikkaHub st_compat_script",
            )
        )

        val parsedResponse = result.stdout
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { stdout ->
                runCatching { json.decodeFromString<StCompatExecutionResponse>(stdout) }
                    .getOrNull()
            }

        if (!result.isSuccessful()) {
            val detail = parsedResponse?.error
                ?: result.stderr.ifBlank { result.errMsg ?: "Node execution failed" }
            error("$detail\n$TERMUX_NODE_SETUP_HINT")
        }

        return parsedResponse ?: error(
            "SillyTavern compatibility script returned invalid JSON.${if (result.stderr.isBlank()) "" else "\n${result.stderr}"}"
        )
    }

    private fun buildNodeWrapperSource(payloadBase64: String): String {
        return """
            const input = JSON.parse(Buffer.from(${payloadBase64.quoteJs()}, 'base64').toString('utf8'));
            const logs = [];
            const stringify = (value) => {
              if (typeof value === 'string') return value;
              try {
                return JSON.stringify(value);
              } catch (_error) {
                return String(value);
              }
            };
            const pushLog = (level, args) => {
              logs.push(`[${'$'}{level}] ${'$'}{args.map(stringify).join(' ')}`);
            };
            globalThis.console = {
              log: (...args) => pushLog('LOG', args),
              info: (...args) => pushLog('INFO', args),
              warn: (...args) => pushLog('WARN', args),
              error: (...args) => pushLog('ERROR', args),
              debug: (...args) => pushLog('DEBUG', args),
            };
            const clone = (value) => value == null ? value : JSON.parse(JSON.stringify(value));
            const listeners = new Map();
            const addListener = (event, listener, { once = false, prepend = false } = {}) => {
              const list = listeners.get(event) || [];
              const entry = { listener, once };
              if (prepend) {
                list.unshift(entry);
              } else {
                list.push(entry);
              }
              listeners.set(event, list);
              return listener;
            };
            const removeListener = (event, listener) => {
              const list = listeners.get(event);
              if (!list) return;
              listeners.set(event, list.filter(entry => entry.listener !== listener));
            };
            const emit = async (event, ...args) => {
              const list = [...(listeners.get(event) || [])];
              for (const entry of list) {
                await entry.listener(...args);
                if (entry.once) {
                  removeListener(event, entry.listener);
                }
              }
            };
            const sharedContext = {
              mainApi: input.mainApi,
              originalMainApi: input.originalMainApi,
              chatCompletionSource: input.chatCompletionSource,
              providerName: input.providerName,
              extensionSettings: clone(input.extensionSettings || {}),
            };
            const event_types = {
              CHAT_COMPLETION_SETTINGS_READY: 'chat_completion_settings_ready',
            };
            globalThis.extensions = {
              getContext: () => sharedContext,
              setting: () => ({ on: () => {} }),
              toastr: {
                info: (...args) => pushLog('TOAST', args),
                warning: (...args) => pushLog('TOAST', args),
                error: (...args) => pushLog('TOAST', args),
                success: (...args) => pushLog('TOAST', args),
              },
            };
            globalThis.script = {
              event_types,
              eventSource: {
                on: (event, listener) => addListener(event, listener),
                once: (event, listener) => addListener(event, listener, { once: true }),
                makeFirst: (event, listener) => addListener(event, listener, { prepend: true }),
                makeLast: (event, listener) => addListener(event, listener),
                removeListener,
                emit,
                emitAndWait: emit,
              },
              saveSettingsDebounced: () => {},
              callPopup: async () => false,
            };
            globalThis.window = globalThis;
            globalThis.self = globalThis;
            globalThis.global = globalThis;
            (async () => {
              try {
                (0, eval)(String(input.scriptSource || ''));
                const completion = { messages: clone(input.messages || []) };
                await emit(event_types.CHAT_COMPLETION_SETTINGS_READY, completion);
                process.stdout.write(JSON.stringify({
                  ok: true,
                  messages: completion.messages,
                  extensionSettings: sharedContext.extensionSettings,
                  logs,
                }));
              } catch (error) {
                process.stdout.write(JSON.stringify({
                  ok: false,
                  error: error?.stack || String(error),
                  logs,
                }));
                process.exitCode = 1;
              }
            })();
        """.trimIndent()
    }
}

internal fun projectCompatMessages(messages: List<UIMessage>): List<StCompatMessage>? {
    if (messages.any(::hasUnsupportedCompatParts)) {
        return null
    }
    return messages.map { message ->
        StCompatMessage(
            role = message.role.toCompatRole(),
            content = message.toText(),
        )
    }
}

internal fun applyCompatMessages(messages: List<StCompatMessage>): List<UIMessage> {
    return messages.mapNotNull { message ->
        message.role.toCompatMessageRole()?.let { role ->
            UIMessage(
                role = role,
                parts = listOf(UIMessagePart.Text(message.content))
            )
        }
    }
}

private fun hasUnsupportedCompatParts(message: UIMessage): Boolean {
    return message.parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> false
            is UIMessagePart.Reasoning -> false
            else -> true
        }
    }
}

internal fun MessageRole.toCompatRole(): String {
    return when (this) {
        MessageRole.SYSTEM -> "system"
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL -> "tool"
    }
}

internal fun String.toCompatMessageRole(): MessageRole? {
    return when (lowercase()) {
        "system" -> MessageRole.SYSTEM
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        "tool" -> MessageRole.TOOL
        else -> null
    }
}

internal data class StCompatApiContext(
    val mainApi: String,
    val originalMainApi: String,
    val chatCompletionSource: String? = null,
)

internal fun ProviderSetting.toCompatApi(): StCompatApiContext? {
    return when (this) {
        is ProviderSetting.OpenAI -> StCompatApiContext(
            mainApi = "openai",
            originalMainApi = "openai",
            chatCompletionSource = "openai",
        )

        is ProviderSetting.Google -> StCompatApiContext(
            mainApi = "openai",
            originalMainApi = "google",
            chatCompletionSource = if (vertexAI) "vertexai" else "makersuite",
        )

        is ProviderSetting.Claude -> StCompatApiContext(
            mainApi = "openai",
            originalMainApi = "claude",
            chatCompletionSource = "claude",
        )
    }
}

private fun String.quoteJs(): String {
    return buildString(length + 2) {
        append('\'')
        this@quoteJs.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('\'')
    }
}
