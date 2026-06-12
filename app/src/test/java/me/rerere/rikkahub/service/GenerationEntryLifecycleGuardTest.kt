package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guards for the generation entry-point lifecycle contract in [ChatService].
 *
 * [ChatService] is not JVM-instantiable (Context/Room/Koin graph), so these invariants are
 * pinned at the source level — the same pattern as [NoRawPrintlnInServiceWebCommonSpeechTest].
 *
 * Invariant 1 (cancellation rethrow): every `catch (e: Exception)` block in ChatService.kt must
 * rethrow per [me.rerere.rikkahub.utils.shouldRethrowVmError] before reporting. The sendMessage
 * path gained this guard when its CancellationException swallow was fixed; the sibling entry
 * points (regenerateAtMessage, handleToolApproval, translateMessage) silently drifted — their
 * catch blocks swallowed cancellation, so a job cancelled by stopGeneration "completed normally"
 * instead of propagating cancellation, breaking the structured-concurrency contract that
 * CoroutineUtils pins. addError early-returns on CancellationException, which made the swallow
 * invisible. This test fails if any entry point loses (or never gains) the guard again.
 */
class GenerationEntryLifecycleGuardTest {

    @Test
    fun `every Exception catch in ChatService rethrows per shouldRethrowVmError before reporting`() {
        val source = chatServiceSource()
        val violations = mutableListOf<String>()

        var searchFrom = 0
        while (true) {
            val catchIndex = source.indexOf("catch (e: Exception)", searchFrom)
            if (catchIndex < 0) break
            val block = catchBlockBody(source, catchIndex)
            if (!block.contains("if (shouldRethrowVmError(e)) throw e")) {
                val line = source.substring(0, catchIndex).count { it == '\n' } + 1
                violations += "ChatService.kt:$line: catch (e: Exception) without the cancellation rethrow guard"
            }
            searchFrom = catchIndex + 1
        }

        assertFalse("ChatService.kt has no catch (e: Exception) blocks; scan is vacuous", searchFrom == 0)
        assertTrue(
            "Generation catch blocks swallowing CancellationException:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * Invariant 2 (supersede barrier): every generation job registered via `session.setJob(...)`
     * must first drain the job it replaces — `previousJob?.join()`, deliberately unguarded so
     * the calling job's own cancellation propagates instead of being swallowed — before
     * touching `session.state` / re-entering handleMessageComplete. sendMessage added this
     * barrier when the cancel-without-join race was fixed; regenerateAtMessage and
     * handleToolApproval drifted, so the superseded generation's NonCancellable finalizer could
     * persist its sanitized snapshot concurrently with the new job's writes (last-writer-wins on
     * Room + the session StateFlow can resurrect the truncated tail a regenerate just removed).
     * Pinned as a count pairing so the invariant survives a later extraction of the shared
     * lifecycle into one place.
     */
    @Test
    fun `every setJob registration is paired with a previous-job join barrier`() {
        val source = chatServiceSource()
        val registrations = Regex("""session\.setJob\(job\)""").findAll(source).count()
        val barriers = Regex("""previousJob\?\.join\(\)""").findAll(source).count()

        assertTrue("ChatService.kt has no setJob registrations; scan is vacuous", registrations > 0)
        assertTrue(
            "Every generation entry that registers a job must join the job it cancelled first: " +
                "$registrations setJob registration(s) vs $barriers join barrier(s)",
            registrations == barriers
        )
    }

    /** Extracts the `{ ... }` body following a `catch (...)` header via brace counting. */
    private fun catchBlockBody(source: String, catchIndex: Int): String {
        val open = source.indexOf('{', catchIndex)
        require(open > 0) { "catch without a block at index $catchIndex" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        error("Unbalanced braces after catch at index $catchIndex")
    }

    private fun chatServiceSource(): String {
        val candidates = listOf(
            "src/main/java/me/rerere/rikkahub/service/ChatService.kt",
            "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt",
        )
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
        assertTrue(
            "Could not locate ChatService.kt (CWD=${File("").absolutePath}); scan would pass vacuously",
            file != null
        )
        return file!!.readText()
    }
}
