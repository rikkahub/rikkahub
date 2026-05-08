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

    private data class LoadedModel(
        val modelPath: String,
        val accelerator: String,
        val engine: Engine,
        val conversation: Conversation,
    )

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
     */
    suspend fun ensureLoaded(modelPath: String, preferredAccel: String? = null): String =
        mutex.withLock {
            val accel = preferredAccel ?: AcceleratorProbe.probeLiteRt(context)
            val current = loaded
            if (current != null && current.modelPath == modelPath && current.accelerator == accel) {
                return@withLock accel
            }
            // Tear down any prior session.
            try { current?.conversation?.close() } catch (_: Throwable) {}
            try { current?.engine?.close() } catch (_: Throwable) {}

            val backend = acceleratorToBackend(accel)
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = 1024,
                cacheDir = context.getExternalFilesDir(null)?.absolutePath,
            )
            val engine = withContext(Dispatchers.IO) {
                Engine(engineConfig).also { it.initialize() }
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
            loaded = LoadedModel(modelPath, accel, engine, conv)
            accel
        }

    /**
     * Stream a generation. Each partial token / chunk arrives as a String element.
     * Terminal completion closes the flow normally; errors close it with the cause.
     *
     * Caller MUST have called [ensureLoaded] first.
     */
    fun streamGenerate(prompt: String): Flow<String> = callbackFlow {
        val instance = loaded
            ?: throw IllegalStateException("Call ensureLoaded(modelPath) before streamGenerate()")
        val conv = instance.conversation
        conv.sendMessageAsync(
            Contents.of(listOf(Content.Text(prompt))),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Each chunk is forwarded as text. The toString includes the textual content
                    // accumulated by the runtime; downstream consumers can treat it as a token
                    // delta or accumulate themselves.
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
        awaitClose { /* No per-call cancel API; mutex serialises. */ }
    }.flowOn(Dispatchers.IO)

    fun closeIfLoaded() {
        try { loaded?.conversation?.close() } catch (_: Throwable) {}
        try { loaded?.engine?.close() } catch (_: Throwable) {}
        loaded = null
    }
}
