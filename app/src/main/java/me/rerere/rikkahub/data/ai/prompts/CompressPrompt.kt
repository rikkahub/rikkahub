package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You are a conversation compression assistant. Compress the following conversation into a concise summary.

    Requirements:
    1. Preserve key facts, decisions, and important context that would be needed to continue the conversation
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Output the summary directly without any explanations or meta-commentary
    5. Format the summary as context information that can be used to continue the conversation
    6. Use {locale} language
    7. Start the output with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or equivalent in the target language)

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()

/**
 * 压缩过渡语生成提示词。
 * 用于在对话首轮根据用户 prompt 预生成一段角色扮演友好的过渡语，
 * 在强制压缩上下文时插入，使对话过渡自然。
 */
internal val COMPACTION_TRANSITION_PROMPT = """
    Based on the following character/situation context from the user's system prompt, 
    generate a short (1-2 sentences) natural-sounding "pause transition" message that the 
    AI assistant would say when it needs to pause and summarize a long conversation.

    The transition message should:
    1. Match the character's tone and persona implied by the context
    2. Be natural and conversational — like the character genuinely needs a moment
    3. Briefly acknowledge the conversation so far
    4. Indicate they'll be right back / need a short break to collect thoughts
    5. Be 1-2 sentences max, in the same language as the conversation

    Examples:
    - "亲爱的，你刚刚说了好多呢，让我先消化一下，稍等我片刻哦~"
    - "Alright, lots of ground covered — give me a moment to gather my thoughts before we continue."
    - "嗯...信息量有点大呢，让我整理一下思路，马上回来~"
    - "Let me take a second to reflect on everything we've discussed before we move forward."

    User's context/prompt:
    {user_prompt}

    Output ONLY the transition message, nothing else:
""".trimIndent()
