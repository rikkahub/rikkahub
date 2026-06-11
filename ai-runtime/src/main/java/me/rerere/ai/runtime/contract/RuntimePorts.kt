package me.rerere.ai.runtime.contract

import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Neutral handle to a stored file (issue #243 §B). Maps to the app `ManagedFileEntity` at the
 * boundary; the runtime never sees the Room entity.
 */
data class RuntimeFileRef(
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
)

/** Neutral port writing/reading runtime-managed files (issue #243 §B). */
interface RuntimeFileStore {
    suspend fun saveBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ): RuntimeFileRef

    suspend fun read(ref: RuntimeFileRef): ByteArray
}

/**
 * Neutral clock port (issue #243 §B). Uses the SAME `kotlin.time.Instant` /
 * `kotlinx.datetime.TimeZone` types `:ai`'s `Message.kt` uses, so it carries no app dependency. Lets
 * tests inject a fixed clock for deterministic turn timestamps.
 */
interface RuntimeClock {
    fun now(): Instant
    fun timeZone(): TimeZone
}

/**
 * Neutral logging port (issue #243 §B). The method names are deliberately platform-free (info / warn
 * / error) so the runtime never names the Android log type — the §E P1 boundary token. The app binds
 * this over its platform logger at the composition root.
 */
interface RuntimeLogSink {
    fun info(tag: String, msg: String)
    fun warn(tag: String, msg: String, throwable: Throwable? = null)
    fun error(tag: String, msg: String, throwable: Throwable? = null)
}
