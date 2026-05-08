package me.rerere.locallm.litert

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.locallm.AcceleratorProbe
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
 * # Lifecycle
 * The Engine + Conversation pair are loaded once per (model path, accelerator) tuple
 * and reused across multiple [streamGenerate] calls. Switching model OR accelerator
 * tears down the old pair and rebuilds. This avoids the per-turn-rebuild cost the
 * older MediaPipe tasks-genai integration had.
 *
 * # Concurrency
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

    private data class LoadedModel(
        val modelPath: String,
        val accelerator: String,
        val engine: Engine,
        val conversation: Conversation,
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

    private fun acceleratorToBackend(accel: String): Backend = when (accel) {
        "QNN", "NPU" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        "GPU" -> Backend.GPU()
        else -> Backend.CPU()
    }

    /**
     * Ensure a model + conversation are ready. Re-creates if (modelPath, accelerator)
     * differs from what's currently loaded. Returns the resolved accelerator label.
     *
     * Callers should hold off on streamGenerate until this suspending function returns.
     * The `Engine.initialize()` call inside can take 10-60 s on first cold-load.
     *
     * When the preferred/probed accelerator fails (e.g. GPU OpenGL backend error on a device
     * that lacks a working OpenCL/OpenGL stack), this function automatically retries with CPU.
     * The fallback is remembered in-session so subsequent loads skip the failed GPU attempt.
     * The persistent accelerator preference in LocalRuntimePreferences is NOT modified — the
     * user can trigger Re-detect to update it explicitly.
     */
    suspend fun ensureLoaded(modelPath: String, preferredAccel: String? = null): String =
        mutex.withLock {
            // Use in-session fallback if a prior GPU→CPU retry already succeeded this session.
            val accel = sessionFallbackAccelerator
                ?: preferredAccel
                ?: AcceleratorProbe.probeLiteRt(context)
            val current = loaded
            if (current != null && current.modelPath == modelPath && current.accelerator == accel) {
                return@withLock accel
            }
            // Tear down any prior session.
            try { current?.conversation?.close() } catch (_: Throwable) {}
            try { current?.engine?.close() } catch (_: Throwable) {}

            // Try the preferred accelerator first; fall back to CPU if it is not already CPU.
            val firstAttempt = runCatching { tryLoadWithBackend(modelPath, accel) }
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

                // Transient / hardware error. Log + retry with CPU.
                android.util.Log.w(
                    "LiteRtRuntime",
                    "Engine init failed on $accel (${firstError.message}); retrying on CPU",
                )
                try {
                    tryLoadWithBackend(modelPath, "CPU")
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

            loaded = LoadedModel(modelPath, finalAccel, engine, conv)
            finalAccel
        }

    /**
     * Attempt to create + initialize an Engine and open a Conversation using the given
     * accelerator label. Throws if the engine fails to initialize.
     */
    private suspend fun tryLoadWithBackend(
        modelPath: String,
        accel: String,
    ): Triple<Engine, Conversation, String> {
        val backend = acceleratorToBackend(accel)
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = null,
            audioBackend = null,
            // Match the model's effective KV cache. Qwen2.5-1.5B's filename
            // encodes ekv4096; Gemma3-1B is also 4096; larger Gallery models
            // (Gemma 4 E2B/E4B) advertise 32000 but those don't fit the
            // typical Phase 22A device anyway. 4096 is the sane v1 default.
            // Phase 22C will surface a per-model override.
            maxNumTokens = 4096,
            // getExternalFilesDir can return null when no external storage is available
            // (removable SD card unmounted, etc.). Fall back to the always-available
            // internal files dir so the engine can still persist its compiled-model cache
            // and avoid a 10-60 s recompile on every load.
            cacheDir = (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath,
        )
        val engine = withContext(Dispatchers.IO) {
            val e = Engine(engineConfig)
            try {
                // Common failures:
                //   FAILED_PRECONDITION: No KV cache inputs found — model was built for a
                //     different LiteRT-LM version.
                //   INVALID_ARGUMENT: tokenizer … exceeds — model's vocab size is larger
                //     than the runtime's built-in limit.
                //   INTERNAL: GPU backend error — OpenCL/OpenGL stack not supported; caller
                //     retries with CPU automatically.
                e.initialize()
            } catch (t: Throwable) {
                // Close the partially-constructed engine to release its native handle before
                // propagating. Without this the GPU engine leaks when initialize() fails and
                // we fall back to CPU.
                try { e.close() } catch (_: Throwable) {}
                throw t
            }
            e
        }
        val conv = engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = 64,
                    topP = 0.95,
                    temperature = 1.0,
                ),
                systemInstruction = null,
                tools = listOf(),
            )
        )
        return Triple(engine, conv, accel)
    }

    /**
     * Stream a generation. Each partial token / chunk arrives as a String element.
     * Terminal completion closes the flow normally; errors close it with the cause.
     *
     * Caller MUST have called [ensureLoaded] first.
     *
     * The [mutex] is held for the duration of each inference so that two concurrent
     * callers queue up rather than both calling [Conversation.sendMessageAsync] on the
     * same (non-thread-safe) Conversation object simultaneously. The lock is acquired
     * as a suspending operation inside the flow so callers block cooperatively rather
     * than spinning — and the lock is released in [awaitClose] once the SDK signals
     * completion (onDone / onError).
     */
    fun streamGenerate(prompt: String): Flow<String> = callbackFlow {
        mutex.withLock {
            val instance = loaded
                ?: throw IllegalStateException("Call ensureLoaded(modelPath) before streamGenerate()")
            val conv = instance.conversation
            conv.sendMessageAsync(
                Contents.of(listOf(Content.Text(prompt))),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        // Each chunk is forwarded as text. The toString includes the textual
                        // content accumulated by the runtime; downstream consumers can treat it
                        // as a token delta or accumulate themselves.
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
            awaitClose { /* SDK callback already closed the channel above; nothing to cancel. */ }
        }
    }.flowOn(Dispatchers.IO)

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
}
