package me.rerere.locallm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `runtimeForExtension routes litertlm to LiteRT and gguf to llama-cpp`() {
        assertEquals(LocalRuntime.LiteRT, ModelInstall.runtimeForExtension("litertlm"))
        assertEquals(LocalRuntime.LlamaCpp, ModelInstall.runtimeForExtension("gguf"))
        assertEquals(null, ModelInstall.runtimeForExtension("task"))
        assertEquals(null, ModelInstall.runtimeForExtension("tflite"))
    }

    @Test fun `runtimeForExtension is case-insensitive`() {
        assertEquals(LocalRuntime.LiteRT, ModelInstall.runtimeForExtension("LITERTLM"))
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

    // normalizeHuggingFaceUrl ---------------------------------------------------

    @Test fun `normalizeHuggingFaceUrl transforms blob-main to resolve-main`() {
        val blob = "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/blob/main/qwen35_2b_q4.litertlm"
        val expected = "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/resolve/main/qwen35_2b_q4.litertlm"
        assertEquals(expected, ModelInstall.normalizeHuggingFaceUrl(blob))
    }

    @Test fun `normalizeHuggingFaceUrl does not alter a resolve URL`() {
        val resolve = "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/resolve/main/qwen35_2b_q4.litertlm"
        assertEquals(resolve, ModelInstall.normalizeHuggingFaceUrl(resolve))
    }

    @Test fun `normalizeHuggingFaceUrl transforms blob with non-main branch`() {
        val blob = "https://huggingface.co/foo/bar/blob/dev/model.gguf"
        val expected = "https://huggingface.co/foo/bar/resolve/dev/model.gguf"
        assertEquals(expected, ModelInstall.normalizeHuggingFaceUrl(blob))
    }

    @Test fun `normalizeHuggingFaceUrl passes through non-huggingface URLs unchanged`() {
        val url = "https://example.com/models/blob/main/model.gguf"
        assertEquals(url, ModelInstall.normalizeHuggingFaceUrl(url))
    }

    @Test fun `normalizeHuggingFaceUrl passes through already-valid GGUF resolve URL`() {
        val url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        assertEquals(url, ModelInstall.normalizeHuggingFaceUrl(url))
    }

    @Test fun `isValidDownloadUrl accepts blob-form HF URL (normalised before download)`() {
        // blob URLs are valid https; validation passes, then normalizeHuggingFaceUrl converts
        // /blob/ → /resolve/ before the HTTP call. Test that validation does NOT reject them.
        assertTrue(ModelInstall.isValidDownloadUrl(
            "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/blob/main/qwen35_2b_q4.litertlm"
        ))
    }

    // looksLikeHtml ------------------------------------------------------------

    @Test fun `looksLikeHtml detects DOCTYPE preamble`() {
        val html = "<!DOCTYPE html><html>".toByteArray()
        assertTrue(ModelInstall.looksLikeHtml(html, html.size))
    }

    @Test fun `looksLikeHtml detects lowercase doctype`() {
        val html = "<!doctype html><html>".toByteArray()
        assertTrue(ModelInstall.looksLikeHtml(html, html.size))
    }

    @Test fun `looksLikeHtml ignores leading whitespace`() {
        val html = "  \n\n<html>".toByteArray()
        assertTrue(ModelInstall.looksLikeHtml(html, html.size))
    }

    @Test fun `looksLikeHtml detects xml preamble`() {
        val xml = "<?xml version=\"1.0\"?>".toByteArray()
        assertTrue(ModelInstall.looksLikeHtml(xml, xml.size))
    }

    @Test fun `looksLikeHtml false for binary model magic bytes`() {
        // "LMFF" + arbitrary bytes — binary model header, not HTML
        val bin = byteArrayOf(0x4C, 0x4D, 0x46, 0x46, 0x00, 0x01, 0x02, 0x03)
        assertFalse(ModelInstall.looksLikeHtml(bin, bin.size))
    }

    @Test fun `looksLikeHtml false for GGUF magic bytes`() {
        // GGUF magic: 0x47475546 ("GGUF") little-endian
        val bin = byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x03, 0x00, 0x00, 0x00)
        assertFalse(ModelInstall.looksLikeHtml(bin, bin.size))
    }
}
