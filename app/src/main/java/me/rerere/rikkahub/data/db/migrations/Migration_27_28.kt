package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds content-free AgentTrace storage without touching existing run, step, tool, or approval rows. */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `agent_trace_events` (
                `id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `timestamp_millis` INTEGER NOT NULL,
                `duration_millis` INTEGER,
                `error_category` TEXT NOT NULL,
                `attributes_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`run_id`) REFERENCES `agent_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_agent_trace_events_run_id_sequence` " +
                "ON `agent_trace_events` (`run_id`, `sequence`)",
        )
        db.execSQL("CREATE INDEX `index_agent_trace_events_created_at` ON `agent_trace_events` (`created_at`)")
    }
}
