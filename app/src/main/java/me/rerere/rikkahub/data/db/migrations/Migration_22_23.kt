package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.AutoMigrationSpec

/**
 * v22 -> v23.
 *
 * Adds image-edit metadata to generated media rows:
 * - `GenMediaEntity.type`, defaulting existing rows to image_generation
 * - `GenMediaEntity.source_paths`, nullable
 */
class Migration_22_23 : AutoMigrationSpec
