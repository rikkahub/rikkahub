package me.rerere.rikkahub.ui.theme

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSurfaceColorGuardTest {
    @Test
    fun custom_surfaces_always_define_matching_content_color() {
        val sourceRoot = listOf(
            Path.of("src/main/java"),
            Path.of("app/src/main/java"),
        ).firstOrNull { Files.exists(it) } ?: error("Cannot find app/src/main/java")

        val violations = Files.walk(sourceRoot)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
            .flatMap { file ->
                val source = file.readText()
                buildList {
                    addAll(findViolations(file, source, "Scaffold(", "containerColor = CustomColors.topBarColors.containerColor"))
                    addAll(findViolations(file, source, "CardDefaults.cardColors(", "CustomColors.listItemColors.containerColor"))
                    addAll(findViolations(file, source, "CardDefaults.outlinedCardColors(", "CustomColors.listItemColors.containerColor"))
                    addAll(findViolations(file, source, "CardDefaults.cardColors(", "copy(alpha ="))
                    addAll(findViolations(file, source, "CardDefaults.outlinedCardColors(", "copy(alpha ="))
                    addAll(
                        findViolations(
                            file,
                            source,
                            "IconButtonDefaults.filledTonalIconButtonColors(",
                            "CustomColors.listItemColors.containerColor",
                        )
                    )
                }.stream()
            }
            .toList()

        assertTrue(
            buildString {
                appendLine("Unsafe custom surface calls are missing `contentColor`:")
                violations.forEach { appendLine(it) }
            },
            violations.isEmpty(),
        )
    }

    private fun findViolations(
        file: Path,
        source: String,
        callMarker: String,
        requiredNeedle: String,
    ): List<String> {
        val violations = mutableListOf<String>()
        var startIndex = 0
        while (true) {
            val markerIndex = source.indexOf(callMarker, startIndex)
            if (markerIndex == -1) {
                return violations
            }

            val call = extractCall(source = source, markerIndex = markerIndex, callMarker = callMarker)
            if (requiredNeedle in call && "contentColor =" !in call) {
                val line = source.substring(0, markerIndex).count { it == '\n' } + 1
                violations += "${file.toString().replace('\\', '/')}:$line -> $callMarker requires contentColor when using `$requiredNeedle`"
            }
            startIndex = markerIndex + callMarker.length
        }
    }

    private fun extractCall(
        source: String,
        markerIndex: Int,
        callMarker: String,
    ): String {
        var index = markerIndex + callMarker.length
        var depth = 1
        while (index < source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(markerIndex, index + 1)
                    }
                }
            }
            index += 1
        }
        return source.substring(markerIndex)
    }
}
