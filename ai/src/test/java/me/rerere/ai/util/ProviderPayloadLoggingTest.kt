package me.rerere.ai.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static source-scan regression guard for issue #96.
 *
 * AI provider implementations must NOT log raw request bodies or raw streaming event
 * payloads to logcat. Those payloads carry system prompts, memories, user content, MCP
 * tool arguments, and base64 media. This test reads the scanned source files and fails if
 * any `Log.*`/`println` call interpolates a known payload identifier, so a future edit
 * cannot silently reintroduce the leak with a different surrounding literal.
 *
 * Message.kt is included as the UI-side delta-merge surface: its streaming fold over
 * `UIMessagePart` deltas (text/image/reasoning/tool) sees the same payload content the
 * providers do, so an unsupported-delta diagnostic there must not raw-`println` the part
 * instance — only its type.
 *
 * It is a pure-JVM test (no Android runtime): under plain unit tests `android.util.Log`
 * is a no-op stub returning 0, so a behavioral assertion on `AiLog` would assert nothing
 * real. The logic under test here is the *absence* of payload interpolation in source,
 * which is exactly what the static scan asserts.
 */
class ProviderPayloadLoggingTest {

    private val relativeSources = listOf(
        "src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt",
        "src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt",
        "src/main/java/me/rerere/ai/provider/providers/ClaudeProvider.kt",
        "src/main/java/me/rerere/ai/provider/providers/GoogleProvider.kt",
        "src/main/java/me/rerere/ai/ui/Message.kt",
    )

    // The local identifiers across the provider files that hold a raw AI payload: request
    // body JSON, the SSE `data` frame, the error-response body, and parsed JSON elements.
    // Interpolating ANY of these into a log/println string leaks payload content (issue #96),
    // regardless of the surrounding literal.
    private val payloadIdentifiers = listOf(
        "data",
        "requestBody",
        "bodyRaw",
        "bodyStr",
        "bodyElement",
        "jsonObject",
        "groundingMetadata",
        "chunks",
        "messages",
        // `url` on an image part holds a raw `data:...;base64,...` string (or a file path);
        // FileEncoder also embeds it in encode-failure exception messages. Logging it leaks
        // media bytes — the image-encode `onFailure` handlers must route through AiLog instead.
        "url",
    )

    // Matches a `Log.<level>(...)` or `println(...)` call up to the end of the line. The
    // leading `(?<![A-Za-z0-9_])` keeps `AiLog.failure(` — our metadata-only helper, which
    // ends in `Log` — from matching as a raw `Log` call. The argument span (everything after
    // the opening paren) is what we scrutinise for payload interpolation, so safe metadata
    // literals like "closing eventSource" never trip it.
    private val logCallRegex = Regex("""(?<![A-Za-z0-9_])(?:Log\.[a-z]+|println)\s*\((.*)$""")
    private val rawLogCallRegex = Regex("""(?<![A-Za-z0-9_])(?:Log\.[a-z]+|println)\s*\(""")

    @Test
    fun `provider source files do not log raw AI payloads`() {
        val moduleRoot = resolveModuleRoot()
        for (relative in relativeSources) {
            val file = File(moduleRoot, relative)
            assertTrue(
                "expected provider source to exist: ${file.absolutePath} " +
                    "(the static payload-logging guard cannot run without it)",
                file.isFile,
            )
            file.readText().lineSequence().forEachIndexed { index, line ->
                val args = logCallRegex.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
                for (id in payloadIdentifiers) {
                    val leak = payloadInterpolationRegex(id)
                    assertFalse(
                        "$relative:${index + 1} must not log raw AI payload: " +
                            "log/println argument references payload identifier `$id`. " +
                            "Use me.rerere.ai.util.AiLog metadata-only logging instead.\n" +
                            "    $line",
                        leak.containsMatchIn(args),
                    )
                }
            }
        }
    }

