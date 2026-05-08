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

    @Test fun `llama-cpp picks Vulkan when supported`() {
        val caps = AcceleratorProbe.LlamaCppCapabilities(vulkanSupported = true)
        assertEquals("Vulkan", AcceleratorProbe.pickLlamaCpp(caps))
    }

    @Test fun `llama-cpp falls back to CPU when Vulkan absent`() {
        val caps = AcceleratorProbe.LlamaCppCapabilities(vulkanSupported = false)
        assertEquals("CPU", AcceleratorProbe.pickLlamaCpp(caps))
    }
}
