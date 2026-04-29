package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * V4 迁移：强制关闭动态颜色，启用 Cyberpunk 主题
 * 
 * 原因：项目已重构为只保留 Cyberpunk 主题，但之前保存的用户设置中
 * dynamicColor 可能为 true，导致使用系统动态颜色而非 Cyberpunk 主题。
 */
class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        
        // 强制关闭动态颜色
        prefs[SettingsStore.DYNAMIC_COLOR] = false
        
        // 强制设置主题为 Cyberpunk（项目已重构为单一主题）
        prefs[SettingsStore.THEME_ID] = "cyberpunk"
        
        // 更新版本号
        prefs[SettingsStore.VERSION] = 4
        
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
