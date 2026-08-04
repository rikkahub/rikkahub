package me.rerere.rikkahub.data.db

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.TokenUsageRecorder
import me.rerere.rikkahub.data.db.dao.TokenUsageDAO

class RoomTokenUsageRecorder(
    private val tokenUsageDAO: TokenUsageDAO,
) : TokenUsageRecorder {
    override suspend fun record(usage: TokenUsage) {
        tokenUsageDAO.addUsage(
            promptTokens = usage.promptTokens.toLong(),
            completionTokens = usage.completionTokens.toLong(),
            cachedTokens = usage.cachedTokens.toLong(),
        )
    }
}
