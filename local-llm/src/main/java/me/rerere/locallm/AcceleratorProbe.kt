package me.rerere.locallm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Decides which accelerator to use for each runtime. Two layers:
 *
 *  - Pure decision functions ([pickLiteRt], [pickLlamaCpp]) take a capability
 *    snapshot and return the chosen label. JVM unit-testable.
 *  - Production probes ([probeLiteRt], [probeLlamaCpp]) read live device state
 *    and feed the decision function.
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

    data class LlamaCppCapabilities(
        val vulkanSupported: Boolean,
    )

    fun pickLiteRt(caps: LiteRtCapabilities): String = when {
        caps.isQualcomm && caps.qnnLibrarySupported -> "QNN"
        caps.gpuDelegateSupported -> "GPU"
        caps.nnapiSupported -> "NNAPI"
        else -> "CPU"
    }

    fun pickLlamaCpp(caps: LlamaCppCapabilities): String =
        if (caps.vulkanSupported) "Vulkan" else "CPU"

    /**
     * Read the live device capabilities for the LiteRT runtime. Production callers
     * use this; unit tests pass synthesised [LiteRtCapabilities] to [pickLiteRt]
     * directly.
     */
    fun probeLiteRt(context: Context): String {
        val isQualcomm = Build.HARDWARE.contains("qcom", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Qualcomm", ignoreCase = true)
        val qnnLibrarySupported = isQualcomm && runCatching {
            // The QNN delegate ships as part of the MediaPipe AAR. Attempting to
            // load it eagerly fails fast on devices that lack the right ABI.
            System.loadLibrary("qnn_delegate_jni")
            true
        }.getOrDefault(false)
        val gpuDelegateSupported = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_OPENGLES_EXTENSION_PACK,
        ) || true // default true; real failures surface from the MediaPipe runtime init
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

    /**
     * Read the live device capabilities for the llama.cpp runtime. Vulkan support
     * is reported by the JNI binding once it loads.
     */
    fun probeLlamaCpp(context: Context, jniReportsVulkan: Boolean): String =
        pickLlamaCpp(LlamaCppCapabilities(vulkanSupported = jniReportsVulkan))
}
