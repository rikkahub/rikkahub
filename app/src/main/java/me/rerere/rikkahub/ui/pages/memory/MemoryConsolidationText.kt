package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.data.ai.MemoryConsolidationRun
import me.rerere.rikkahub.data.model.memoryTitleOf

/** 一句话说明一次整理的结果，提示条和记忆页共用 */
internal fun MemoryConsolidationRun.resultText(): String = when {
    error != null -> "Dreaming failed: $error"
    !hasChanges -> "Nothing new to remember"
    else -> "Memory updated: " +
        (changedPaths.map(::memoryFileTitle) + deletedPaths.map { "removed ${memoryFileTitle(it)}" }).joinToString(", ")
}

private fun memoryFileTitle(path: String): String = memoryTitleOf(path.substringAfterLast('/').removeSuffix(".md"))
