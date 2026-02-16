package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo

data class MessageNodeMessageRow(
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("messages")
    val messages: String,
)
