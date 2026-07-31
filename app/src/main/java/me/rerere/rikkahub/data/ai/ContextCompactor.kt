package me.rerere.rikkahub.data.ai

import android.util.Log
import me.rerere.rikkahub.data.ai.prompts.COMPACTION_TRANSITION_PROMPT
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.applyPlaceholders
import kotlin.math.roundToInt

private const val TAG = "ContextCompactor"
private const val CACHE_TAG = "CacheTracker"

/**
 * 自动上下文压缩器。
 *
 * 参考 Reasonix 的多层级上下文维护策略，结合角色扮演友好的过渡语设计：
 * - 软阈值（softCompactTokenRatio）：超过后标记需压缩，空闲时触发
 * - 强制阈值（forceCompactTokenRatio）：超过后立即压缩，先插入预生成的过渡语
 * - 工具结果裁剪：压缩前先廉价裁剪旧工具输出
 * - 卡住检测：连续压缩后仍超阈值则暂停
 */
class ContextCompactor(
    private val contextWindow: Int,  // 模型的 context window 大小（token 数）
    private val assistant: Assistant,
) {
    // 压缩过渡语，对话首轮预生成一次
    var transitionMessage: String? = null

    // 阈值（token 数）
    private val softThreshold: Int = (contextWindow * assistant.softCompactTokenRatio).roundToInt()
    private val forceThreshold: Int = (contextWindow * assistant.forceCompactTokenRatio).roundToInt()

    /** 是否已启用自动压缩 */
    val enabled: Boolean get() = assistant.autoCompactEnabled && contextWindow > 0

    /**
     * 根据当前 prompt token 使用量判断压缩级别。
     */
    fun assess(promptTokens: Int): CompactionLevel {
        if (!enabled) return CompactionLevel.NONE
        return when {
            promptTokens >= forceThreshold -> CompactionLevel.FORCE
            promptTokens >= softThreshold -> CompactionLevel.SOFT
            else -> CompactionLevel.NONE
        }
    }

    /**
     * 预生成压缩过渡语。应在对话首轮（step=0）调用一次。
     * 使用压缩模型（或回退到对话模型）根据用户 system prompt 生成角色扮演友好的过渡语。
     *
     * @param userPrompt 用户的 system prompt / 首条消息内容
     * @param generate 生成函数：接收 prompt 文本，返回生成的过渡语
     */
    suspend fun generateTransitionMessage(
        userPrompt: String,
        generate: suspend (String) -> String?
    ) {
        if (transitionMessage != null) return  // 已生成，跳过
        if (userPrompt.isBlank()) return

        val prompt = COMPACTION_TRANSITION_PROMPT.applyPlaceholders(
            "user_prompt" to userPrompt.take(2000) // 截断过长的 prompt
        )
        try {
            val result = generate(prompt)
            if (!result.isNullOrBlank()) {
                transitionMessage = result.trim()
                Log.i(TAG, "[$CACHE_TAG] transition message generated: ${transitionMessage!!.take(80)}...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$CACHE_TAG] failed to generate transition message: ${e.message}")
            // 失败不阻塞对话流程
        }
    }
}

/**
 * 压缩级别
 */
enum class CompactionLevel {
    /** 无需压缩 */
    NONE,
    /** 软压缩：超过软阈值，空闲时压缩 */
    SOFT,
    /** 强制压缩：超过强制阈值，立即压缩 */
    FORCE,
}
