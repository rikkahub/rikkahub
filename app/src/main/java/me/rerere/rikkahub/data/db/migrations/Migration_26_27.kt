package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds persisted tool-call identity fields used for indexed approval and duplicate-call checks. */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Dropping tool_executions would otherwise cascade-delete its approvals. Stage them
        // without foreign keys, then recreate the v27 relationship after the tool table swap.
        db.execSQL(
            """
            CREATE TABLE `agent_approvals_new` (
                `id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `tool_execution_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `summary_json` TEXT,
                `created_at` INTEGER NOT NULL,
                `resolved_at` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `agent_approvals_new`
            SELECT id, run_id, tool_execution_id, sequence, status, summary_json, created_at, resolved_at
            FROM `agent_approvals`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `agent_approvals`")
        db.execSQL(
            """
            CREATE TABLE `tool_executions_new` (
                `id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `step_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `tool_name` TEXT NOT NULL,
                `tool_call_id` TEXT NOT NULL,
                `input_sha256` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `summary_json` TEXT,
                `error_json` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `started_at` INTEGER,
                `finished_at` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`id`) ON DELETE CASCADE,
                FOREIGN KEY(`step_id`) REFERENCES `agent_steps`(`id`) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        // Pre-27 executions did not persist an identity. Their id is a stable, non-colliding legacy call id.
        db.execSQL(
            """
            INSERT INTO `tool_executions_new`
            SELECT id, run_id, step_id, sequence, tool_name, 'legacy-' || id, '', status, summary_json,
                   error_json, created_at, updated_at, started_at, finished_at
            FROM `tool_executions`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `tool_executions`")
        db.execSQL("ALTER TABLE `tool_executions_new` RENAME TO `tool_executions`")
        db.execSQL(
            """
            CREATE TABLE `agent_approvals` (
                `id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `tool_execution_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `summary_json` TEXT,
                `created_at` INTEGER NOT NULL,
                `resolved_at` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`id`) ON DELETE CASCADE,
                FOREIGN KEY(`tool_execution_id`) REFERENCES `tool_executions`(`id`) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `agent_approvals`
            SELECT id, run_id, tool_execution_id, sequence, status, summary_json, created_at, resolved_at
            FROM `agent_approvals_new`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `agent_approvals_new`")
        db.execSQL("CREATE UNIQUE INDEX `index_tool_executions_run_id_sequence` ON `tool_executions` (`run_id`, `sequence`)")
        db.execSQL("CREATE UNIQUE INDEX `index_tool_executions_run_id_step_id_tool_name_tool_call_id_input_sha256` ON `tool_executions` (`run_id`, `step_id`, `tool_name`, `tool_call_id`, `input_sha256`)")
        db.execSQL("CREATE UNIQUE INDEX `index_tool_executions_run_id_step_id_tool_call_id` ON `tool_executions` (`run_id`, `step_id`, `tool_call_id`)")
        db.execSQL("CREATE INDEX `index_tool_executions_step_id` ON `tool_executions` (`step_id`)")
        db.execSQL("CREATE INDEX `index_tool_executions_status` ON `tool_executions` (`status`)")
        db.execSQL("CREATE INDEX `index_agent_runs_conversation_id_created_at_id` ON `agent_runs` (`conversation_id`, `created_at`, `id`)")
        db.execSQL("CREATE INDEX `index_tool_executions_run_id_tool_name_input_sha256_status` ON `tool_executions` (`run_id`, `tool_name`, `input_sha256`, `status`)")
        db.execSQL("CREATE UNIQUE INDEX `index_agent_approvals_run_id_sequence` ON `agent_approvals` (`run_id`, `sequence`)")
        db.execSQL("CREATE INDEX `index_agent_approvals_tool_execution_id` ON `agent_approvals` (`tool_execution_id`)")
        db.execSQL("CREATE INDEX `index_agent_approvals_status` ON `agent_approvals` (`status`)")
        db.execSQL("CREATE INDEX `index_agent_approvals_run_id_status` ON `agent_approvals` (`run_id`, `status`)")
    }
}
