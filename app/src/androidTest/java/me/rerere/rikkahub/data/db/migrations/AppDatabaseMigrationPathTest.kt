package me.rerere.rikkahub.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.APP_DATABASE_MANUAL_MIGRATIONS
import me.rerere.rikkahub.data.db.APP_DATABASE_VERSION
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationPathTest {
    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allHistoricalSchemasMigrateToLatestRoomSchema() {
        for (startVersion in 1 until APP_DATABASE_VERSION) {
            val dbName = "migration-path-$startVersion"
            context.deleteDatabase(dbName)
            helper.createDatabase(dbName, startVersion).close()

            val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .addMigrations(*APP_DATABASE_MANUAL_MIGRATIONS)
                .build()

            database.openHelper.writableDatabase.use { migrated ->
                assertEquals(
                    "Expected schema $startVersion to migrate to version $APP_DATABASE_VERSION",
                    APP_DATABASE_VERSION,
                    migrated.version
                )

                val tables = buildSet {
                    migrated.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0))
                        }
                    }
                }
                val normalizedTables = tables.mapTo(mutableSetOf()) { it.lowercase() }

                assertTrue(
                    "conversationentity should exist after migration from $startVersion",
                    normalizedTables.contains("conversationentity")
                )
                assertTrue(
                    "message_node should exist after migration from $startVersion",
                    normalizedTables.contains("message_node")
                )
                assertTrue(
                    "scheduled_task_run should exist after migration from $startVersion",
                    normalizedTables.contains("scheduled_task_run")
                )
            }

            database.close()
            context.deleteDatabase(dbName)
        }
    }
}
