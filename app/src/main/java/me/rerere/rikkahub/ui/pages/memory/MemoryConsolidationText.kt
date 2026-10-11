package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.MemoryConsolidationRun
import me.rerere.rikkahub.data.model.memoryTitleOf

/** 一句话说明一次整理的结果 */
@Composable
internal fun MemoryConsolidationRun.resultText(): String = when {
    error != null -> stringResource(R.string.assistant_page_memory_dream_failed, error)
    !hasChanges -> stringResource(R.string.assistant_page_memory_dream_no_changes)
    else -> stringResource(
        R.string.assistant_page_memory_dream_updated,
        (changedPaths.map { titles[it] ?: memoryFileTitle(it) } +
            deletedPaths.map { stringResource(R.string.assistant_page_memory_dream_removed, memoryFileTitle(it)) })
            .joinToString(", "),
    )
}

// 删掉的文件读不到标题，从文件名推
private fun memoryFileTitle(path: String): String = memoryTitleOf(path.substringAfterLast('/').removeSuffix(".md"))
