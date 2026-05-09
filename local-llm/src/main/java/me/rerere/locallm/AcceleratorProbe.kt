package me.rerere.locallm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Decides which accelerator to use for each runtime. Two layers:
 *
 *  - Pure decision function ([pickLiteRt]) takes a capability snapshot and
 *    returns the chosen label. JVM unit-testable.
 *  - Production probe ([probeLiteRt]) reads live device state and feeds the
 *    decision function.
 *
 * The cached choice persists in [LocalRuntimePreferences]; the probe runs once
 * on first model load and again only when the user taps "Re-detect".
 */
object AcceleratorProbe {

    data class LiteRtCapabilities(
        val isQualcomm: Boolean,
        val qnnLibrarySupported: Boolean,
        val gpuDelegateSupported: Boolean,
        val nnapiSupported: Boolean,
    )

    fun pickLiteRt(caps: LiteRtCapabilities): String = when {
        caps.isQualcomm && caps.qnnLibrarySupported -> "QNN"
        caps.gpuDelegateSupported -> "GPU"
        caps.nnapiSupported -> "NNAPI"
        else -> "CPU"
    }

    /**
     * Read the live device capabilities for the LiteRT runtime. Production callers
     * use this; unit tests pass synthesised [LiteRtCapabilities] to [pickLiteRt]
     * directly.
     *
     * @param forceCpu short-circuits to "CPU" without probing — set when the user has
     *   the "Try GPU acceleration" toggle off, OR when the auto-recovery sweep saw a
     *   prior native crash inside liblitertlm and flipped the flag for us.
     */
    fun probeLiteRt(context: Context, forceCpu: Boolean = false): String {
        if (forceCpu) return "CPU"
        val isQualcomm = Build.HARDWARE.contains("qcom", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Qualcomm", ignoreCase = true)
        val qnnLibrarySupported = isQualcomm && runCatching {
            // The QNN delegate is bundled in the LiteRT-LM AAR (litertlm-android). Attempting to
            // load it eagerly fails fast on non-Qualcomm devices or where the right ABI is absent.
            // A failed load leaves the class loader in a partially-initialised state for that
            // library name, but Android's JNI loader is idempotent for subsequent real loads of the
            // same name by the actual runtime — the side effect is acceptable.
            System.loadLibrary("qnn_delegate_jni")
            true
        }.getOrDefault(false)
        // FEATURE_OPENGLES_EXTENSION_PACK is a reasonable proxy for GPU-delegate capability but
        // is only advisory — the LiteRT-LM runtime may still fail to initialise the GPU backend
        // at model-load time even if this returns true. The AcceleratorProbe is therefore
        // intentionally optimistic: prefer GPU when the feature flag suggests it's present, and
        // let the runtime's own error path trigger a re-probe if load fails.
        val gpuDelegateSupported = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_OPENGLES_EXTENSION_PACK,
        )
        val nnapiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        return pickLiteRt(
            LiteRtCapabilities(
                isQualcomm = isQualcomm,
                qnnLibrarySupported = qnnLibrarySupported,
                gpuDelegateSupported = gpuDelegateSupported,
                nnapiSupported = nnapiSupported,
            )
        )
    }
}
