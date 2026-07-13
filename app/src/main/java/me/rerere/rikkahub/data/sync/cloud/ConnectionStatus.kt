package me.rerere.rikkahub.data.sync.cloud

enum class ConnectionStatus {
    NOT_CONFIGURED,
    CHECKING,
    ONLINE,
    DEGRADED,
    UNREACHABLE,
    AUTH_REQUIRED,
    INCOMPATIBLE,
    LOCAL_MODE,
    PAUSED,
}
