package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.AutoMigrationSpec

/**
 * v21 → v22 (Phase 12 — Workflows).
 *
 * Adds two tables: `workflows` and `workflow_runs`. Both are net-new — no data fix-up.
 * Room's auto-migration handles the schema delta from the @Entity annotations directly.
 * This spec is here purely as a marker for developers reading the migration list.
 */
class Migration_21_22 : AutoMigrationSpec
