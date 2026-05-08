package me.rerere.locallm

import android.app.ActivityManager
import android.content.Context

/**
 * Bounds-check before loading a local model into memory. Loading a 4 GB GGUF on a
 * device with 2 GB free OOMs the app on the next allocation; we want a clean refusal
 * envelope instead. The 0.7 multiplier leaves headroom for the runtime's own
 * working memory (KV cache, sampling buffers, intermediate tensors).
 */
object MemoryGuard {

    sealed class Decision {
        data object Ok : Decision()
        data class TooLarge(
            val modelFileBytes: Long,
            val availMemBytes: Long,
        ) : Decision()
    }

    /**
     * Pure decision function exposed for unit testing. The Android-aware overload
     * below reads availMem from ActivityManager and delegates here.
     */
    fun decide(modelFileBytes: Long, availMemBytes: Long): Decision {
        val budget = (availMemBytes * 0.7).toLong()
        return if (modelFileBytes <= budget) Decision.Ok
            else Decision.TooLarge(modelFileBytes, availMemBytes)
    }

    /**
     * Production entry point. Reads the live availMem from ActivityManager.
     */
    fun canLoad(context: Context, modelFileBytes: Long): Decision {
        val info = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(info)
        return decide(modelFileBytes, info.availMem)
    }
}
