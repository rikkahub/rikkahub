package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
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
import me.rerere.rikkahub.data.model.effectiveUserName

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
internal data class StCompatChatMessage(
    val name: String,
    @SerialName("is_user")
    val isUser: Boolean,
    @SerialName("is_system")
    val isSystem: Boolean,
    val mes: String,
    val extra: JsonObject = buildJsonObject { },
)

@Serializable
private data class StCompatExecutionRequest(
    val scriptSource: String,
    val mainApi: String,
    val originalMainApi: String,
    val chatCompletionSource: String?,
    val providerName: String,
    val modelId: String,
    val userName: String,
    val charName: String,
    val groupNames: List<String>,
    val messages: List<StCompatMessage>,
    val chat: List<StCompatChatMessage>,
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
        val userName = ctx.settings.effectiveUserName().ifBlank { "User" }
        val charName = ctx.assistant.stCharacterData?.name
            ?.takeIf { it.isNotBlank() }
            ?: ctx.assistant.name.ifBlank { "Assistant" }

        val response = executeScript(
            request = StCompatExecutionRequest(
                scriptSource = scriptSource,
                mainApi = compatApi.mainApi,
                originalMainApi = compatApi.originalMainApi,
                chatCompletionSource = compatApi.chatCompletionSource,
                providerName = provider.name,
                modelId = ctx.model.modelId,
                userName = userName,
                charName = charName,
                groupNames = emptyList(),
                messages = compatMessages,
                chat = buildCompatContextChat(
                    messages = compatMessages,
                    userName = userName,
                    charName = charName,
                ),
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
            const noop = () => {};
            const immediate = (listener, type, target) => {
              if (typeof listener !== 'function') return;
              Promise.resolve().then(() => listener.call(target, { type }));
            };
            const knownEventTypes = {
              CHAT_COMPLETION_SETTINGS_READY: 'chat_completion_settings_ready',
              GENERATE_AFTER_DATA: 'generate_after_data',
              SETTINGS_UPDATED: 'settings_updated',
              OAI_PRESET_CHANGED_AFTER: 'oai_preset_changed_after',
              CHAT_CHANGED: 'chat_id_changed',
              USER_MESSAGE_RENDERED: 'user_message_rendered',
              CHARACTER_MESSAGE_RENDERED: 'character_message_rendered',
              MESSAGE_UPDATED: 'message_updated',
              MESSAGE_SWIPED: 'message_swiped',
            };
            const event_types = new Proxy(knownEventTypes, {
              get(target, prop) {
                if (typeof prop !== 'string') return target[prop];
                return prop in target ? target[prop] : prop.toLowerCase();
              },
            });
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
            const removeAllListeners = (event) => {
              if (typeof event === 'undefined') {
                listeners.clear();
                return;
              }
              listeners.delete(event);
            };
            const emit = async (event, ...args) => {
              const list = [...(listeners.get(event) || [])];
              for (const entry of list) {
                if (typeof entry.listener !== 'function') continue;
                await entry.listener(...args);
                if (entry.once) {
                  removeListener(event, entry.listener);
                }
              }
            };
            const listenerCount = (event) => (listeners.get(event) || []).length;
            const makeEventTarget = (target, readyEvents = []) => {
              const handlers = new Map();
              target.addEventListener = (type, listener) => {
                const list = handlers.get(type) || [];
                list.push(listener);
                handlers.set(type, list);
                if (readyEvents.includes(type)) {
                  immediate(listener, type, target);
                }
              };
              target.removeEventListener = (type, listener) => {
                const list = handlers.get(type);
                if (!list) return;
                handlers.set(type, list.filter(entry => entry !== listener));
              };
              target.dispatchEvent = (event) => {
                const evt = typeof event === 'string' ? { type: event } : (event || { type: '' });
                const list = [...(handlers.get(evt.type) || [])];
                list.forEach(listener => {
                  if (typeof listener === 'function') {
                    listener.call(target, evt);
                  }
                });
                const inlineHandler = target['on' + evt.type];
                if (typeof inlineHandler === 'function') {
                  inlineHandler.call(target, evt);
                }
                return true;
              };
              return target;
            };
            const makeClassList = () => {
              const names = new Set();
              const sync = () => Array.from(names).join(' ');
              return {
                add: (...values) => values.forEach(value => names.add(String(value))),
                remove: (...values) => values.forEach(value => names.delete(String(value))),
                contains: (value) => names.has(String(value)),
                toggle: (value, force) => {
                  const normalized = String(value);
                  if (force === true) {
                    names.add(normalized);
                    return true;
                  }
                  if (force === false) {
                    names.delete(normalized);
                    return false;
                  }
                  if (names.has(normalized)) {
                    names.delete(normalized);
                    return false;
                  }
                  names.add(normalized);
                  return true;
                },
                toString: () => sync(),
              };
            };
            const makeTextNode = (text = '') => ({
              nodeType: 3,
              textContent: String(text),
              parentNode: null,
            });
            const makeElement = (tagName = 'div') => {
              const element = makeEventTarget({
                nodeType: 1,
                tagName: String(tagName).toUpperCase(),
                style: {},
                dataset: {},
                attributes: {},
                childNodes: [],
                children: [],
                parentNode: null,
                textContent: '',
                innerHTML: '',
                value: '',
                checked: false,
                id: '',
                type: '',
                focus: noop,
                blur: noop,
                querySelector: () => null,
                querySelectorAll: () => [],
                closest: () => null,
                appendChild(child) {
                  if (child == null) return child;
                  this.childNodes.push(child);
                  this.children.push(child);
                  child.parentNode = this;
                  return child;
                },
                append(...items) {
                  items.forEach(item => {
                    if (item == null) return;
                    this.appendChild(typeof item === 'string' ? makeTextNode(item) : item);
                  });
                  return this;
                },
                removeChild(child) {
                  this.childNodes = this.childNodes.filter(entry => entry !== child);
                  this.children = this.children.filter(entry => entry !== child);
                  if (child) {
                    child.parentNode = null;
                  }
                  return child;
                },
                setAttribute(name, value) {
                  const normalized = String(name);
                  const stringValue = String(value);
                  this.attributes[normalized] = stringValue;
                  if (normalized === 'id') {
                    this.id = stringValue;
                  }
                  if (normalized === 'type') {
                    this.type = stringValue;
                  }
                  if (normalized === 'class') {
                    this.className = stringValue;
                  }
                },
                getAttribute(name) {
                  return this.attributes[String(name)] ?? null;
                },
                removeAttribute(name) {
                  delete this.attributes[String(name)];
                },
                cloneNode() {
                  return makeElement(tagName);
                },
              });
              const classList = makeClassList();
              Object.defineProperty(element, 'classList', {
                value: classList,
                enumerable: true,
              });
              Object.defineProperty(element, 'className', {
                get() {
                  return classList.toString();
                },
                set(value) {
                  const values = String(value || '').split(/\s+/).filter(Boolean);
                  classList.remove(...String(classList).split(/\s+/).filter(Boolean));
                  classList.add(...values);
                },
                enumerable: true,
              });
              return element;
            };
            const makeCollection = (items = []) => {
              const api = Array.from(items);
              api.find = () => makeCollection([]);
              api.children = () => makeCollection([]);
              api.each = (callback) => {
                api.forEach((item, index) => callback.call(item, index, item));
                return api;
              };
              api.val = (value) => {
                if (typeof value === 'undefined') {
                  return api[0]?.value ?? '';
                }
                api.forEach(item => {
                  if (item) item.value = value;
                });
                return api;
              };
              api.prop = (name, value) => {
                if (typeof value === 'undefined') {
                  return api[0]?.[name];
                }
                api.forEach(item => {
                  if (item) item[name] = value;
                });
                return api;
              };
              api.attr = (name, value) => {
                if (typeof value === 'undefined') {
                  return api[0]?.getAttribute?.(name);
                }
                api.forEach(item => item?.setAttribute?.(name, value));
                return api;
              };
              api.trigger = (eventName) => {
                api.forEach(item => item?.dispatchEvent?.({ type: eventName }));
                return api;
              };
              api.append = (...children) => {
                api.forEach(item => item?.append?.(...children));
                return api;
              };
              api.on = (eventName, listener) => {
                api.forEach(item => item?.addEventListener?.(eventName, listener));
                return api;
              };
              api.text = (value) => {
                if (typeof value === 'undefined') {
                  return api.map(item => item?.textContent ?? '').join('');
                }
                api.forEach(item => {
                  if (item) item.textContent = String(value);
                });
                return api;
              };
              api.html = (value) => {
                if (typeof value === 'undefined') {
                  return api[0]?.innerHTML ?? '';
                }
                api.forEach(item => {
                  if (item) item.innerHTML = String(value);
                });
                return api;
              };
              return api;
            };
            const jq = (value) => {
              if (typeof value === 'function') {
                value();
                return makeCollection([]);
              }
              if (Array.isArray(value)) return makeCollection(value);
              if (value == null) return makeCollection([]);
              if (typeof value === 'string') return makeCollection([]);
              return makeCollection([value]);
            };
            class MutationObserver {
              constructor(callback) {
                this.callback = callback;
              }
              observe() {}
              disconnect() {}
            }
            class SimpleEvent {
              constructor(type, init = {}) {
                this.type = type;
                Object.assign(this, init);
              }
            }
            const toContextChat = (messages) => (messages || []).map(message => ({
              name: message.role === 'assistant'
                ? input.charName
                : message.role === 'user'
                  ? input.userName
                  : message.role === 'system'
                    ? 'System'
                    : 'Tool',
              is_user: message.role === 'user',
              is_system: message.role === 'system',
              mes: String(message.content || ''),
              extra: {},
            }));
            const sharedContext = {
              mainApi: input.mainApi,
              originalMainApi: input.originalMainApi,
              chatCompletionSource: input.chatCompletionSource,
              chat_completion_source: input.chatCompletionSource,
              providerName: input.providerName,
              model: input.modelId,
              name1: input.userName,
              name2: input.charName,
              userName: input.userName,
              charName: input.charName,
              groupNames: clone(input.groupNames || []),
              group_names: clone(input.groupNames || []),
              chat: clone((input.chat && input.chat.length) ? input.chat : toContextChat(input.messages || [])),
              extensionSettings: clone(input.extensionSettings || {}),
              extensionPrompts: {},
              chatMetadata: {},
              tags: [],
              tagMap: {},
              onlineStatus: 'connected',
              maxContext: 0,
              getCurrentChatId: () => 'st-compat-chat',
              getRequestHeaders: () => ({ 'Content-Type': 'application/json' }),
              saveChat: async () => {},
              saveMetadata: async () => {},
              saveSettingsDebounced: async () => {},
              activateSendButtons: noop,
              deactivateSendButtons: noop,
            };
            const eventSource = {
              on: (event, listener) => addListener(event, listener),
              once: (event, listener) => addListener(event, listener, { once: true }),
              makeFirst: (event, listener) => addListener(event, listener, { prepend: true }),
              makeLast: (event, listener) => addListener(event, listener),
              removeListener,
              off: removeListener,
              removeAllListeners,
              emit,
              emitAndWait: emit,
              listenerCount,
              listeners: (event) => (listeners.get(event) || []).map(entry => entry.listener),
            };
            const registeredSettings = [];
            const makeSettingBuilder = () => {
              const builder = {
                on(listener) {
                  if (typeof listener === 'function') {
                    registeredSettings.push(listener);
                  }
                  return builder;
                },
                button: () => builder,
                text: () => builder,
                label: () => builder,
                icon: () => builder,
                title: () => builder,
                description: () => builder,
                section: () => builder,
                group: () => builder,
                menu: () => builder,
              };
              return builder;
            };
            const setExtensionPrompt = async (promptId, content, position, depth, scan = false, role = 0, filter = () => true) => {
              sharedContext.extensionPrompts[promptId] = {
                value: String(content ?? ''),
                position,
                depth,
                scan: Boolean(scan),
                role: Number(role),
                filter,
              };
            };
            const writeExtensionField = async (_characterId, key, value) => {
              if (typeof value === 'undefined') {
                delete sharedContext.extensionSettings[key];
              } else {
                sharedContext.extensionSettings[key] = value;
              }
            };
            const documentElement = makeElement('html');
            const documentBody = makeElement('body');
            const documentHead = makeElement('head');
            const document = makeEventTarget({
              readyState: 'complete',
              body: documentBody,
              head: documentHead,
              documentElement,
              createElement: (tagName) => makeElement(tagName),
              createTextNode: (text) => makeTextNode(text),
              getElementById: () => null,
              querySelector: () => null,
              querySelectorAll: () => [],
            }, ['DOMContentLoaded', 'readystatechange']);
            globalThis.window = makeEventTarget(globalThis, ['load']);
            globalThis.self = globalThis;
            globalThis.global = globalThis;
            globalThis.parent = globalThis;
            globalThis.top = globalThis;
            globalThis.window.parent = globalThis;
            globalThis.window.top = globalThis;
            globalThis.document = document;
            globalThis.Event = SimpleEvent;
            globalThis.CustomEvent = SimpleEvent;
            globalThis.MutationObserver = MutationObserver;
            globalThis.location = globalThis.location || { href: 'about:blank', reload: noop };
            globalThis.alert = (...args) => pushLog('ALERT', args);
            globalThis.confirm = (...args) => {
              pushLog('CONFIRM', args);
              return false;
            };
            globalThis.prompt = (_message, defaultValue = '') => String(defaultValue ?? '');
            if (typeof globalThis.navigator === 'undefined') {
              globalThis.navigator = {};
            }
            if (!globalThis.navigator.clipboard) {
              globalThis.navigator.clipboard = {
                writeText: async (text) => pushLog('CLIPBOARD', [text]),
              };
            }
            globalThis.extensions = {
              getContext: () => sharedContext,
              setting: () => makeSettingBuilder(),
              setExtensionPrompt,
              writeExtensionField,
              toastr: {
                info: (...args) => pushLog('TOAST', args),
                warning: (...args) => pushLog('TOAST', args),
                error: (...args) => pushLog('TOAST', args),
                success: (...args) => pushLog('TOAST', args),
              },
            };
            globalThis.script = {
              event_types,
              eventSource,
              saveSettingsDebounced: async () => {},
              callPopup: async () => false,
            };
            globalThis.SillyTavern = {
              getContext: () => sharedContext,
              eventSource,
              eventTypes: event_types,
              saveSettingsDebounced: async () => {},
              setExtensionPrompt,
              writeExtensionField,
              getRequestHeaders: sharedContext.getRequestHeaders,
              mainApi: sharedContext.mainApi,
              extensionSettings: sharedContext.extensionSettings,
              chat: sharedContext.chat,
              name1: sharedContext.name1,
              name2: sharedContext.name2,
            };
            globalThis.eventSource = eventSource;
            globalThis.event_types = event_types;
            globalThis.saveSettingsDebounced = async () => {};
            globalThis.getContext = () => sharedContext;
            globalThis.setExtensionPrompt = setExtensionPrompt;
            globalThis.getRequestHeaders = sharedContext.getRequestHeaders;
            globalThis['${'$'}'] = jq;
            globalThis.jQuery = jq;
            (async () => {
              try {
                (0, eval)(String(input.scriptSource || ''));
                const completion = {
                  messages: clone(input.messages || []),
                  model: input.modelId,
                  temprature: 1,
                  temperature: 1,
                  frequency_penalty: 0,
                  presence_penalty: 0,
                  top_p: 1,
                  max_tokens: 0,
                  stream: true,
                  logit_bias: {},
                  stop: [],
                  chat_completion_source: input.chatCompletionSource || input.mainApi,
                  chat_comletion_source: input.chatCompletionSource || input.mainApi,
                  n: 1,
                  user_name: input.userName,
                  char_name: input.charName,
                  group_names: clone(input.groupNames || []),
                  include_reasoning: false,
                  reasoning_effort: 'medium',
                  json_schema: null,
                };
                await emit(event_types.CHAT_COMPLETION_SETTINGS_READY, completion);
                sharedContext.chat = toContextChat(completion.messages);
                globalThis.SillyTavern.chat = sharedContext.chat;
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
    if (messages.any { !it.hasProjectableCompatText() }) {
        return null
    }
    return messages.map { message ->
        StCompatMessage(
            role = message.role.toCompatRole(),
            content = message.toCompatText(),
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

internal fun buildCompatContextChat(
    messages: List<StCompatMessage>,
    userName: String,
    charName: String,
): List<StCompatChatMessage> {
    return messages.map { message ->
        message.toCompatChatMessage(
            userName = userName,
            charName = charName,
        )
    }
}

internal fun StCompatMessage.toCompatChatMessage(
    userName: String,
    charName: String,
): StCompatChatMessage {
    return StCompatChatMessage(
        name = when (role.lowercase()) {
            "user" -> userName
            "assistant" -> charName
            "system" -> "System"
            "tool" -> "Tool"
            else -> role.replaceFirstChar { it.uppercase() }
        },
        isUser = role.equals("user", ignoreCase = true),
        isSystem = role.equals("system", ignoreCase = true),
        mes = content,
    )
}

private fun hasUnsupportedCompatParts(message: UIMessage): Boolean {
    return message.parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> false
            is UIMessagePart.Document -> false
            is UIMessagePart.Reasoning -> false
            else -> true
        }
    }
}

private fun UIMessage.hasProjectableCompatText(): Boolean {
    return parts.any { it is UIMessagePart.Text }
}

private fun UIMessage.toCompatText(): String {
    return copy(parts = parts.filterIsInstance<UIMessagePart.Text>()).toText()
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
