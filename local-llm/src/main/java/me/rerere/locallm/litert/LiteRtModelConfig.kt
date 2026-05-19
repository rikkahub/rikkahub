package me.rerere.locallm.litert

/** Per-model runtime defaults. Mirrors Gallery's per-model `defaultConfig` block in
 *  model_allowlists/1_0_13.json so models load with the sampler/length params they
 *  were curated for. */
data class LiteRtModelConfig(
    val modelFile: String,           // e.g. "gemma-4-E2B-it.litertlm" — match key
    val topK: Int = 64,
    val topP: Double = 0.95,
    val temperature: Double = 1.0,
    val maxTokens: Int = 4096,        // OUTPUT cap → EngineConfig.maxNumTokens
    val maxContextLength: Int? = null,
    val preferredAccelerators: List<String> = listOf("gpu", "cpu"),  // first available wins
    val visionAccelerator: String? = null,                            // null when no image support
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsThinking: Boolean = false,
    val supportsSpeculativeDecoding: Boolean = false,
    val minDeviceMemoryGb: Int = 6,
    val sizeBytes: Long,
)

object LiteRtModelDefaults {
    /** Look up by exact `modelFile` name. Returns sensible fallback if unknown so HF-URL-pasted
     *  models still get reasonable defaults instead of silent SDK defaults. */
    fun forModelFile(modelFile: String): LiteRtModelConfig =
        BUILT_IN.firstOrNull { it.modelFile == modelFile } ?: FALLBACK

    private val FALLBACK = LiteRtModelConfig(
        modelFile = "<unknown>",
        sizeBytes = 0L,
    )

    private val BUILT_IN: List<LiteRtModelConfig> = listOf(
        // Gemma-4-E2B-it — matches Google AI Edge Gallery's allowlist for this model file.
        // Vision works on Adreno 642L (Nothing Phone 1, Snapdragon 8 Gen 1) per the user's
        // live Gallery test on 2026-05-19; both apps target litertlm 0.11.0.
        LiteRtModelConfig(
            modelFile = "gemma-4-E2B-it.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            maxTokens = 4000,
            maxContextLength = 32000,
            preferredAccelerators = listOf("gpu", "cpu"),
            visionAccelerator = "gpu",
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = true,
            supportsSpeculativeDecoding = true,
            minDeviceMemoryGb = 8,
            sizeBytes = 2588147712L,
        ),
        // Gemma-4-E4B-it — Gallery-parity multimodal config.
        LiteRtModelConfig(
            modelFile = "gemma-4-E4B-it.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            maxTokens = 4000,
            maxContextLength = 32000,
            preferredAccelerators = listOf("gpu", "cpu"),
            visionAccelerator = "gpu",
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = true,
            supportsSpeculativeDecoding = true,
            minDeviceMemoryGb = 12,
            sizeBytes = 3659530240L,
        ),
        // Gemma-3n-E2B-it — Gallery-parity multimodal (no thinking).
        LiteRtModelConfig(
            modelFile = "gemma-3n-E2B-it-int4.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            maxTokens = 4096,
            maxContextLength = null,
            preferredAccelerators = listOf("cpu", "gpu"),
            visionAccelerator = "gpu",
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = false,
            supportsSpeculativeDecoding = false,
            minDeviceMemoryGb = 8,
            sizeBytes = 3655827456L,
        ),
        // Gemma-3n-E4B-it — Gallery-parity multimodal (no thinking).
        LiteRtModelConfig(
            modelFile = "gemma-3n-E4B-it-int4.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            maxTokens = 4096,
            maxContextLength = null,
            preferredAccelerators = listOf("cpu", "gpu"),
            visionAccelerator = "gpu",
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = false,
            supportsSpeculativeDecoding = false,
            minDeviceMemoryGb = 12,
            sizeBytes = 4919541760L,
        ),
        // Gemma3-1B-IT
        LiteRtModelConfig(
            modelFile = "gemma3-1b-it-int4.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            maxTokens = 1024,
            maxContextLength = null,
            preferredAccelerators = listOf("gpu", "cpu"),
            visionAccelerator = null,
            supportsImage = false,
            supportsAudio = false,
            supportsThinking = false,
            supportsSpeculativeDecoding = false,
            minDeviceMemoryGb = 6,
            sizeBytes = 584417280L,
        ),
        // Qwen2.5-1.5B-Instruct (new ekv4096 .litertlm format from 1_0_13.json)
        LiteRtModelConfig(
            modelFile = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            topK = 20,
            topP = 0.8,
            temperature = 0.7,
            maxTokens = 4096,
            maxContextLength = null,
            preferredAccelerators = listOf("gpu", "cpu"),
            visionAccelerator = null,
            supportsImage = false,
            supportsAudio = false,
            supportsThinking = false,
            supportsSpeculativeDecoding = false,
            minDeviceMemoryGb = 6,
            sizeBytes = 1597931520L,
        ),
        // DeepSeek-R1-Distill-Qwen-1.5B intentionally removed: it is a reasoning
        // distillation, not instruction/tool tuned, so it does not support the
        // prompt-engineered tool calling the agent loop depends on. See LiteRtCatalog.
    )
}
