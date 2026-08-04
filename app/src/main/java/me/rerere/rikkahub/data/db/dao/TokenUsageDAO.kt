package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface TokenUsageDAO {
    @Query(
        """
        INSERT INTO token_usage_summary (id, prompt_tokens, completion_tokens, cached_tokens)
        VALUES (0, :promptTokens, :completionTokens, :cachedTokens)
        ON CONFLICT(id) DO UPDATE SET
            prompt_tokens = prompt_tokens + excluded.prompt_tokens,
            completion_tokens = completion_tokens + excluded.completion_tokens,
            cached_tokens = cached_tokens + excluded.cached_tokens
        """
    )
    suspend fun addUsage(
        promptTokens: Long,
        completionTokens: Long,
        cachedTokens: Long,
    )

    @Query(
        """
        SELECT
            COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
            COALESCE(SUM(completion_tokens), 0) AS completionTokens,
            COALESCE(SUM(cached_tokens), 0) AS cachedTokens
        FROM token_usage_summary
        """
    )
    suspend fun getUsage(): TokenUsageTotals
}

data class TokenUsageTotals(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)
