package me.rerere.rikkahub.data.db

import android.content.Context
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration

/** Use the same SQLite extensions for the live database and backup validation. */
internal object SQLiteConfiguration {
    const val DATABASE_NAME = "rikka_hub"

    fun configure(context: Context, configuration: SQLiteDatabaseConfiguration): SQLiteDatabaseConfiguration {
        configuration.customExtensions.add(
            SQLiteCustomExtension(context.applicationInfo.nativeLibraryDir + "/libsimple", null)
        )
        return configuration
    }

    fun openHelperFactory(context: Context) = RequerySQLiteOpenHelperFactory(
        listOf(RequerySQLiteOpenHelperFactory.ConfigurationOptions { configure(context, it) })
    )
}