    @Test
    fun `provider source files do not call printStackTrace`() {
        val moduleRoot = resolveModuleRoot()
        for (relative in relativeSources) {
            val file = File(moduleRoot, relative)
            file.readText().lineSequence().forEachIndexed { index, line ->
                // A printStackTrace on an encode/parse failure prints the throwable message,
                // which FileEncoder builds from the image `url` (raw base64) — issue #96.
                // Route failures through AiLog.failure (type only) instead. The `//` guard
                // keeps explanatory comments that mention printStackTrace from tripping it.
                val code = line.substringBefore("//")
                assertFalse(
                    "$relative:${index + 1} must not call printStackTrace(): it leaks the " +
                        "throwable message (which can carry raw base64/file data). " +
                        "Use me.rerere.ai.util.AiLog.failure instead.\n    $line",
                    Regex("""\bprintStackTrace\s*\(""").containsMatchIn(code),
                )
            }
        }
    }

    @Test
    fun `scanned source files do not call println`() {
        val moduleRoot = resolveModuleRoot()
        val printlnRegex = Regex("""(?<![A-Za-z0-9_])println\s*\(""")
        for (relative in relativeSources) {
            val file = File(moduleRoot, relative)
            file.readText().lineSequence().forEachIndexed { index, line ->
                // println goes to raw stdout (not even logcat), so its argument is never
                // redactable — `println(... $deltaPart)` dumps a whole UIMessagePart instance
                // (text/tool args/base64 media) to stdout (issue #98). The `//` guard keeps
                // explanatory comments that mention println from tripping it.
                val code = line.substringBefore("//")
                assertFalse(
                    "$relative:${index + 1} must not call println(): it writes unredacted " +
                        "output to stdout, which can carry raw AI payload. " +
                        "Use me.rerere.ai.util.AiLog (type-only) instead.\n    $line",
                    printlnRegex.containsMatchIn(code),
                )
            }
        }
    }

    @Test
    fun `SSE onFailure routes through AiLog, never a raw Log call`() {
        val moduleRoot = resolveModuleRoot()
        for (relative in relativeSources) {
            val source = File(moduleRoot, relative).readText()
            for (body in extractOnFailureBodies(source)) {
                assertFalse(
                    "$relative: onFailure() must report failures via AiLog.failure, not a raw " +
                        "Log.*/println call — the throwable funnelled here can carry the SSE " +
                        "`data` snippet in its message (issue #96).",
                    rawLogCallRegex.containsMatchIn(body),
                )
            }
        }
    }

    // Builds a regex that matches `$id` / `${...id...}` string-template interpolation OR a
    // bare positional argument (`Log.d(TAG, id)` / `println(id)`) referencing the payload
    // identifier as a whole word — but NOT `$attempt`, `$url`, or `metadata` (word-boundary
    // guarded so `data` does not match inside `groundingMetadata`/`mandatory`).
    private fun payloadInterpolationRegex(id: String): Regex {
        val word = """(?<![A-Za-z0-9_])$id(?![A-Za-z0-9_])"""
        // `$id` (simple template), `${...id...}` (block template), or `, id` / `(id` (bare arg).
        return Regex("""\$$word|\$\{[^}]*$word[^}]*}|(?:^|[(,]\s*)$word\s*[),]""")
    }

    // Returns the brace-balanced body of every `override fun onFailure(` in the source, so the
    // routing assertion only inspects the SSE failure handler and not the whole file.
    private fun extractOnFailureBodies(source: String): List<String> {
        val bodies = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val sig = source.indexOf("fun onFailure(", searchFrom)
            if (sig < 0) break
            val open = source.indexOf('{', sig)
            if (open < 0) break
            var depth = 0
            var i = open
            while (i < source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            bodies += source.substring(open, minOf(i + 1, source.length))
            searchFrom = i + 1
        }
        return bodies
    }

    // Gradle runs unit tests with the module dir as the working dir, so `src/...` resolves
    // directly. Walk up from user.dir as a fallback (e.g. if run from the repo root), and
    // fail loudly if the sources can't be found so the guard never silently passes.
    private fun resolveModuleRoot(): File {
        val userDir = System.getProperty("user.dir")
            ?: throw IllegalStateException("user.dir system property is not set")
        val workingDir = File(userDir)
        val probe = relativeSources.first()
        var dir: File? = workingDir
        while (dir != null) {
            if (File(dir, probe).isFile) return dir
            if (File(dir, "ai/$probe").isFile) return File(dir, "ai")
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "could not locate the :ai module source root from user.dir=${workingDir.absolutePath}; " +
                "the payload-logging guard cannot resolve provider sources",
        )
    }
}
