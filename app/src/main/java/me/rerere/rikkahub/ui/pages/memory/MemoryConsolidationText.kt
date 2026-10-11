package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.data.ai.MemoryConsolidationRun
import me.rerere.rikkahub.data.model.memoryTitleOf

/** 一句话说明一次整理的结果 */
internal fun MemoryConsolidationRun.resultText(): String = when {
    error != null -> "Dreaming failed: $error"
    !hasChanges -> "Nothing new to remember"
    else -> "Memory updated: " +
        (changedPaths.map { titles[it] ?: memoryFileTitle(it) } + deletedPaths.map { "removed ${memoryFileTitle(it)}" })
            .joinToString(", ")
}

// 删掉的文件读不到标题，从文件名推
private fun memoryFileTitle(path: String): String = memoryTitleOf(path.substringAfterLast('/').removeSuffix(".md"))
