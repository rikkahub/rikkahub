package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import me.rerere.ai.runtime.transformers.stripThinkTags
import me.rerere.ai.ui.UIMessage
import kotlin.time.Clock

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
// The provider-agnostic extraction lives in :ai-runtime (stripThinkTags); this app adapter only
// binds the OutputMessageTransformer interface and supplies the wall clock.
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = stripThinkTags(
        messages = messages,
        now = Clock.System.now(),
        zone = TimeZone.currentSystemDefault(),
    )

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = stripThinkTags(
        messages = messages,
        now = Clock.System.now(),
        zone = TimeZone.currentSystemDefault(),
    )
}
