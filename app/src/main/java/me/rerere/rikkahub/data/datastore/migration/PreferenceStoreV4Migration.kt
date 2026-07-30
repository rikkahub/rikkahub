package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions

class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[SettingsStore.VERSION] ?: 0) < 4

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val migrated = migrateDisabledCustomJsSearchServices(
            servicesJson = prefs[SettingsStore.SEARCH_SERVICES] ?: "[]",
            selectedIndex = prefs[SettingsStore.SEARCH_SELECTED] ?: 0,
        )
        prefs[SettingsStore.SEARCH_SERVICES] = migrated.servicesJson
        prefs[SettingsStore.SEARCH_SELECTED] = migrated.selectedIndex
        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal data class MigratedSearchServices(
    val servicesJson: String,
    val selectedIndex: Int,
)

/** Removes persisted Custom JS providers, which cannot execute safely with the bundled QuickJS wrapper. */
internal fun migrateDisabledCustomJsSearchServices(
    servicesJson: String,
    selectedIndex: Int,
): MigratedSearchServices = runCatching {
    val services = JsonInstant.decodeFromString<List<SearchServiceOptions>>(servicesJson)
    val (migratedServices, migratedSelectedIndex) =
        SearchServiceOptions.migrateDisabledCustomJs(services, selectedIndex)
    MigratedSearchServices(
        servicesJson = JsonInstant.encodeToString(migratedServices),
        selectedIndex = migratedSelectedIndex,
    )
}.getOrElse {
    val fallback: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT)
    MigratedSearchServices(JsonInstant.encodeToString(fallback), 0)
}
