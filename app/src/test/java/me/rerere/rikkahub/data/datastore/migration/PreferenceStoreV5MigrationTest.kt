package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreV5MigrationTest {

    @Test
    fun `legacy a2a enablement is reset before standalone service autostart`() = runBlocking {
        val migration = PreferenceStoreV5Migration()
        val migrated = migration.migrate(
            preferencesOf(
                SettingsStore.VERSION to 4,
                SettingsStore.A2A_ENABLED to true,
            )
        )

        assertFalse(migrated[SettingsStore.A2A_ENABLED] == true)
        assertEquals(5, migrated[SettingsStore.VERSION])
    }

    @Test
    fun `version five preferences do not rerun migration`() = runBlocking {
        val migration = PreferenceStoreV5Migration()

        assertTrue(migration.shouldMigrate(preferencesOf(SettingsStore.VERSION to 4)))
        assertFalse(migration.shouldMigrate(preferencesOf(SettingsStore.VERSION to 5)))
    }
}
