package me.rerere.rikkahub.data.db.migrations

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

@DeleteColumn(tableName = "ConversationEntity", columnName = "truncate_index")
class Migration_16_17 : AutoMigrationSpec
