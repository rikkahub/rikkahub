package me.rerere.rikkahub.data.sync.cloud

enum class SyncMode {
    AUTO,
    PAUSED,
    LOCAL_ONLY,
    ;

    companion object {
        fun fromStorage(value: String?): SyncMode {
            return entries.firstOrNull { it.name == value } ?: LOCAL_ONLY
        }
    }
}
