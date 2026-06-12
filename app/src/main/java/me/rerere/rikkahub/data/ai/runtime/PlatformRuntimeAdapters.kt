package me.rerere.rikkahub.data.ai.runtime

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import me.rerere.ai.runtime.contract.RuntimeClock
import me.rerere.ai.runtime.contract.RuntimeFileRef
import me.rerere.ai.runtime.contract.RuntimeFileStore
import me.rerere.ai.runtime.contract.RuntimeLogSink
import me.rerere.ai.runtime.contract.TaskBudgetClock
import me.rerere.rikkahub.data.files.FilesManager
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Binds the neutral [RuntimeFileStore] over [FilesManager] (issue #243 slice 3). Maps the app
 * `ManagedFileEntity` onto [RuntimeFileRef]; the runtime never sees the Room entity.
 */
class FilesManagerRuntimeFileStore(
    private val filesManager: FilesManager,
) : RuntimeFileStore {
    override suspend fun saveBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ): RuntimeFileRef {
        val entity = filesManager.saveManagedFromBytes(folder, bytes, displayName, mimeType)
        return RuntimeFileRef(
            relativePath = entity.relativePath,
            displayName = entity.displayName,
            mimeType = entity.mimeType,
        )
    }

    override suspend fun read(ref: RuntimeFileRef): ByteArray = withContext(Dispatchers.IO) {
        val entity = filesManager.getByRelativePath(ref.relativePath)
            ?: error("No managed file at ${ref.relativePath}")
        filesManager.getFile(entity).readBytes()
    }
}

/** Binds the neutral [RuntimeLogSink] over the Android platform logger (issue #243 slice 3). */
class AndroidLogSink : RuntimeLogSink {
    override fun info(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun warn(tag: String, msg: String, throwable: Throwable?) {
        Log.w(tag, msg, throwable)
    }

    override fun error(tag: String, msg: String, throwable: Throwable?) {
        Log.e(tag, msg, throwable)
    }
}

/** Binds the neutral [RuntimeClock] over the system clock (issue #243 slice 3). */
class SystemRuntimeClock : RuntimeClock {
    override fun now(): Instant = Clock.System.now()
    override fun timeZone(): TimeZone = TimeZone.currentSystemDefault()
}

/**
 * Binds the neutral [TaskBudgetClock] over a monotonic time source for wall-time budget accounting
 * (SPEC.md M1 / TaskBudgetClock port). Distinct from [SystemRuntimeClock]: budget elapsed time must
 * be immune to wall-clock jumps, so it reads [TimeSource.Monotonic] deltas from a fixed origin
 * captured at construction. Using a real source (not a constant) is what makes the wall-time cap
 * enforceable in the shipped build.
 */
class MonotonicTaskBudgetClock : TaskBudgetClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun monotonicNow(): Duration = origin.elapsedNow()
}
