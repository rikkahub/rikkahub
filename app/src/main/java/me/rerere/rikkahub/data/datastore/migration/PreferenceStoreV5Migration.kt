package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * V5 迁移：强制将所有用户主题更新为 Cyberpunk
 * 
 * 原因：项目已完全重构为单一 Cyberpunk 主题系统，
 * 需要确保所有用户（包括之前已完成 V4 迁移的用户）都使用正确的主题。
 */
class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        
        // 强制关闭动态颜色，使用 Cyberpunk 主题
        prefs[SettingsStore.DYNAMIC_COLOR] = false
        
        // 强制设置主题为 Cyberpunk（覆盖任何旧的主题 ID）
        prefs[SettingsStore.THEME_ID] = "cyberpunk"
        
        // 更新版本号
        prefs[SettingsStore.VERSION] = 5
        
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
