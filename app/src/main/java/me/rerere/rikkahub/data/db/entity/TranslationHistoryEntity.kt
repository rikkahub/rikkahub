package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 翻译页的一条翻译记录，翻译成功后写入，只保留最近的若干条。
 */
@Entity(
    tableName = "translation_history",
    indices = [Index(value = ["create_at"])]
)
data class TranslationHistoryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("source_text")
    val sourceText: String,
    @ColumnInfo("translated_text")
    val translatedText: String,
    // BCP 47 语言标签
    @ColumnInfo("target_language")
    val targetLanguage: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("create_at")
    val createAt: Long,
)
