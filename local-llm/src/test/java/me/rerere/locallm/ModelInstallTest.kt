package me.rerere.locallm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelInstallTest {

    @Test fun `validUrl accepts well-formed https URLs`() {
        assertTrue(ModelInstall.isValidDownloadUrl("https://huggingface.co/foo/bar/resolve/main/model.task"))
        assertTrue(ModelInstall.isValidDownloadUrl("https://example.com/path/to/model.gguf"))
    }

    @Test fun `validUrl rejects http URLs`() {
        assertEquals(false, ModelInstall.isValidDownloadUrl("http://example.com/model.task"))
    }

    @Test fun `validUrl rejects malformed input`() {
        assertEquals(false, ModelInstall.isValidDownloadUrl(""))
        assertEquals(false, ModelInstall.isValidDownloadUrl("not a url"))
        assertEquals(false, ModelInstall.isValidDownloadUrl("file:///etc/passwd"))
    }

    @Test fun `runtimeForExtension routes task to LiteRT and gguf to llama-cpp`() {
        assertEquals(LocalRuntime.LiteRT, ModelInstall.runtimeForExtension("task"))
        assertEquals(LocalRuntime.LiteRT, ModelInstall.runtimeForExtension("tflite"))
        assertEquals(LocalRuntime.LlamaCpp, ModelInstall.runtimeForExtension("gguf"))
    }

    @Test fun `runtimeForExtension is case-insensitive`() {
        assertEquals(LocalRuntime.LiteRT, ModelInstall.runtimeForExtension("TASK"))
        assertEquals(LocalRuntime.LlamaCpp, ModelInstall.runtimeForExtension("GGUF"))
    }

    @Test fun `runtimeForExtension returns null for unrecognised extension`() {
        assertEquals(null, ModelInstall.runtimeForExtension("bin"))
        assertEquals(null, ModelInstall.runtimeForExtension(""))
    }

    @Test fun `extractFileNameFromUrl pulls the last path segment`() {
        assertEquals(
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            ModelInstall.extractFileNameFromUrl("https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"),
        )
    }

    @Test fun `extractFileNameFromUrl handles query strings`() {
        assertEquals(
            "model.task",
            ModelInstall.extractFileNameFromUrl("https://example.com/path/model.task?download=1"),
        )
    }

    @Test fun `targetFile builds a path under the runtime-specific subdir`() {
        val baseDir = File("/data/data/com.test/files/local-models")
        val out = ModelInstall.targetFile(baseDir, LocalRuntime.LiteRT, "model.task")
        assertEquals(
            "/data/data/com.test/files/local-models/litert/model.task",
            out.absolutePath,
        )
    }
}
