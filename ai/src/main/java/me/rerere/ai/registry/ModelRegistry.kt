package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility

fun interface ModelData<T> {
    fun getData(modelId: String): T
}

object ModelRegistry {
    private val GPT4O = ModelMatcher.containsRegex("(?<!chat)gpt-4o")
    private val GPT_4_1 = ModelMatcher.containsRegex("gpt-4\\.1")
    val OPENAI_O_MODELS = ModelMatcher.containsRegex("o\\d")
    private val GPT_OSS = ModelMatcher.containsRegex("gpt-oss")
    val GPT_5 =
        ModelMatcher.containsRegex("gpt-(?!.*\\.)(?:5)") and ModelMatcher.containsRegex("gpt-5-chat", negated = true)

    private val GEMINI_20_FLASH = ModelMatcher.containsRegex("gemini-2.0-flash")
    val GEMINI_2_5_FLASH = ModelMatcher.containsRegex("gemini-2.5-flash") and ModelMatcher.containsRegex("image", negated = true)
    val GEMINI_2_5_PRO = ModelMatcher.containsRegex("gemini-2.5-pro")
    val GEMINI_2_5_IMAGE = ModelMatcher.containsRegex("gemini-2.5-flash-image")
    val GEMINI_3_PRO = ModelMatcher.containsRegex("gemini-3-pro")
    val GEMINI_3_FLASH = ModelMatcher.containsRegex("gemini-3-flash")
    val GEMINI_FLASH_LATEST = ModelMatcher.exact("gemini-flash-latest")
    val GEMINI_PRO_LATEST = ModelMatcher.exact("gemini-pro-latest")
    val GEMINI_LATEST = GEMINI_FLASH_LATEST + GEMINI_PRO_LATEST
    val GEMINI_3_SERIES = GEMINI_3_PRO + GEMINI_3_FLASH
    val GEMINI_SERIES = GEMINI_20_FLASH + GEMINI_2_5_FLASH + GEMINI_2_5_PRO + GEMINI_3_SERIES + GEMINI_LATEST

    private val CLAUDE_SONNET_3_5 = ModelMatcher.containsRegex("claude-3.5-sonnet")
    private val CLAUDE_SONNET_3_7 = ModelMatcher.containsRegex("claude-3.7-sonnet")
    private val CLAUDE_4 = ModelMatcher.containsRegex("claude.*-4")
    private val CLAUDE_4_5 = ModelMatcher.containsRegex("claude.*-4.5")
    val CLAUDE_SERIES = CLAUDE_SONNET_3_5 + CLAUDE_SONNET_3_7 + CLAUDE_4 + CLAUDE_4_5

    private val DEEPSEEK_V3 = ModelMatcher.containsRegex("deepseek-(v3|chat)")
    private val DEEPSEEK_R1 = ModelMatcher.containsRegex("deepseek-(r1|reasoner)")
    private val DEEPSEEK_V3_1 = ModelMatcher.containsRegex("deepseek-(v3\\.1)")
    private val DEEPSEEK_V3_2 = ModelMatcher.containsRegex("deepseek-(v3\\.2)")
    private val QWEN_3 = ModelMatcher.containsRegex("qwen-?3")
    private val DOUBAO_1_6 = ModelMatcher.containsRegex("doubao.+1([-.])6")
    private val GROK_4 = ModelMatcher.containsRegex("grok-4")
    private val KIMI_K2 = ModelMatcher.containsRegex("kimi-k2")
    private val STEP_3 = ModelMatcher.containsRegex("step-3")
    private val INTERN_S1 = ModelMatcher.containsRegex("intern-s1")
    private val GLM_4_5 = ModelMatcher.containsRegex("glm-4.5")
    private val GLM_4_6 = ModelMatcher.containsRegex("glm-4.6")
    private val MINIMAX_M2 = ModelMatcher.containsRegex("minimax-m2")
    val QWEN_MT = ModelMatcher.containsRegex("qwen-mt")

    val VISION_MODELS =
        GPT4O + GPT_4_1 + GPT_5 + OPENAI_O_MODELS + GEMINI_SERIES + CLAUDE_SERIES + DOUBAO_1_6 + GROK_4 + STEP_3 + INTERN_S1
    val TOOL_MODELS =
        GPT4O + GPT_4_1 + GPT_OSS + GPT_5 + OPENAI_O_MODELS + GEMINI_SERIES + CLAUDE_SERIES + QWEN_3 + DOUBAO_1_6 + GROK_4 + KIMI_K2 + STEP_3 + INTERN_S1 + GLM_4_5 + DEEPSEEK_R1 + DEEPSEEK_V3 + DEEPSEEK_V3_1 + DEEPSEEK_V3_2 + GLM_4_6 + MINIMAX_M2
    val REASONING_MODELS =
        GPT_OSS + GPT_5 + OPENAI_O_MODELS + GEMINI_2_5_FLASH + GEMINI_2_5_PRO + GEMINI_3_SERIES + GEMINI_LATEST + CLAUDE_SERIES + QWEN_3 + DOUBAO_1_6 + GROK_4 + KIMI_K2 + STEP_3 + INTERN_S1 + GLM_4_5 + DEEPSEEK_R1 + DEEPSEEK_V3_1 + DEEPSEEK_V3_2 + GLM_4_6 + MINIMAX_M2
    val CHAT_IMAGE_GEN_MODELS = GEMINI_2_5_IMAGE

    val MODEL_INPUT_MODALITIES = ModelData { modelId ->
        if (VISION_MODELS.match(modelId)) {
            listOf(Modality.TEXT, Modality.IMAGE)
        } else {
            listOf(Modality.TEXT)
        }
    }

    val MODEL_OUTPUT_MODALITIES = ModelData { modelId ->
        if(CHAT_IMAGE_GEN_MODELS.match(modelId)) {
            listOf(Modality.TEXT, Modality.IMAGE)
        } else {
            listOf(Modality.TEXT)
        }
    }

    val MODEL_ABILITIES = ModelData { modelId ->
        buildList {
            if (TOOL_MODELS.match(modelId)) {
                add(ModelAbility.TOOL)
            }
            if (REASONING_MODELS.match(modelId)) {
                add(ModelAbility.REASONING)
            }
        }
    }
}
