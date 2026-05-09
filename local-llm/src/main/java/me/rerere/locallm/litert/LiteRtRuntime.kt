package me.rerere.locallm.litert

import android.content.Context
import android.graphics.Bitmap
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
 *      backend. Without it, the first generation worked by luck (or didn't), and KV cache
 *      sizing was unset → output truncated at the runtime's hardcoded default.
 *   2. **Did not pass `EngineConfig.maxNumTokens`.** The SDK falls back to an internal default
 *      (~16) when `maxNumTokens` is null, so users saw "I" then nothing. Setting it to 4096
 *      (Gallery's `DEFAULT_MAX_TOKEN`) restores normal-length responses.
 *   3. **Inlined the system prompt as `User: …\nAssistant: …` plain text.** The LiteRT-LM
 *      runtime ships per-model chat templates; without `systemInstruction` going through the
 *      template engine, Qwen2.5 (and any chat-tuned model) emits gibberish — the template's
 *      role tokens are missing.
 *
 * This rewrite mirrors `gallery/.../LlmChatModelHelper.kt` line-by-line for the SDK-call
 * sequence. Other behavior (corruption detection, in-session GPU→CPU fallback, accelerator
 * probe caching) is preserved from the v22A original so [LiteRtProvider] keeps compiling.
 *
 * # Lifecycle
 *
 * The Engine + Conversation pair are loaded once per
 * (modelPath, accelerator, maxNumTokens, supportImage, supportAudio, speculativeDecoding)
 * tuple and reused across multiple [streamGenerate] calls. Per-conversation knobs
 * (system instruction, tools, sampler params, constrained decoding) recreate the
 * Conversation but keep the Engine — the Engine cold-load is the expensive 10-60s step.
 *
 * # Concurrency
 *
 * A single [mutex] serialises all access. Two concurrent collectors of the same
 * runtime queue up; only one inference is in flight at a time.
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
     * Snapshot of every config bit that requires Engine teardown vs. only Conversation
     * recreation. The Engine is the expensive resource (cold-load 10-60s); the Conversation
     * is cheap to recreate. This split lets a caller change `systemInstruction` or `tools`
     * mid-session without paying the Engine reload cost.
     */
    private data class EngineKey(
        val modelPath: String,
        val accelerator: String,
        val maxNumTokens: Int,
        val supportImage: Boolean,
        val supportAudio: Boolean,
        val speculativeDecoding: Boolean,
    )

    private data class LoadedModel(
        val key: EngineKey,
        val engine: Engine,
        var conversation: Conversation,
    )

    /**
     * Inspect a raw engine throwable and re-wrap it as [LiteRtModelCorruptException] if the
     * error message indicates a structural file problem that won't be fixed by switching
     * accelerators or retrying. Returns the original throwable unchanged for transient /
     * hardware errors so the GPU→CPU fallback still works.
     *
     * Non-recoverable patterns (file is permanently broken):
     *   - "Failed to decompress" / "Uncompressed size … exceeds" — corrupt tokenizer chunk
     *   - "Invalid magic number" — not a valid .litertlm container
     *   - "No KV cache inputs found" — model built for a different runtime version
     *   - "FAILED_PRECONDITION" — schema mismatch (usually paired with KV cache message)
     *   - "INVALID_ARGUMENT" with "tokenizer" / "Section not found" / "Uncompressed size"
     *     — tokenizer or section header is structurally invalid
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
     * Gallery's `LlmChatModelHelper.kt` lines 91-94: both labels share the QNN delegate
     * loader path; "TPU" is just a label the Gallery UI uses for Pixel Tensor and is
     * still served by the NPU backend under the hood.
     */
    private fun acceleratorToBackend(accel: String): Backend = when (accel) {
        "QNN", "NPU", "TPU" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        "GPU" -> Backend.GPU()
        else -> Backend.CPU()
    }

    /**
     * Configure the engine + conversation for the next [streamGenerate] call.
     *
     * Reuses the existing Engine if every Engine-affecting parameter matches
     * (modelPath, accelerator, maxNumTokens, supportImage, supportAudio, speculativeDecoding).
     * If only per-conversation knobs change (systemInstruction, tools, sampler, constrained
     * decoding), the Engine is kept and only the Conversation is recreated — saving the
     * 10-60s Engine cold-load cost.
     *
     * When the preferred/probed accelerator fails (e.g. GPU OpenCL/OpenGL stack absent),
     * automatically retries with CPU. The fallback is remembered in-session so subsequent
     * loads skip the failed GPU attempt. The persistent accelerator preference in
     * `LocalRuntimePreferences` is NOT modified — the user can trigger Re-detect to update
     * it explicitly.
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
        systemInstruction: Contents? = null,
        tools: List<ToolProvider> = emptyList(),
        constrainedDecoding: Boolean = false,
        topK: Int = 64,
        topP: Double = 0.95,
        temperature: Double = 1.0,
    ): String = mutex.withLock {
        // Use in-session fallback if a prior GPU→CPU retry already succeeded this session.
        // forceCpu wins over a non-null preferredAccel — when the user has the toggle off
        // (or recovery flipped it), CPU is mandatory for this session.
        val accel = if (forceCpu) "CPU"
        else sessionFallbackAccelerator
            ?: preferredAccel
            ?: AcceleratorProbe.probeLiteRt(context)

        // Probe the file for speculative-decoding support BEFORE building the engine.
        // Gallery wraps this in try/catch (line 128-134) because Capabilities() can throw
        // on malformed files — but we want to keep going either way (a corrupt file will
        // fail again at engine.initialize() and get classified there).
        val supportsSpeculativeDecoding = try {
            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
        } catch (_: Throwable) {
            false
        }
        val effectiveSpeculativeDecoding = speculativeDecoding && supportsSpeculativeDecoding

        val desiredKey = EngineKey(
            modelPath = modelPath,
            accelerator = accel,
            maxNumTokens = maxNumTokens,
            supportImage = supportImage,
            supportAudio = supportAudio,
            speculativeDecoding = effectiveSpeculativeDecoding,
        )

        val current = loaded
        // Engine reuse path: only recreate the Conversation. Saves the 10-60s cold-load.
        if (current != null && current.key == desiredKey) {
            try { current.conversation.close() } catch (_: Throwable) {}
            val newConv = createConversationWithFlags(
                engine = current.engine,
                backend = acceleratorToBackend(accel),
                systemInstruction = systemInstruction,
                tools = tools,
                constrainedDecoding = constrainedDecoding,
                topK = topK,
                topP = topP,
                temperature = temperature,
            )
            current.conversation = newConv
            return@withLock accel
        }

        // Engine swap path: tear down any prior Engine + Conversation.
        try { current?.conversation?.close() } catch (_: Throwable) {}
        try { current?.engine?.close() } catch (_: Throwable) {}

        // Try the preferred accelerator first; fall back to CPU if it isn't already CPU.
        val firstAttempt = runCatching {
            tryLoadWithBackend(
                modelPath = modelPath,
                accel = accel,
                maxNumTokens = maxNumTokens,
                supportImage = supportImage,
                supportAudio = supportAudio,
                speculativeDecoding = effectiveSpeculativeDecoding,
                systemInstruction = systemInstruction,
                tools = tools,
                constrainedDecoding = constrainedDecoding,
                topK = topK,
                topP = topP,
                temperature = temperature,
            )
        }
        val (engine, conv, finalAccel) = firstAttempt.getOrElse { firstError ->
            if (accel == "CPU") {
                // CPU was already tried. Don't retry; surface the error, classifying as
                // corrupt if the message matches a non-recoverable structural pattern.
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
            // Non-CPU failed. Classify before deciding whether to retry.
            // If the first error is already a structural/corrupt problem, retrying on CPU
            // won't help — surface the classified exception immediately.
            val classified = classifyEngineError(modelPath, firstError)
            if (classified is LiteRtModelCorruptException) throw classified

            android.util.Log.w(
                "LiteRtRuntime",
                "Engine init failed on $accel (${firstError.message}); retrying on CPU",
            )
            try {
                tryLoadWithBackend(
                    modelPath = modelPath,
                    accel = "CPU",
                    maxNumTokens = maxNumTokens,
                    supportImage = supportImage,
                    supportAudio = supportAudio,
                    speculativeDecoding = effectiveSpeculativeDecoding,
                    systemInstruction = systemInstruction,
                    tools = tools,
                    constrainedDecoding = constrainedDecoding,
                    topK = topK,
                    topP = topP,
                    temperature = temperature,
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
        if (finalAccel != accel) {
            sessionFallbackAccelerator = finalAccel
        }

        loaded = LoadedModel(
            key = desiredKey.copy(accelerator = finalAccel),
            engine = engine,
            conversation = conv,
        )
        finalAccel
    }

    /**
     * Build + initialize an Engine, then open a Conversation. Throws if either step fails.
     *
     * Mirrors Gallery's `LlmChatModelHelper.initialize()` lines 110-176:
     *   - EngineConfig with explicit `maxNumTokens` (THE fix for the I-then-nothing bug)
     *   - `cacheDir` only when modelPath sits in `/data/local/tmp` (matches Gallery 119-122)
     *     — for normal app-storage paths, the runtime picks its own cache location.
     *   - The flag dance: set `enableSpeculativeDecoding` true → construct Engine →
     *     `engine.initialize()` → reset flag false. The flag is read at construction +
     *     init time; resetting after init prevents leaking the choice into the next Engine
     *     created from another component (e.g. background workflow).
     *   - `enableConversationConstrainedDecoding` flag is read at `createConversation`
     *     time; same set/reset dance around it.
     */
    @OptIn(ExperimentalApi::class)
    private suspend fun tryLoadWithBackend(
        modelPath: String,
        accel: String,
        maxNumTokens: Int,
        supportImage: Boolean,
        supportAudio: Boolean,
        speculativeDecoding: Boolean,
        systemInstruction: Contents?,
        tools: List<ToolProvider>,
        constrainedDecoding: Boolean,
        topK: Int,
        topP: Double,
        temperature: Double,
    ): Triple<Engine, Conversation, String> {
        val backend = acceleratorToBackend(accel)
        // Vision backend MUST be GPU for Gemma 3n (Gallery comment line 116). When the
        // caller doesn't request image support, leave the backend null entirely so the
        // engine doesn't allocate vision-encoder memory.
        val visionBackend: Backend? = if (supportImage) Backend.GPU() else null
        // Audio backend MUST be CPU for Gemma 3n (Gallery comment line 117).
        val audioBackend: Backend? = if (supportAudio) Backend.CPU() else null

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = visionBackend,
            audioBackend = audioBackend,
            // The fix for the "single character then nothing" bug: the SDK's null-default
            // for maxNumTokens is ~16, which truncates the very first response. 4096
            // matches Gallery's `DEFAULT_MAX_TOKEN`. Larger values (32k for Gemma 4 E2B/E4B)
            // would need per-model overrides — out of scope for this rewrite.
            maxNumTokens = maxNumTokens,
            // Gallery only sets cacheDir when the model lives in /data/local/tmp (the dev
            // push location). For models the user installed normally, the runtime picks
            // its own cache location automatically. Mirroring this avoids surprising the
            // SDK with a cacheDir for a path it didn't expect.
            cacheDir = if (modelPath.startsWith("/data/local/tmp"))
                context.getExternalFilesDir(null)?.absolutePath
            else null,
        )

        val engine = withContext(Dispatchers.IO) {
            // The flag dance: set BEFORE constructing the Engine so the constructor sees
            // it, and reset AFTER initialize() so a later Engine constructed elsewhere
            // doesn't accidentally inherit speculative decoding. We set/reset around the
            // *whole* construct+initialize sequence because the SDK reads the flag at
            // multiple points across both calls.
            ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
            val e = try {
                Engine(engineConfig).also { built ->
                    try {
                        // Common failures:
                        //   FAILED_PRECONDITION: No KV cache inputs found — model was built
                        //     for a different LiteRT-LM version.
                        //   INVALID_ARGUMENT: tokenizer … exceeds — model's vocab size is
                        //     larger than the runtime's built-in limit.
                        //   INTERNAL: GPU backend error — OpenCL/OpenGL stack not supported;
                        //     caller retries with CPU automatically.
                        built.initialize()
                    } catch (t: Throwable) {
                        // Close the partially-constructed engine to release its native
                        // handle before propagating. Otherwise the GPU engine leaks when
                        // initialize() fails and we fall back to CPU.
                        try { built.close() } catch (_: Throwable) {}
                        throw t
                    }
                }
            } finally {
                // Reset even on failure so a later attempt with speculativeDecoding=false
                // doesn't get an unwanted carry-over.
                ExperimentalFlags.enableSpeculativeDecoding = false
            }
            e
        }

        val conv = createConversationWithFlags(
            engine = engine,
            backend = backend,
            systemInstruction = systemInstruction,
            tools = tools,
            constrainedDecoding = constrainedDecoding,
            topK = topK,
            topP = topP,
            temperature = temperature,
        )
        return Triple(engine, conv, accel)
    }

    /**
     * Build a Conversation with the constrained-decoding flag dance.
     *
     * Sampler choice mirrors Gallery line 162-171:
     *   - NPU backend → samplerConfig MUST be null. The QNN runtime ignores the field
     *     and configuring it produces an error in some delegate versions.
     *   - GPU/CPU backend → pass an explicit SamplerConfig with the caller's topK/topP/
     *     temperature so the Conversation's sampling matches the user's settings.
     *
     * `enableConversationConstrainedDecoding` is read inside createConversation, so we
     * set it true right before and reset to false right after, matching Gallery's
     * line 157-176 dance.
     */
    @OptIn(ExperimentalApi::class)
    private fun createConversationWithFlags(
        engine: Engine,
        backend: Backend,
        systemInstruction: Contents?,
        tools: List<ToolProvider>,
        constrainedDecoding: Boolean,
        topK: Int,
        topP: Double,
        temperature: Double,
    ): Conversation {
        ExperimentalFlags.enableConversationConstrainedDecoding = constrainedDecoding
        return try {
            engine.createConversation(
                ConversationConfig(
                    samplerConfig = if (backend is Backend.NPU) {
                        null
                    } else {
                        SamplerConfig(
                            topK = topK,
                            topP = topP,
                            temperature = temperature,
                        )
                    },
                    systemInstruction = systemInstruction,
                    tools = tools,
                )
            )
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    /**
     * Stream a single user turn. Each emitted String is the **cumulative** response so far,
     * NOT a delta — Gallery's `partialResult` is consumed via
     * `updateLastTextMessageContentIncrementally(partialContent = partialResult)`, which
     * REPLACES the content rather than appending. Downstream consumers in this fork should
     * either treat each emission as the full latest text, or compute deltas themselves.
     *
     * Multi-modal inputs (images, audio) are passed in per-call and packaged into [Contents]
     * the same way Gallery's `runInference` does — image/audio Contents are appended FIRST
     * and the text Content last, so the runtime sees the text as the final token in the
     * input sequence (Gallery comment line 308).
     *
     * The optional [onThinking] callback receives the model's reasoning-channel content
     * (Message.channels["thought"]) when the model emits one. Reasoning models like
     * Qwen3-Thinking surface their chain-of-thought through this side channel rather than
     * inlined in the response text.
     *
     * Caller MUST have called [ensureLoaded] first.
     *
     * The [mutex] is held for the duration of each inference so two concurrent callers
     * queue up rather than both calling [Conversation.sendMessageAsync] on the same
     * (non-thread-safe) Conversation simultaneously.
     */
    fun streamGenerate(
        userInput: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onThinking: ((String) -> Unit)? = null,
    ): Flow<String> = callbackFlow {
        mutex.withLock {
            val instance = loaded
                ?: throw IllegalStateException(
                    "Call ensureLoaded(modelPath) before streamGenerate()"
                )
            val conv = instance.conversation

            // Build Contents in the same order Gallery does (line 300-310): images and
            // audio first, then text last. The runtime treats the LAST Content as the
            // most recent token block, which matters for chat templates that emit a
            // role-end marker after the final user text.
            val contentList = mutableListOf<Content>()
            for (image in images) {
                contentList.add(Content.ImageBytes(image.toPngByteArray()))
            }
            for (clip in audioClips) {
                contentList.add(Content.AudioBytes(clip))
            }
            if (userInput.trim().isNotEmpty()) {
                contentList.add(Content.Text(userInput))
            }

            conv.sendMessageAsync(
                Contents.of(contentList),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        // Surface reasoning channel separately if present. Gallery threads
                        // this back through the resultListener's `partialThinkingResult`
                        // parameter; we expose it as an optional callback so the fork's
                        // existing emission shape (Flow<String>) stays simple.
                        message.channels["thought"]?.let { thinking ->
                            if (thinking.isNotEmpty()) onThinking?.invoke(thinking)
                        }
                        // Message.toString() returns the cumulative-so-far response text.
                        val text = message.toString()
                        if (text.isNotEmpty()) trySend(text)
                    }
                    override fun onDone() {
                        close()
                    }
                    override fun onError(throwable: Throwable) {
                        if (throwable is CancellationException) close() else close(throwable)
                    }
                },
                emptyMap(),
            )
            awaitClose { /* SDK callback already closed the channel above; nothing to cancel here. */ }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cancel the currently-running generation, if any. Safe to call when nothing is
     * generating — `cancelProcess` is a no-op in that case. Does NOT tear down the
     * Engine or Conversation; the runtime stays warm for the next [streamGenerate].
     *
     * Mirrors Gallery's `stopResponse` (line 271-274).
     */
    fun stop() {
        try { loaded?.conversation?.cancelProcess() } catch (_: Throwable) {}
    }

    /**
     * Tear down the currently loaded engine + conversation, if any.
     * Acquires [mutex] so it cannot race with a concurrent [ensureLoaded] or
     * [streamGenerate] call — otherwise a concurrent caller could see [loaded]
     * go null while the engine handle is mid-close.
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
}
