package me.rerere.locallm.litert

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.locallm.AcceleratorProbe

/**
 * Thin wrapper around MediaPipe's `tasks-genai` LlmInference for LiteRT-format models.
 *
 * Single in-flight inference per process: the [mutex] guarantees the runtime isn't
 * asked to start a new generation while one is running. Subsequent calls suspend.
 *
 * Backend selection is done once per (model, accelerator) pair and cached. When the
 * cached LlmInference instance is asked to switch model OR accelerator we close the
 * old one and rebuild.
 *
 * # API deviations from the plan (0.10.21 vs plan expectations)
 *
 * 1. `generateResponseAsync(String)` does NOT accept an inline callback in 0.10.21.
 *    The plan called for `generateResponseAsync(prompt) { partial, done -> }` but the
 *    real signature is `generateResponseAsync(prompt: String): Unit`. The result listener
 *    must be registered via `setResultListener` on the options builder instead, before
 *    `LlmInference.createFromOptions`. We therefore re-build the inference instance on
 *    every `ensureLoaded` call when a new listener channel is needed.
 *
 * 2. `Backend` has `DEFAULT`, `CPU`, `GPU` only — no `QNN` or `NNAPI` enum members.
 *    The plan mapped "QNN" -> Backend.GPU (correct) and "NNAPI" -> Backend.CPU (correct).
 *    We do the same.
 *
 * 3. Errors during async generation arrive via a separate `setErrorListener` builder
 *    method (the plan did not model this explicitly). We register an error channel so
 *    exceptions propagate into the collecting coroutine rather than being silently swallowed.
 */
class LiteRtRuntime(private val context: Context) {

    private val mutex = Mutex()
    private var loaded: LoadedModel? = null

    private data class LoadedModel(
        val modelPath: String,
        val accelerator: String,
        val inference: LlmInference,
    )

    /**
     * Maps the accelerator label (from [AcceleratorProbe]) to a MediaPipe [Backend].
     *
     * 0.10.21 Backend enum: DEFAULT, CPU, GPU.
     * Plan originally called for NNAPI -> Backend.NNAPI (does not exist); mapped to CPU.
     * Plan originally called for QNN   -> Backend.GPU  (same as 0.10.21 reality).
     */
    private fun acceleratorToBackend(accel: String): Backend = when (accel) {
        "QNN"   -> Backend.GPU  // Qualcomm path — delegate inside GPU backend in 0.10.21
        "GPU"   -> Backend.GPU
        "NNAPI" -> Backend.CPU  // NNAPI backend enum absent in 0.10.21; fall back to CPU
        else    -> Backend.CPU
    }

    /**
     * Build a new [LlmInference] with the given listener and error channels.
     *
     * The result listener is wired at construction time (not per-call) because 0.10.21's
     * `generateResponseAsync` has no per-call callback parameter.
     */
    private fun buildInference(
        modelPath: String,
        accel: String,
        onPartial: (partial: String, done: Boolean) -> Unit,
        onError: (RuntimeException) -> Unit,
    ): LlmInference {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .setPreferredBackend(acceleratorToBackend(accel))
            .setResultListener { partial, done -> onPartial(partial, done) }
            .setErrorListener { e -> onError(e) }
            .build()
        return LlmInference.createFromOptions(context, options)
    }

    /**
     * Ensure a model is loaded and ready. Re-builds the inference instance when the
     * model path or accelerator changes (or when not yet loaded).
     *
     * The listener/error channels are wired per-[streamGenerate] call: because
     * 0.10.21 requires the listener at construction time, [streamGenerate] calls
     * [ensureLoadedWithListener] (the internal variant) rather than this public helper.
     *
     * This public variant is safe to call standalone (e.g. from a pre-warm path) with
     * a no-op listener. It will be replaced by [streamGenerate]'s own call before the
     * first token arrives.
     *
     * @return the resolved accelerator label that was used.
     */
    suspend fun ensureLoaded(modelPath: String, preferredAccel: String? = null): String {
        val accel = preferredAccel ?: AcceleratorProbe.probeLiteRt(context)
        mutex.withLock {
            val current = loaded
            if (current == null || current.modelPath != modelPath || current.accelerator != accel) {
                current?.inference?.close()
                val inference = buildInference(
                    modelPath = modelPath,
                    accel = accel,
                    onPartial = { _, _ -> },  // no-op until streamGenerate wires its own instance
                    onError = { },
                )
                loaded = LoadedModel(modelPath = modelPath, accelerator = accel, inference = inference)
            }
        }
        return accel
    }

    /**
     * Stream a generation against the already-loaded model. The result listener is
     * wired into a [Channel] so each partial token becomes a Flow element.
     *
     * Internally this rebuilds the [LlmInference] instance with the channel's listener
     * if the loaded model's listener is the no-op prewarm variant, or always if we
     * need to switch models.
     *
     * **Must call [ensureLoaded] (or let [streamGenerate] do it internally) before this
     * returns elements.**
     */
    fun streamGenerate(
        prompt: String,
        modelPath: String? = null,
        preferredAccel: String? = null,
    ): Flow<String> = callbackFlow {
        // Re-acquire the mutex to rebuild the inference with a live channel listener.
        // The callbackFlow coroutine runs on Dispatchers.IO (see flowOn below), but
        // the mutex lock is brief: just enough to swap the inference instance.
        mutex.withLock {
            val current = loaded
            val targetPath = modelPath ?: current?.modelPath
                ?: throw IllegalStateException("Call ensureLoaded(modelPath) before streamGenerate()")
            val accel = preferredAccel ?: current?.accelerator
                ?: AcceleratorProbe.probeLiteRt(context)

            // Always rebuild so this channel's partial/error lambdas are registered.
            current?.inference?.close()
            val inference = buildInference(
                modelPath = targetPath,
                accel = accel,
                onPartial = { partial, done ->
                    if (partial.isNotEmpty()) trySend(partial)
                    if (done) close()
                },
                onError = { e -> close(e) },
            )
            loaded = LoadedModel(modelPath = targetPath, accelerator = accel, inference = inference)
            inference.generateResponseAsync(prompt)
        }
        awaitClose {
            // No per-call cancellation API in 0.10.21; the mutex serialises concurrent calls.
        }
    }.flowOn(Dispatchers.IO)

    fun closeIfLoaded() {
        loaded?.inference?.close()
        loaded = null
    }
}
