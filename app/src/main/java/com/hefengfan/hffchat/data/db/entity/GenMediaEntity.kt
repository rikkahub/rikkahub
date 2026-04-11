package com.hefengfan.hffchat.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class GenMediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("prompt")
    val prompt: String,
    @ColumnInfo("create_at")
    val createAt: Long,
)
