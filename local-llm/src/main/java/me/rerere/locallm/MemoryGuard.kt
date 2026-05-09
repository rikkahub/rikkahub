package me.rerere.locallm

import android.app.ActivityManager
import android.content.Context

/**
 * Bounds-check before loading a local model into memory. Loading a 4 GB model file on
 * a device with 2 GB free OOMs the app on the next allocation; we want a clean refusal
 * envelope instead. The 0.7 multiplier leaves headroom for the runtime's own
 * working memory (KV cache, sampling buffers, intermediate tensors).
 */
object MemoryGuard {

    /** Fraction of free RAM we'll spend on the model file itself. The remaining 30%
     *  buffers the runtime's own working memory (KV cache, sampling buffers, intermediate
     *  tensors). Surfaced as `requiredFreeBytes` on TooLarge so the UI can present a
     *  consistent comparison instead of silently subtracting the headroom. */
    private const val MODEL_BUDGET_FRACTION = 0.7

    sealed class Decision {
        data object Ok : Decision()
        data class TooLarge(
            val modelFileBytes: Long,
            val availMemBytes: Long,
            /** Total free RAM the user actually needs (model file + ~30% runtime
             *  headroom). Computing this here keeps the headroom multiplier in one
             *  place and lets the UI render a coherent "need X but only Y" message. */
            val requiredFreeBytes: Long,
        ) : Decision()
    }

    /**
     * Pure decision function exposed for unit testing. The Android-aware overload
     * below reads availMem from ActivityManager and delegates here.
     */
    fun decide(modelFileBytes: Long, availMemBytes: Long): Decision {
        val budget = (availMemBytes * MODEL_BUDGET_FRACTION).toLong()
        if (modelFileBytes <= budget) return Decision.Ok
        // Inverse of the budget formula: ceil(modelBytes / 0.7). Using ceil so the
        // reported number is never lower than what would actually be required.
        val required = ((modelFileBytes / MODEL_BUDGET_FRACTION) + 0.5).toLong()
        return Decision.TooLarge(
            modelFileBytes = modelFileBytes,
            availMemBytes = availMemBytes,
            requiredFreeBytes = required,
        )
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
