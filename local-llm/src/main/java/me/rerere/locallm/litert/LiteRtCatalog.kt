package me.rerere.locallm.litert

/** A single curated entry the Settings → Local · LiteRT picker shows. Joins the
 *  download identity (HuggingFace repo + file) with the config defaults so picking
 *  an entry is one-shot: download + config in one user action. */
data class LiteRtCatalogEntry(
    val displayName: String,         // e.g. "Gemma 4 E2B-it"
    val modelId: String,             // HuggingFace repo path, e.g. "litert-community/gemma-4-E2B-it-litert-lm"
    val modelFile: String,           // File inside the repo, e.g. "gemma-4-E2B-it.litertlm"
    val description: String,         // Markdown-friendly one-liner from Gallery
    val sizeBytes: Long,
    val minDeviceMemoryGb: Int,
    val recommended: Boolean = false, // Marks the "default first pick" — Gemma3-1B-IT for low RAM, Gemma-4-E2B for capable
    val tags: List<String> = emptyList(), // ["multimodal", "thinking", "speculative-decoding"] — for chips in UI
) {
    /** Pre-built download URL on HuggingFace's `resolve` path. Same format ModelInstall already validates. */
    fun resolveUrl(): String = "https://huggingface.co/$modelId/resolve/main/$modelFile"
    /** Lookup the matching config defaults. */
    fun config(): LiteRtModelConfig = LiteRtModelDefaults.forModelFile(modelFile)
}

object LiteRtCatalog {
    /** Curated picker list — order matters (top of list shown first). */
    val ENTRIES: List<LiteRtCatalogEntry> = listOf(
        LiteRtCatalogEntry(
            displayName = "Gemma-4-E2B-it",
            modelId = "litert-community/gemma-4-E2B-it-litert-lm",
            modelFile = "gemma-4-E2B-it.litertlm",
            description = "A variant of Gemma 4 E2B ready for deployment on Android using LiteRT-LM. It supports multi-modality input, with up to 32K context length.",
            sizeBytes = 2588147712L,
            minDeviceMemoryGb = 8,
            recommended = true,
            tags = listOf("multimodal", "thinking", "speculative-decoding"),
        ),
        LiteRtCatalogEntry(
            displayName = "Gemma-4-E4B-it",
            modelId = "litert-community/gemma-4-E4B-it-litert-lm",
            modelFile = "gemma-4-E4B-it.litertlm",
            description = "A variant of Gemma 4 E4B ready for deployment on Android using LiteRT-LM. It supports multi-modality input, with up to 32K context length.",
            sizeBytes = 3659530240L,
            minDeviceMemoryGb = 12,
            recommended = false,
            tags = listOf("multimodal", "thinking", "speculative-decoding"),
        ),
        LiteRtCatalogEntry(
            displayName = "Gemma-3n-E2B-it",
            modelId = "google/gemma-3n-E2B-it-litert-lm",
            modelFile = "gemma-3n-E2B-it-int4.litertlm",
            description = "A variant of Gemma 3n E2B ready for deployment on Android using LiteRT-LM. It supports text, vision, and audio input, with 4096 context length.",
            sizeBytes = 3655827456L,
            minDeviceMemoryGb = 8,
            recommended = false,
            tags = listOf("multimodal"),
        ),
        LiteRtCatalogEntry(
            displayName = "Gemma-3n-E4B-it",
            modelId = "google/gemma-3n-E4B-it-litert-lm",
            modelFile = "gemma-3n-E4B-it-int4.litertlm",
            description = "A variant of Gemma 3n E4B ready for deployment on Android using LiteRT-LM. It supports text, vision, and audio input, with 4096 context length.",
            sizeBytes = 4919541760L,
            minDeviceMemoryGb = 12,
            recommended = false,
            tags = listOf("multimodal"),
        ),
        LiteRtCatalogEntry(
            displayName = "Gemma3-1B-IT",
            modelId = "litert-community/Gemma3-1B-IT",
            modelFile = "gemma3-1b-it-int4.litertlm",
            description = "A variant of google/Gemma-3-1B-IT with 4-bit quantization ready for deployment on Android using LiteRT-LM.",
            sizeBytes = 584417280L,
            minDeviceMemoryGb = 6,
            recommended = true,
            tags = emptyList(),
        ),
        LiteRtCatalogEntry(
            displayName = "Qwen2.5-1.5B-Instruct",
            modelId = "litert-community/Qwen2.5-1.5B-Instruct",
            modelFile = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            description = "A variant of Qwen/Qwen2.5-1.5B-Instruct ready for deployment on Android using LiteRT-LM.",
            sizeBytes = 1597931520L,
            minDeviceMemoryGb = 6,
            recommended = false,
            tags = emptyList(),
        ),
        LiteRtCatalogEntry(
            displayName = "DeepSeek-R1-Distill-Qwen-1.5B",
            modelId = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            modelFile = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            description = "A variant of deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B ready for deployment on Android using LiteRT-LM.",
            sizeBytes = 1833451520L,
            minDeviceMemoryGb = 6,
            recommended = false,
            tags = listOf("thinking"),
        ),
    )

    /** Find an entry by modelFile (matches what's stored in our provider config). Useful for
     *  rendering "you have <X> installed" in the UI. */
    fun findByModelFile(modelFile: String): LiteRtCatalogEntry? =
        ENTRIES.firstOrNull { it.modelFile == modelFile }
}
