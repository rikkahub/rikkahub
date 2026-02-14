package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility

fun interface ModelData<T> {
    fun getData(modelId: String): T
}

object ModelRegistry {
    private val GPT4O = defineModel {
        tokens("gpt", "4", "o")
        visionInput()
        toolAbility()
    }

    private val GPT_4_1 = defineModel {
        tokens("gpt", "4", "1")
        visionInput()
        toolAbility()
    }

    val OPENAI_O_MODELS = defineModel {
        tokens(tokenRegex("^o$"), tokenRegex("^\\d+$"))
        visionInput()
        toolReasoningAbility()
    }

    private val GPT_OSS = defineModel {
        tokens("gpt", "oss")
        toolReasoningAbility()
    }

    val GPT_5 = defineModel {
        tokens("gpt", "5")
        notTokens("gpt", "5", ".")
        notTokens("gpt", "5", "chat")
        visionInput()
        toolReasoningAbility()
    }

    private val GPT_5_1 = defineModel {
        tokens("gpt", "5", "1")
        visionInput()
        toolReasoningAbility()
    }

    private val GPT_5_2 = defineModel {
        tokens("gpt", "5", "2")
        visionInput()
        toolReasoningAbility()
    }

    private val GEMINI_20_FLASH = defineModel {
        tokens("gemini", "2", "0", "flash")
        visionInput()
        toolAbility()
    }

    val GEMINI_2_5_FLASH = defineModel {
        tokens("gemini", "2", "5", "flash")
        notTokens("image")
        visionInput()
        toolReasoningAbility()
    }

    val GEMINI_2_5_PRO = defineModel {
        tokens("gemini", "2", "5", "pro")
        visionInput()
        toolReasoningAbility()
    }

    val GEMINI_2_5_IMAGE = defineModel {
        tokens("gemini", "2", "5", "flash", "image")
        visionInput()
        imageOutput()
    }

    val GEMINI_3_PRO_IMAGE = defineModel {
        tokens("gemini", "3", "pro", "image")
        visionInput()
        imageOutput()
    }

    val GEMINI_NANO_BANANA = defineModel {
        tokens("nano", "banana")
        visionInput()
        imageOutput()
    }

    val GEMINI_3_PRO = defineModel {
        tokens("gemini", "3", "pro")
        visionInput()
        toolReasoningAbility()
    }

    val GEMINI_3_FLASH = defineModel {
        tokens("gemini", "3", "flash")
        visionInput()
        toolReasoningAbility()
    }

    val GEMINI_FLASH_LATEST = defineModel {
        exact("gemini-flash-latest")
        visionInput()
        toolReasoningAbility()
    }

    val GEMINI_PRO_LATEST = defineModel {
        exact("gemini-pro-latest")
        visionInput()
        toolReasoningAbility()
    }

    val GEMINI_LATEST = defineGroup {
        add(GEMINI_FLASH_LATEST, GEMINI_PRO_LATEST)
    }

    val GEMINI_3_SERIES = defineGroup {
        add(GEMINI_3_PRO, GEMINI_3_FLASH)
    }

    val GEMINI_SERIES = defineGroup {
        add(GEMINI_20_FLASH, GEMINI_2_5_FLASH, GEMINI_2_5_PRO, GEMINI_3_SERIES, GEMINI_LATEST)
    }

    private val CLAUDE_SONNET_3_5 = defineModel {
        tokens("claude", "3", "5", "sonnet")
        visionInput()
        toolReasoningAbility()
    }

    private val CLAUDE_SONNET_3_7 = defineModel {
        tokens("claude", "3", "7", "sonnet")
        visionInput()
        toolReasoningAbility()
    }

    private val CLAUDE_4 = defineModel {
        tokens("claude", "4")
        visionInput()
        toolReasoningAbility()
    }

    val CLAUDE_4_5 = defineModel {
        tokens("claude", "4", "5")
        visionInput()
        toolReasoningAbility()
    }

    val CLAUDE_SERIES = defineGroup {
        add(CLAUDE_SONNET_3_5, CLAUDE_SONNET_3_7, CLAUDE_4, CLAUDE_4_5)
    }

    private val DEEPSEEK_V3_MODEL = defineModel {
        tokens("deepseek", "v", "3")
        toolAbility()
    }

    private val DEEPSEEK_CHAT = defineModel {
        tokens("deepseek", "chat")
        toolAbility()
    }

    private val DEEPSEEK_V3 = defineGroup {
        add(DEEPSEEK_V3_MODEL, DEEPSEEK_CHAT)
    }

    private val DEEPSEEK_R1_MODEL = defineModel {
        tokens("deepseek", "r", "1")
        toolReasoningAbility()
    }

    private val DEEPSEEK_REASONER = defineModel {
        tokens("deepseek", "reasoner")
        toolReasoningAbility()
    }

    private val DEEPSEEK_R1 = defineGroup {
        add(DEEPSEEK_R1_MODEL, DEEPSEEK_REASONER)
    }

    private val DEEPSEEK_V3_1 = defineModel {
        tokens("deepseek", "v", "3", "1")
        toolReasoningAbility()
    }

    private val DEEPSEEK_V3_2 = defineModel {
        tokens("deepseek", "v", "3", "2")
        toolReasoningAbility()
    }

    private val QWEN_3 = defineModel {
        tokens("qwen", "3")
        toolReasoningAbility()
    }

