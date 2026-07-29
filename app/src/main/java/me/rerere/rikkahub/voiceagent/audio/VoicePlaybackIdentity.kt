package me.rerere.rikkahub.voiceagent.audio

@JvmInline
value class PlaybackEpoch(val value: Long) : Comparable<PlaybackEpoch> {
    override fun compareTo(other: PlaybackEpoch): Int = value.compareTo(other.value)
}

@JvmInline
internal value class WriterGeneration(val value: Long) : Comparable<WriterGeneration> {
    override fun compareTo(other: WriterGeneration): Int = value.compareTo(other.value)
    fun next(): WriterGeneration = WriterGeneration(value + 1L)
}

@JvmInline
internal value class PlaybackCommandId(val value: Long)

internal data class PlaybackCommandReservation(
    val commandId: PlaybackCommandId,
    val writerGeneration: WriterGeneration,
)
