package me.rerere.rikkahub.data.db.migrations

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

@DeleteColumn(tableName = "workspaces", columnName = "shell_enabled")
class Migration_22_23 : AutoMigrationSpec
