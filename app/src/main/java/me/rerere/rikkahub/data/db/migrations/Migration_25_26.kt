package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_runs` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `parent_run_id` TEXT,
                `status` TEXT NOT NULL,
                `config_snapshot_json` TEXT NOT NULL,
                `error_json` TEXT,
                `summary_json` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `started_at` INTEGER,
                `finished_at` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON DELETE CASCADE,
                FOREIGN KEY(`parent_run_id`) REFERENCES `agent_runs`(`id`) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_runs_conversation_id` ON `agent_runs` (`conversation_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_runs_assistant_id` ON `agent_runs` (`assistant_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_runs_parent_run_id` ON `agent_runs` (`parent_run_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_runs_status` ON `agent_runs` (`status`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_runs_conversation_id_status` " +
                "ON `agent_runs` (`conversation_id`, `status`)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_steps` (
                `id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `summary_json` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `finished_at` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`id`) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_steps_run_id_sequence` " +
                "ON `agent_steps` (`run_id`, `sequence`)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tool_executions` (
                `id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `step_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `tool_name` TEXT NOT NULL,
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
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_executions_run_id_sequence` " +
                "ON `tool_executions` (`run_id`, `sequence`)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_executions_step_id` ON `tool_executions` (`step_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_executions_status` ON `tool_executions` (`status`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_approvals` (
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
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_approvals_run_id_sequence` " +
                "ON `agent_approvals` (`run_id`, `sequence`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_approvals_tool_execution_id` " +
                "ON `agent_approvals` (`tool_execution_id`)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_approvals_status` ON `agent_approvals` (`status`)")
    }
}
