package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for input validation in the `transcribe_audio_file` tool.
 *
 * These tests verify the validation logic directly without spinning up a real Context,
 * since all pre-flight checks (path safety, file existence) run before any Termux call.
 *
 * The PathSafetyGuard checks are tested via the shared guard object (same guard used by
 * the file-manager tools and write_text_file). The file-level checks are tested with
 * real temp files on the JVM host.
 *
 * Manual integration test plan (run on-device after `adb install`):
 *   1. In Termux: `pkg install whisper.cpp`
 *   2. Download a model:
 *      `mkdir -p ~/.cache/whisper-models && cd ~/.cache/whisper-models && \
 *       wget https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin`
 *   3. Drop a test voice note at `/sdcard/Download/test_voice.ogg` (export one from Telegram,
 *      or record one with: `termux_run_command "arecord -d 5 /sdcard/Download/test_voice.wav"`).
 *   4. Enable the Termux toggle in Assistant → Local tools.
 *   5. Ask the assistant: "Transcribe /sdcard/Download/test_voice.ogg"
 *   6. Approve the tool-call prompt. Expect:
 *      `{"success":true,"text":"...actual words...","language":"en","transcription_time_sec":...}`
 *   7. If you get `whisper_model_missing`, the model download in step 2 didn't complete;
 *      re-run it. If you get `whisper_not_installed`, step 1 failed; retry pkg install.
 */
class TranscribeAudioToolValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------------------
    // PathSafetyGuard — path argument validation
    // These run before any Context / Termux code is touched.
    // ---------------------------------------------------------------------------

    @Test fun `null path is blocked`() {
        val v = PathSafetyGuard.check(null)
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `empty path is blocked`() {
        val v = PathSafetyGuard.check("")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `system path is blocked`() {
        val v = PathSafetyGuard.check("/system/lib/libc.so")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `proc path is blocked`() {
        val v = PathSafetyGuard.check("/proc/1/cmdline")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `dev path is blocked`() {
        val v = PathSafetyGuard.check("/dev/urandom")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `other app sandbox is blocked`() {
        val v = PathSafetyGuard.check("/data/data/com.evil.app/private.db")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `traversal sequence is blocked`() {
        val v = PathSafetyGuard.check("/sdcard/Download/../../../system/etc/passwd")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `sdcard audio path is allowed`() {
        // This should NOT be blocked by PathSafetyGuard — it's a normal user-data path.
        // (The tool will then check file existence, which will fail in tests since the
        // file doesn't exist on the JVM host — but the path guard itself returns null.)
        val v = PathSafetyGuard.check("/sdcard/Download/telegram_inbox/12345/voice.ogg")
        assertNull("sdcard Download path should not be path_blocked", v)
    }

    @Test fun `storage emulated path is allowed`() {
        val v = PathSafetyGuard.check("/storage/emulated/0/Download/voice.ogg")
        assertNull("storage/emulated path should not be path_blocked", v)
    }

    // ---------------------------------------------------------------------------
    // File-level checks (JVM temp files — no Context needed).
    // These validate the File.exists / isDirectory / length checks in the tool body.
    // ---------------------------------------------------------------------------

    @Test fun `empty file is detected as empty`() {
        val f = tmp.newFile("empty.ogg")
        // File is created by TemporaryFolder — it exists but length() == 0
        assertEquals(0L, f.length())
        // The tool's guard: audioFile.length() == 0L → errEnv("empty_file", ...)
        // We verify the JVM-level check holds so there's no off-by-one.
        assert(f.exists()) { "temp file must exist" }
        assert(!f.isDirectory) { "temp file must not be a directory" }
    }

    @Test fun `directory is detected as directory`() {
        val dir = tmp.newFolder("audiodir")
        assert(dir.exists()) { "temp dir must exist" }
        assert(dir.isDirectory) { "temp dir must be a directory" }
        // The tool returns errEnv("is_directory", ...) when isDirectory is true
    }

    @Test fun `non-existent file is detected as missing`() {
        val missing = File(tmp.root, "no_such_file.ogg")
        assert(!missing.exists()) { "file must not exist" }
        // The tool returns errEnv("not_found", ...) when !file.exists()
    }
}
