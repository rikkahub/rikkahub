package me.rerere.rikkahub.data.db.migrations

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

@DeleteColumn(tableName = "ConversationEntity", columnName = "usage")
class Migration_8_9 : AutoMigrationSpec
