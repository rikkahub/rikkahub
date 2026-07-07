package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_TITLE_PROMPT = """
    I will give you some dialogue content in the `<content>` block.
    You need to summarize the conversation between user and assistant into a short title.
    1. The title language should be consistent with the user's primary language
    2. Do not use punctuation or other special symbols
    3. Reply directly with the title
    4. Summarize using {locale} language
    5. The title should not exceed 10 characters

    <content>
    {content}
    </content>
""".trimIndent()

internal val EMOJI_TITLE_PROMPT = """
    I will give you some dialogue content in the `<content>` block.
    You need to summarize the conversation between user and assistant into a short title.
    1. The title language should be consistent with the user's primary language
    2. You may start the title with ONE relevant emoji followed by a space, and use no other special characters or punctuation
    3. Reply directly with the title
    4. Summarize using {locale} language
    5. The title (excluding the emoji) should not exceed 10 characters

    <content>
    {content}
    </content>
""".trimIndent()
