package me.rerere.rikkahub.voiceagent

internal fun fastReconnectPolicy(maxAttempts: Int = 3, delayMs: Long = 1L): VoiceReconnectPolicy =
    VoiceReconnectPolicy(
        maxAttempts = maxAttempts,
        maxElapsedMs = 60_000L,
        baseDelayMs = delayMs,
        maxDelayMs = delayMs,
        jitterRatio = 0.0,
        jitterSource = { 0.0 },
    )
