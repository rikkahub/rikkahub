package me.rerere.locallm.litert

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.locallm.AcceleratorProbe
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

/** Role labels used in [Turn] signatures. Kept as plain strings so [turnSignature] is a
 *  pure function the provider and the runtime can both call without sharing an enum. */
const val ROLE_USER = "user"
const val ROLE_ASSISTANT = "assistant"

/**
 * Stable, content-derived signature for one conversation turn. Used to decide whether the
 * warm [Conversation]'s KV cache can be reused (see [planTurns]). Length + hashCode keeps
 * the collision rate negligible; a collision would only cause a (correct) cold reload, never
 * wrong output.
 */
fun turnSignature(role: String, text: String): String = "$role|${text.length}|${text.hashCode()}"

/**
 * One rendered conversation turn handed from [LiteRtProvider] to [LiteRtRuntime].
 *
 * [rawText] is the turn's content WITHOUT any "User:/Assistant:" markers — when this turn is
 * the single new turn appended to a warm Conversation, [rawText] is sent as-is and the SDK's
 * chat template applies the role wrapping (the same path Gallery uses). The marker-prefixed
 * cold blob is built separately by the provider for the rebuild-from-scratch path.
 */
data class Turn(val role: String, val rawText: String) {
    val signature: String get() = turnSignature(role, rawText)
}

/**
 * Outcome of [planTurns]: whether the live [Conversation]'s KV cache can be reused.
 */
sealed interface TurnPlan {
    /** Reuse the warm Conversation; send only `history[sendFromIndex]` (guaranteed exactly
     *  one new turn). The KV cache already holds every turn before [sendFromIndex]. */
    data class Warm(val sendFromIndex: Int) : TurnPlan

    /** Recreate the Conversation (clearing the KV cache) and send the full cold blob. */
    data object Cold : TurnPlan
}

/**
 * Thrown when a `.litertlm` model file is structurally broken in a way that retrying
 * with a different accelerator won't fix (e.g. corrupt tokenizer data, invalid magic
 * number, schema mismatch). Callers should treat [modelPath] as permanently unloadable,
 * delete the file from disk, and remove it from the provider's models list.
 */
class LiteRtModelCorruptException(
    val modelPath: String,
    cause: Throwable,
) : RuntimeException(
    "LiteRT model file appears corrupt or incompatible: ${cause.message}",
    cause,
)

/**
 * Wraps Google's LiteRT-LM runtime (com.google.ai.edge.litertlm:litertlm-android:0.11.0)
 * for on-device inference of `.litertlm` model files.
 *
 * # Why this rewrite (vs. the simpler v22A original)
 *
 * The original implementation made three SDK-misuse mistakes Gallery's
 * `LlmChatModelHelper` does NOT make. Each was silently broken on production devices:
 *
 *   1. **Skipped `engine.initialize()`.** `Engine(config)` returns a partially-constructed
 *      handle; only `initialize()` actually loads tokenizer, KV cache, and weights into the
 *      backend.
 *   2. **Did not pass `EngineConfig.maxNumTokens`.** The SDK falls back to an internal default
 *      (~16) when `maxNumTokens` is null, so users saw "I" then nothing.
 *   3. **Inlined the system prompt as `User: …\nAssistant: …` plain text.** The LiteRT-LM
 *      runtime ships per-model chat templates; without `systemInstruction` going through the
 *      template engine, Qwen2.5 (and any chat-tuned model) emits gibberish.
 *
 * # KV-cache reuse across turns (the perf rewrite)
 *
 * The original recreated the [Conversation] on every [ensureLoaded] call, so every turn
 * re-prefilled the ENTIRE conversation history from a cold KV cache — turn N paid for
 * turns 1..N every time. Gallery instead keeps ONE Conversation alive and sends only the
 * new user message each turn, so the KV cache stays warm and each turn prefills only its
 * own new tokens.
 *
 * This runtime now does the same. The Conversation is kept across turns when the
 * (model, accelerator, sampler, system instruction) tuple is unchanged. [streamTurns]
 * compares the caller's full turn list against [LoadedModel.processed] (the signatures the
 * live Conversation has already consumed): a clean single-turn append reuses the warm KV
 * cache; anything else (a new chat, an edited/regenerated turn, a tool round-trip, a config
 * change) falls back to recreating the Conversation and re-sending the full history. The
 * cold path is always correct — the warm path is purely an optimisation, and a signature
 * mismatch can only cost a cold reload, never produce wrong output.
 *
 * # Concurrency
 *
 * A single [mutex] serialises all access. The mutex is held for the full duration of each
 * inference, so [LoadedModel.processed] is only ever touched by one coroutine at a time.
 */
