package me.rerere.rikkahub.data.ai.transformers

class ChatMessageTransformers(
    private val templateTransformer: TemplateTransformer,
) {
    val input: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        OcrTransformer,
        KnowledgeContextTransformer,
        templateTransformer,
    )

    val output: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

