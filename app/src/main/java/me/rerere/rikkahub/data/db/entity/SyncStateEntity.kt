package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: Int = 1,
    @ColumnInfo("server_id")
    val serverId: String? = null,
    @ColumnInfo("device_id")
    val deviceId: String? = null,
    @ColumnInfo("change_cursor", defaultValue = "0")
    val changeCursor: Long = 0,
    @ColumnInfo("sync_mode", defaultValue = "LOCAL_ONLY")
    val syncMode: String = "LOCAL_ONLY",
    @ColumnInfo("last_success_at")
    val lastSuccessAt: Long? = null,
    @ColumnInfo("last_error")
    val lastError: String? = null,
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0,
)