class LiteRtRuntime(private val context: Context) {

    private val mutex = Mutex()
    private var loaded: LoadedModel? = null

    /**
     * In-session fallback accelerator. If the preferred/probed accelerator failed and we
     * successfully fell back to CPU, remember that result for the rest of this session so
     * subsequent loads skip the GPU-init attempt. Resets on app restart (acceptable for v1).
     */
    @Volatile private var sessionFallbackAccelerator: String? = null

    /**
     * Snapshot of every config bit that requires Engine teardown. The Engine is the
     * expensive resource (cold-load 10-60s); changing any of these forces a full reload.
     */
    private data class EngineKey(
        val modelPath: String,
        val accelerator: String,
        val maxNumTokens: Int,
        val supportImage: Boolean,
        val supportAudio: Boolean,
        val speculativeDecoding: Boolean,
    )

    /**
     * Snapshot of every config bit that requires only Conversation recreation (cheap) — NOT
     * an Engine reload. When this is unchanged the warm Conversation (and its KV cache) is
     * kept across [ensureLoaded] calls.
     */
    private data class ConversationKey(
        val systemInstructionText: String?,
        val constrainedDecoding: Boolean,
        val topK: Int,
        val topP: Double,
        val temperature: Double,
    )

    /** Everything needed to (re)build a [Conversation] for the cold path without re-deriving
     *  it from scattered call parameters. */
    private data class ConversationSpec(
        val systemInstruction: Contents?,
        val tools: List<ToolProvider>,
        val constrainedDecoding: Boolean,
        val topK: Int,
        val topP: Double,
        val temperature: Double,
    )

    private class LoadedModel(
        val engineKey: EngineKey,
        var conversationKey: ConversationKey,
        var conversationSpec: ConversationSpec,
        val engine: Engine,
        var conversation: Conversation,
        /** Signatures of the turns the live Conversation's KV cache currently holds, in
         *  order — the caller-supplied history turns plus one synthetic assistant turn for
         *  the response generated last. Cleared whenever the Conversation is recreated. */
        val processed: MutableList<String> = mutableListOf(),
    )

    /**
     * Inspect a raw engine throwable and re-wrap it as [LiteRtModelCorruptException] if the
     * error message indicates a structural file problem that won't be fixed by switching
     * accelerators or retrying. Returns the original throwable unchanged for transient /
     * hardware errors so the GPU→CPU fallback still works.
     */
    private fun classifyEngineError(modelPath: String, t: Throwable): Throwable {
        val msg = t.message.orEmpty()
        val isCorrupt =
            msg.contains("Invalid magic number", ignoreCase = true) ||
            msg.contains("Failed to decompress", ignoreCase = true) ||
            msg.contains("No KV cache inputs found", ignoreCase = true) ||
            msg.contains("FAILED_PRECONDITION", ignoreCase = true) ||
            (msg.contains("INVALID_ARGUMENT", ignoreCase = true) &&
                (msg.contains("tokenizer", ignoreCase = true) ||
                 msg.contains("Section not found", ignoreCase = true) ||
                 msg.contains("Uncompressed size", ignoreCase = true)))
        return if (isCorrupt) LiteRtModelCorruptException(modelPath, t) else t
    }

    /**
     * Map our internal accelerator label → the SDK's [Backend] sealed-class instance.
     *
     * NPU/TPU both map to `Backend.NPU` with the app's native library dir, matching
     * Gallery's `LlmChatModelHelper.kt`: both labels share the QNN delegate loader path.
     */
    private fun acceleratorToBackend(accel: String): Backend = when (accel) {
        "QNN", "NPU", "TPU" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        "GPU" -> Backend.GPU()
        else -> Backend.CPU()
    }