    private val DOUBAO_1_6 = defineModel {
        tokens("doubao", "1", "6")
        visionInput()
        toolReasoningAbility()
    }

    private val DOUBAO_1_8 = defineModel {
        tokens("doubao", "1", "8")
        visionInput()
        toolReasoningAbility()
    }

    private val GROK_4 = defineModel {
        tokens("grok", "4")
        visionInput()
        toolReasoningAbility()
    }

    private val KIMI_K2 = defineModel {
        tokens("kimi", "k", "2")
        toolReasoningAbility()
    }

    private val KIMI_K2_5 = defineModel {
        tokens("kimi", "k", "2", "5")
        visionInput()
        toolReasoningAbility()
    }

    private val STEP_3 = defineModel {
        tokens("step", "3")
        visionInput()
        toolReasoningAbility()
    }

    private val INTERN_S1 = defineModel {
        tokens("intern", "s", "1")
        visionInput()
        toolReasoningAbility()
    }

    private val GLM_4_5 = defineModel {
        tokens("glm", "4", "5")
        toolReasoningAbility()
    }

    private val GLM_4_6 = defineModel {
        tokens("glm", "4", "6")
        toolReasoningAbility()
    }

    private val GLM_4_7 = defineModel {
        tokens("glm", "4", "7")
        toolReasoningAbility()
    }

    private val GLM_5 = defineModel {
        tokens("glm", "5")
        toolReasoningAbility()
    }

    private val MINIMAX_M2 = defineModel {
        tokens("minimax", "m", "2")
        toolReasoningAbility()
    }

    private val MINIMAX_M2_5 = defineModel {
        tokens("minimax", "m", "2", "5")
        toolReasoningAbility()
    }

    private val XIAOMI_MIMO_V2 = defineModel {
        tokens("mimo", "v", "2")
        toolReasoningAbility()
    }

    val QWEN_MT = defineModel {
        tokens("qwen", "mt")
    }

    private val ALL_MODELS = listOf(
        GPT4O,
        GPT_4_1,
        OPENAI_O_MODELS,
        GPT_OSS,
        GPT_5,
        GPT_5_1,
        GPT_5_2,
        GEMINI_20_FLASH,
        GEMINI_2_5_FLASH,
        GEMINI_2_5_PRO,
        GEMINI_2_5_IMAGE,
        GEMINI_3_PRO_IMAGE,
        GEMINI_NANO_BANANA,
        GEMINI_3_PRO,
        GEMINI_3_FLASH,
        GEMINI_FLASH_LATEST,
        GEMINI_PRO_LATEST,
        CLAUDE_SONNET_3_5,
        CLAUDE_SONNET_3_7,
        CLAUDE_4,
        CLAUDE_4_5,
        DEEPSEEK_V3_MODEL,
        DEEPSEEK_CHAT,
        DEEPSEEK_R1_MODEL,
        DEEPSEEK_REASONER,
        DEEPSEEK_V3_1,
        DEEPSEEK_V3_2,
        QWEN_3,
        DOUBAO_1_6,
        DOUBAO_1_8,
        GROK_4,
        KIMI_K2,
        KIMI_K2_5,
        STEP_3,
        INTERN_S1,
        GLM_4_5,
        GLM_4_6,
        GLM_4_7,
        GLM_5,
        MINIMAX_M2,
        MINIMAX_M2_5,
        XIAOMI_MIMO_V2,
        QWEN_MT
    )

    val MODEL_INPUT_MODALITIES = ModelData { modelId ->
        resolveModalities(modelId) { it.inputModalities }
    }

    val MODEL_OUTPUT_MODALITIES = ModelData { modelId ->
        resolveModalities(modelId) { it.outputModalities }
    }

    val MODEL_ABILITIES = ModelData { modelId ->
        val abilities = resolveModels(modelId)
            .flatMap { it.abilities }
            .toSet()
        buildList {
            if (ModelAbility.TOOL in abilities) add(ModelAbility.TOOL)
            if (ModelAbility.REASONING in abilities) add(ModelAbility.REASONING)
        }
    }

    private fun resolveModels(modelId: String): List<ModelDefinition> {
        var bestScore: Int? = null
        val matches = mutableListOf<ModelDefinition>()
        for (model in ALL_MODELS) {
            val score = model.matchScore(modelId) ?: continue
            when {
                bestScore == null || score > bestScore -> {
                    bestScore = score
                    matches.clear()
                    matches.add(model)
                }

                score == bestScore -> matches.add(model)
            }
        }
        return matches
    }

    private fun resolveModalities(
        modelId: String,
        selector: (ModelDefinition) -> Set<Modality>
    ): List<Modality> {
        val modalities = resolveModels(modelId)
            .flatMap { selector(it) }
            .toSet()
        return if (modalities.isEmpty()) {
            listOf(Modality.TEXT)
        } else {
            listOf(Modality.TEXT, Modality.IMAGE).filter { it in modalities }
        }
    }

    private fun ModelDefinitionBuilder.visionInput() {
        input(Modality.TEXT, Modality.IMAGE)
    }

    private fun ModelDefinitionBuilder.imageOutput() {
        output(Modality.TEXT, Modality.IMAGE)
    }

    private fun ModelDefinitionBuilder.toolAbility() {
        ability(ModelAbility.TOOL)
    }

    private fun ModelDefinitionBuilder.toolReasoningAbility() {
        ability(ModelAbility.TOOL, ModelAbility.REASONING)
    }
}
