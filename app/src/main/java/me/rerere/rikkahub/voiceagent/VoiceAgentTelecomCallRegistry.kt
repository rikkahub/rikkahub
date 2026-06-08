package me.rerere.rikkahub.voiceagent

interface VoiceAgentTelecomCall {
    fun disconnectFromApp()
}

class VoiceAgentTelecomCallRegistry {
    private val lock = Any()
    private var activeConnection: VoiceAgentTelecomCall? = null

    fun replace(connection: VoiceAgentTelecomCall) {
        val previous = synchronized(lock) {
            activeConnection.also {
                activeConnection = connection
            }
        }
        previous?.disconnectFromApp()
    }

    fun clear(connection: VoiceAgentTelecomCall) {
        synchronized(lock) {
            if (activeConnection === connection) {
                activeConnection = null
            }
        }
    }

    fun hasActiveConnection(): Boolean = synchronized(lock) {
        activeConnection != null
    }

    fun disconnectActive() {
        val connection = synchronized(lock) {
            activeConnection.also {
                activeConnection = null
            }
        }
        connection?.disconnectFromApp()
    }
}