    /**
     * Configure the engine + conversation for the next [streamTurns] call.
     *
     * Engine reuse: when every [EngineKey] field matches, the (expensive) Engine is kept.
     * Conversation reuse: when the [ConversationKey] ALSO matches, the warm Conversation —
     * and its KV cache — is kept untouched, so the next [streamTurns] can warm-continue.
     * When only the ConversationKey changed (system instruction / sampler), the Engine is
     * kept but the Conversation is recreated and [LoadedModel.processed] is cleared.
     *
     * When the preferred/probed accelerator fails (e.g. GPU OpenCL/OpenGL stack absent),
     * automatically retries with CPU. The fallback is remembered in-session.
     *
     * Returns the resolved accelerator label that was actually used.
     */
    @OptIn(ExperimentalApi::class) // ExperimentalFlags.* + Capabilities.hasSpeculativeDecodingSupport
    suspend fun ensureLoaded(
        modelPath: String,
        preferredAccel: String? = null,
        forceCpu: Boolean = false,
        maxNumTokens: Int = 4096,
        supportImage: Boolean = false,
        supportAudio: Boolean = false,
        speculativeDecoding: Boolean = false,
        systemInstructionText: String? = null,
        tools: List<ToolProvider> = emptyList(),
        constrainedDecoding: Boolean = false,
        topK: Int = 64,
        topP: Double = 0.95,
        temperature: Double = 1.0,
    ): String = mutex.withLock {
        // Use in-session fallback if a prior GPU→CPU retry already succeeded this session.
        // forceCpu wins over a non-null preferredAccel.
        val accel = if (forceCpu) "CPU"
        else sessionFallbackAccelerator
            ?: preferredAccel
            ?: AcceleratorProbe.probeLiteRt(context)

        // Probe the file for speculative-decoding support BEFORE building the engine.
        val supportsSpeculativeDecoding = try {
            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
        } catch (_: Throwable) {
            false
        }
        val effectiveSpeculativeDecoding = speculativeDecoding && supportsSpeculativeDecoding

        val desiredEngineKey = EngineKey(
            modelPath = modelPath,
            accelerator = accel,
            maxNumTokens = maxNumTokens,
            supportImage = supportImage,
            supportAudio = supportAudio,
            speculativeDecoding = effectiveSpeculativeDecoding,
        )
        val systemInstruction: Contents? =
            if (!systemInstructionText.isNullOrBlank()) Contents.of(systemInstructionText) else null
        val desiredConversationKey = ConversationKey(
            systemInstructionText = systemInstructionText?.takeIf { it.isNotBlank() },
            constrainedDecoding = constrainedDecoding,
            topK = topK,
            topP = topP,
            temperature = temperature,
        )
        val desiredConversationSpec = ConversationSpec(
            systemInstruction = systemInstruction,
            tools = tools,
            constrainedDecoding = constrainedDecoding,
            topK = topK,
            topP = topP,
            temperature = temperature,
        )

        val current = loaded
        if (current != null && current.engineKey == desiredEngineKey) {
            if (current.conversationKey == desiredConversationKey) {
                // Full reuse — Engine AND Conversation kept. The KV cache (and therefore
                // [processed]) is left intact so the next streamTurns can warm-continue.
                return@withLock accel
            }
            // Engine kept, Conversation config changed — recreate just the Conversation.
            // The KV cache is gone, so [processed] must be cleared.
            try { current.conversation.close() } catch (_: Throwable) {}
            current.conversation = createConversationWithFlags(
                engine = current.engine,
                backend = acceleratorToBackend(current.engineKey.accelerator),
                spec = desiredConversationSpec,
            )
            current.conversationKey = desiredConversationKey
            current.conversationSpec = desiredConversationSpec
            current.processed.clear()
            return@withLock accel
        }

        // Engine swap path: tear down any prior Engine + Conversation.
        try { current?.conversation?.close() } catch (_: Throwable) {}
        try { current?.engine?.close() } catch (_: Throwable) {}
        loaded = null

        // Try the preferred accelerator first; fall back to CPU if it isn't already CPU.
        val firstAttempt = runCatching {
            tryLoadWithBackend(desiredEngineKey, accel, desiredConversationSpec)
        }
        val loadResult = firstAttempt.getOrElse { firstError ->
            if (accel == "CPU") {
                throw classifyEngineError(
                    modelPath,
                    RuntimeException(
                        "LiteRT engine could not load this model on this device's GPU OR CPU. " +
                        "This usually means the model file is packaged for a different runtime version. " +
                        "Try a different model from the Gallery allowlist (tap Install default, or " +
                        "paste a litert-community/ HuggingFace URL). " +
                        "Underlying: ${firstError.message}",
                        firstError,
                    )
                )
            }
            val classified = classifyEngineError(modelPath, firstError)
            if (classified is LiteRtModelCorruptException) throw classified

            android.util.Log.w(
                "LiteRtRuntime",
                "Engine init failed on $accel (${firstError.message}); retrying on CPU",
            )
            try {
                tryLoadWithBackend(
                    desiredEngineKey.copy(accelerator = "CPU"),
                    "CPU",
                    desiredConversationSpec,
                )
            } catch (cpuError: Throwable) {
                throw classifyEngineError(
                    modelPath,
                    RuntimeException(
                        "LiteRT engine could not load this model on this device's GPU OR CPU. " +
                        "This usually means the model file is packaged for a different runtime version. " +
                        "Try a different model from the Gallery allowlist (tap Install default, or " +
                        "paste a litert-community/ HuggingFace URL). " +
                        "Underlying: ${cpuError.message}",
                        cpuError,
                    )
                )
            }
        }

        // If we fell back to CPU from a non-CPU accelerator, cache that decision in-session.
        if (loadResult.accelerator != accel) {
            sessionFallbackAccelerator = loadResult.accelerator
        }

        loaded = LoadedModel(
            engineKey = desiredEngineKey.copy(accelerator = loadResult.accelerator),
            conversationKey = desiredConversationKey,
            conversationSpec = desiredConversationSpec,
            engine = loadResult.engine,
            conversation = loadResult.conversation,
        )
        loadResult.accelerator
    }

