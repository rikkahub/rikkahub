package me.rerere.rikkahub.service

internal class ActiveGenerationRegistry {
    private val conversationIds = mutableSetOf<String>()

    val size: Int
        @Synchronized get() = conversationIds.size

    @Synchronized
    fun add(conversationId: String): Boolean {
        val wasIdle = conversationIds.isEmpty()
        val added = conversationIds.add(conversationId)
        return added && wasIdle
    }

    @Synchronized
    fun remove(conversationId: String): Boolean {
        val removed = conversationIds.remove(conversationId)
        return removed && conversationIds.isEmpty()
    }
}

internal class GenerationCheckpointPolicy(
    private val intervalMillis: Long,
) {
    private var lastCheckpointAt: Long? = null

    fun shouldCheckpoint(nowMillis: Long, force: Boolean = false): Boolean {
        val previousCheckpointAt = lastCheckpointAt
        val isDue = previousCheckpointAt == null ||
            nowMillis < previousCheckpointAt ||
            nowMillis - previousCheckpointAt >= intervalMillis

        if (!force && !isDue) return false

        lastCheckpointAt = nowMillis
        return true
    }
}
