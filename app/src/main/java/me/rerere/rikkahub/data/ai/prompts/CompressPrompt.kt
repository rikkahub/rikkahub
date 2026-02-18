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

internal val DEFAULT_CODE_COMPRESS_PROMPT = """
    你的任务是创建到目前为止的对话的详细摘要，密切关注用户的明确请求和你之前的操作。
    这个摘要应该详尽地捕捉技术细节、代码模式和架构决策，这些都是在不丢失上下文的情况下继续开发工作所必需的。

    在提供最终摘要之前，请将你的分析包裹在 <分析> 标签中，以组织你的思维并确保你涵盖了所有必要的要点。在你的分析过程中：

    1. 按时间顺序分析每条消息和对话的每个部分。对于每个部分，彻底识别：
      - 用户的明确请求和意图
      - 你处理用户请求的方法
      - 关键决策、技术概念和代码模式
      - 具体细节，例如：
        - 文件名
        - 完整的代码片段
        - 函数签名
        - 文件编辑
        - 你遇到的错误以及你是如何修复它们的
        - 特别注意你收到的具体用户反馈，特别是如果用户告诉你要以不同的方式做某事
    2. 仔细检查技术准确性和完整性，彻底处理每个必需的元素。

    你的摘要应包括以下部分：

    1. 主要请求和意图：详细捕捉用户的所有明确请求和意图
    2. 关键技术概念：列出讨论的所有重要技术概念、技术和框架
    3. 文件和代码段：枚举检查、修改或创建的具体文件和代码段。特别注意最近的消息，并在适用的情况下包含完整的代码片段，并包括为什么读取或编辑此文件的摘要
    4. 错误和修复：列出你遇到的所有错误，以及你是如何修复它们的。特别注意你收到的具体用户反馈，特别是如果用户告诉你要以不同的方式做某事
    5. 问题解决：记录已解决的问题和任何正在进行的故障排除工作
    6. 所有用户消息：列出所有非工具结果的用户消息。这些对于理解用户的反馈和变化的意图至关重要
    7. 待处理任务：概述你被明确要求处理的任何待处理任务
    8. 当前工作：详细描述在此摘要请求之前正在处理的内容，特别注意来自用户和助手的最近消息。在适用的情况下包括文件名和代码片段
    9. 可选的下一步：列出与你正在处理的最近工作相关的下一步。重要提示：确保这一步直接与用户最近的明确请求以及在此摘要请求之前你正在处理的任务一致。

    目标约 {target_tokens} 个 token，使用 {locale} 语言。

    {additional_context}

    <对话>
    {content}
    </对话>
""".trimIndent()