    private class LoadResult(
        val engine: Engine,
        val conversation: Conversation,
        val accelerator: String,
    )

    /**
     * Build + initialize an Engine, then open a Conversation. Throws if either step fails.
     *
     * Mirrors Gallery's `LlmChatModelHelper.initialize()`:
     *   - EngineConfig with explicit `maxNumTokens`
     *   - `cacheDir` only when modelPath sits in `/data/local/tmp`
     *   - the `enableSpeculativeDecoding` flag dance around construct + initialize
     */
    @OptIn(ExperimentalApi::class)
    private suspend fun tryLoadWithBackend(
        engineKey: EngineKey,
        accel: String,
        conversationSpec: ConversationSpec,
    ): LoadResult {
        val backend = acceleratorToBackend(accel)
        // Vision backend MUST be GPU for Gemma 3n; audio backend MUST be CPU (Gallery's
        // mandate). Leave each null when the caller didn't request that modality so the
        // engine doesn't allocate the corresponding executor memory.
        val visionBackend: Backend? = if (engineKey.supportImage) Backend.GPU() else null
        val audioBackend: Backend? = if (engineKey.supportAudio) Backend.CPU() else null

        val engineConfig = EngineConfig(
            modelPath = engineKey.modelPath,
            backend = backend,
            visionBackend = visionBackend,
            audioBackend = audioBackend,
            maxNumTokens = engineKey.maxNumTokens,
            cacheDir = if (engineKey.modelPath.startsWith("/data/local/tmp"))
                context.getExternalFilesDir(null)?.absolutePath
            else null,
        )

        val engine = withContext(Dispatchers.IO) {
            // The flag dance: set BEFORE constructing the Engine, reset AFTER initialize().
            ExperimentalFlags.enableSpeculativeDecoding = engineKey.speculativeDecoding
            val e = try {
                Engine(engineConfig).also { built ->
                    try {
                        built.initialize()
                    } catch (t: Throwable) {
                        // Close the partially-constructed engine to release its native
                        // handle before propagating.
                        try { built.close() } catch (_: Throwable) {}
                        throw t
                    }
                }
            } finally {
                ExperimentalFlags.enableSpeculativeDecoding = false
            }
            e
        }

        val conv = createConversationWithFlags(
            engine = engine,
            backend = backend,
            spec = conversationSpec,
        )
        return LoadResult(engine, conv, accel)
    }

