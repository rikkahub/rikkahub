package me.rerere.rikkahub.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.createMessageFtsTables
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import java.io.File

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {
    fun create(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_27_28,
                Migration_30_31,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // 旧版本搜索用 jieba 分词，会把词典解压到这里（约 14MB），现在不再需要
                    File(context.filesDir, "simple_dict").takeIf { it.exists() }?.deleteRecursively()
                    createMessageFtsTables(db)
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
