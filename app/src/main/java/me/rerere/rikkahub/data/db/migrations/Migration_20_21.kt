// app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_20_21.kt
package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v20 → v21. Schema delta is auto-handled by Room (all new cols are nullable or have
 * @ColumnInfo(defaultValue=...)). We only need onPostMigrate for the data fix-up:
 * translate "interval" rows into "cron" rows with cron_expression='@every Ns'.
 */
class Migration_20_21 : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            UPDATE scheduled_jobs
            SET scheduleType = 'cron',
                cronExpression = '@every ' || intervalSeconds || 's'
            WHERE scheduleType = 'interval' AND intervalSeconds IS NOT NULL
            """
        )
    }
}