    /**
     * Build a Conversation with the constrained-decoding flag dance.
     *
     * Sampler choice mirrors Gallery:
     *   - NPU backend → samplerConfig MUST be null.
     *   - GPU/CPU backend → explicit SamplerConfig with the caller's topK/topP/temperature.
     */
    @OptIn(ExperimentalApi::class)
    private fun createConversationWithFlags(
        engine: Engine,
        backend: Backend,
        spec: ConversationSpec,
    ): Conversation {
        ExperimentalFlags.enableConversationConstrainedDecoding = spec.constrainedDecoding
        return try {
            engine.createConversation(
                ConversationConfig(
                    samplerConfig = if (backend is Backend.NPU) {
                        null
                    } else {
                        SamplerConfig(
                            topK = spec.topK,
                            topP = spec.topP,
                            temperature = spec.temperature,
                        )
                    },
                    systemInstruction = spec.systemInstruction,
                    tools = spec.tools,
                )
            )
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    /** Close + rebuild the live Conversation in place, clearing its KV cache. Caller must
     *  hold [mutex] and must clear [LoadedModel.processed] itself. */
    private fun recreateConversationLocked(instance: LoadedModel) {
        try { instance.conversation.close() } catch (_: Throwable) {}
        instance.conversation = createConversationWithFlags(
            engine = instance.engine,
            backend = acceleratorToBackend(instance.engineKey.accelerator),
            spec = instance.conversationSpec,
        )
    }

    /**
     * Stream one assistant response.
     *
     * [history] is the FULL turn list for the conversation (already trimmed to the model's
     * context budget by the caller). The runtime decides — via [planTurns] — whether the
     * live Conversation's KV cache already holds a prefix of [history]:
     *
     *  - **Warm:** the cache holds everything except exactly one newly-appended turn → send
     *    only that turn's [Turn.rawText]; the SDK's chat template applies role wrapping and
     *    the prior turns are reused from the cache (Gallery's behaviour).
     *  - **Cold:** anything else (first turn, config change, edited/regenerated history,
     *    a tool round-trip, media inputs) → recreate the Conversation and send [coldBlob],
     *    the caller's full marker-formatted render of the history.
     *
     * Each emitted String is the **cumulative** response so far, NOT a delta — same contract
     * as Gallery's `partialResult`. Downstream consumers compute deltas themselves.
     *
     * Caller MUST have called [ensureLoaded] first. The [mutex] is held for the whole
     * inference so two concurrent callers queue up rather than racing the Conversation.
     */
    fun streamTurns(
        history: List<Turn>,
        coldBlob: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onThinking: ((String) -> Unit)? = null,
    ): Flow<String> = callbackFlow {
        // GPU-boost the inference. Three levers, in order of effectiveness:
        //   1. PerformanceHintManager (API 33+). Opens a hint session for this thread
        //      with a 50ms target — the OS scheduler treats the process as doing
        //      sustained compute and refuses to downclock GPU/CPU like it would for a
        //      productivity app. This is exactly what Android game engines use.
        //   2. THREAD_PRIORITY_URGENT_DISPLAY on the calling thread. LiteRT's native
        //      worker threads inherit nice values from their creator; boosting our
        //      thread propagates a higher scheduling priority into the compute work.
        //   3. (Manifest) game_mode_config.xml declares we accept PERFORMANCE mode,
        //      so OEM game-mode frameworks (Adreno GPP, Mali frame rate control,
        //      Tensor Game Mode) also bump GPU clocks + relax DCVS throttling.
        // All cleaned up in the finally so an idle app doesn't keep the boost.
        val callerTid = Process.myTid()
        val originalPriority = runCatching {
            Process.getThreadPriority(callerTid)
        }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        val hintSession = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val phm = context.getSystemService(PerformanceHintManager::class.java)
                phm?.createHintSession(intArrayOf(callerTid), 50_000_000L)  // 50 ms target
            }.getOrNull()
        } else null
        runCatching {
            Process.setThreadPriority(callerTid, Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }

        try { mutex.withLock {
            val instance = loaded
                ?: throw IllegalStateException("Call ensureLoaded(...) before streamTurns()")

            val hasMedia = images.isNotEmpty() || audioClips.isNotEmpty()
            val historySignatures = history.map { it.signature }
            val plan = planTurns(instance.processed, historySignatures, hasMedia)

            val inputText: String = when (plan) {
                is TurnPlan.Warm -> history[plan.sendFromIndex].rawText
                TurnPlan.Cold -> {
                    // ensureLoaded may have kept a warm Conversation that this turn cannot
                    // reuse (a /new, an edit, a regeneration, a tool round-trip, or media).
                    // Recreate it to clear the KV cache, then send the full history.
                    recreateConversationLocked(instance)
                    instance.processed.clear()
                    coldBlob
                }
            }

            val conv = instance.conversation
            // Build Contents in Gallery's order: images and audio first, text last.
            val contentList = mutableListOf<Content>()
            for (image in images) contentList.add(Content.ImageBytes(image.toPngByteArray()))
            for (clip in audioClips) contentList.add(Content.AudioBytes(clip))
            if (inputText.trim().isNotEmpty()) contentList.add(Content.Text(inputText))

            var lastCumulative = ""
            conv.sendMessageAsync(
                Contents.of(contentList),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        message.channels["thought"]?.let { thinking ->
                            if (thinking.isNotEmpty()) onThinking?.invoke(thinking)
                        }
                        val text = message.toString()
                        if (text.isNotEmpty()) {
                            lastCumulative = text
                            trySend(text)
                        }
                    }
                    override fun onDone() {
                        // The Conversation's KV cache now holds [history…] + the assistant
                        // turn just generated. Record that so the NEXT call can warm-continue
                        // if it is a clean single-turn append. Mutating [processed] here is
                        // safe: the mutex is held for this whole streamTurns invocation.
                        instance.processed.clear()
                        instance.processed.addAll(historySignatures)
                        instance.processed.add(turnSignature(ROLE_ASSISTANT, lastCumulative))
                        close()
                    }
                    override fun onError(throwable: Throwable) {
                        // The KV-cache state is now unknown — force the next turn cold.
                        instance.processed.clear()
                        if (throwable is CancellationException) close() else close(throwable)
                    }
                },
                emptyMap(),
            )
            awaitClose { /* SDK callback already closed the channel above. */ }
        } } finally {
            runCatching { hintSession?.close() }
            runCatching { Process.setThreadPriority(callerTid, originalPriority) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cancel the currently-running generation, if any. Safe to call when nothing is
     * generating. Does NOT tear down the Engine or Conversation; the runtime stays warm.
     */
    fun stop() {
        try { loaded?.conversation?.cancelProcess() } catch (_: Throwable) {}
    }

    /**
     * Tear down the currently loaded engine + conversation, if any.
     */
    suspend fun closeIfLoaded() {
        mutex.withLock {
            try { loaded?.conversation?.close() } catch (_: Throwable) {}
            try { loaded?.engine?.close() } catch (_: Throwable) {}
            loaded = null
        }
    }

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    companion object {
        /**
         * Pure decision function: given the signatures the live Conversation has already
         * consumed ([processed]) and the caller's full [historySignatures], decide whether
         * the warm KV cache can be reused. Extracted for unit testing.
         *
         * Warm reuse requires ALL of:
         *  - no media inputs (the warm path sends one turn's text and has nowhere to attach
         *    per-call images/audio onto a prior turn);
         *  - the runtime has prior warm state ([processed] non-empty);
         *  - [processed] is a strict prefix of [historySignatures];
         *  - exactly one turn was appended (a clean continuation — more than one, or a
         *    rewritten earlier turn, goes cold so the full history is re-sent correctly).
         *
         * Any "no" answer is [TurnPlan.Cold], which is always correct — the warm path is
         * purely an optimisation.
         */
        internal fun planTurns(
            processed: List<String>,
            historySignatures: List<String>,
            hasMedia: Boolean,
        ): TurnPlan {
            if (hasMedia) return TurnPlan.Cold
            if (processed.isEmpty()) return TurnPlan.Cold
            if (processed.size >= historySignatures.size) return TurnPlan.Cold
            if (historySignatures.subList(0, processed.size) != processed) return TurnPlan.Cold
            if (historySignatures.size - processed.size != 1) return TurnPlan.Cold
            return TurnPlan.Warm(sendFromIndex = processed.size)
        }
    }
}
