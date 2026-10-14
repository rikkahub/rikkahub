package me.rerere.rikkahub.data.repository

import androidx.paging.PagingSource
import me.rerere.rikkahub.data.db.dao.TranslationHistoryDAO
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import kotlin.uuid.Uuid

private const val MAX_HISTORY_COUNT = 100

class TranslationHistoryRepository(
    private val dao: TranslationHistoryDAO,
) {
    fun pagingAll(): PagingSource<Int, TranslationHistoryEntity> = dao.pagingAll()

    suspend fun add(
        sourceText: String,
        translatedText: String,
        targetLanguage: String,
        modelId: Uuid,
    ) {
        dao.insert(
            TranslationHistoryEntity(
                id = Uuid.random().toString(),
                sourceText = sourceText,
                translatedText = translatedText,
                targetLanguage = targetLanguage,
                modelId = modelId.toString(),
                createAt = System.currentTimeMillis(),
            )
        )
        dao.trimTo(MAX_HISTORY_COUNT)
    }

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
