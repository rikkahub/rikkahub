package me.rerere.locallm

import org.junit.Assert.assertEquals
import org.junit.Test

class AcceleratorProbeTest {

    @Test fun `LiteRT picks QNN when Qualcomm and QNN library is loadable`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = true,
            qnnLibrarySupported = true,
            gpuDelegateSupported = true,
            nnapiSupported = true,
        )
        assertEquals("QNN", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT falls back to GPU on Qualcomm if QNN not loadable`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = true,
            qnnLibrarySupported = false,
            gpuDelegateSupported = true,
            nnapiSupported = true,
        )
        assertEquals("GPU", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT picks GPU on non-Qualcomm Mali or Adreno when delegate works`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = false,
            qnnLibrarySupported = false,
            gpuDelegateSupported = true,
            nnapiSupported = true,
        )
        assertEquals("GPU", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT falls back to NNAPI when GPU delegate is unavailable`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = false,
            qnnLibrarySupported = false,
            gpuDelegateSupported = false,
            nnapiSupported = true,
        )
        assertEquals("NNAPI", AcceleratorProbe.pickLiteRt(caps))
    }

    @Test fun `LiteRT falls back to CPU when nothing else works`() {
        val caps = AcceleratorProbe.LiteRtCapabilities(
            isQualcomm = false,
            qnnLibrarySupported = false,
            gpuDelegateSupported = false,
            nnapiSupported = false,
        )
        assertEquals("CPU", AcceleratorProbe.pickLiteRt(caps))
    }

    // -- defaultForceCpu: GPU is the default everywhere except the Google Tensor crash class --

    @Test fun `defaultForceCpu is true for Google Tensor by SOC manufacturer`() {
        assertEquals(true, AcceleratorProbe.defaultForceCpu("Google", "Tensor G3"))
        assertEquals(true, AcceleratorProbe.defaultForceCpu("google", "Tensor G5"))
    }

    @Test fun `defaultForceCpu is true when only the SOC model names Tensor`() {
        assertEquals(true, AcceleratorProbe.defaultForceCpu("UNKNOWN", "Tensor G2"))
    }

    @Test fun `defaultForceCpu is false for Qualcomm`() {
        assertEquals(false, AcceleratorProbe.defaultForceCpu("QTI", "SM7325"))
    }

    @Test fun `defaultForceCpu is false for non-Tensor vendors`() {
        assertEquals(false, AcceleratorProbe.defaultForceCpu("Samsung", "Exynos 2400"))
        assertEquals(false, AcceleratorProbe.defaultForceCpu("MediaTek", "Dimensity 9300"))
    }

    @Test fun `defaultForceCpu is false when SOC info is unavailable`() {
        // Pre-API-31 devices report null SOC_* - no Tensor device runs an OS that old.
        assertEquals(false, AcceleratorProbe.defaultForceCpu(null, null))
    }
}
