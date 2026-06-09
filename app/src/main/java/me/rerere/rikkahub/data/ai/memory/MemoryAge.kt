package me.rerere.rikkahub.data.ai.memory

import kotlin.math.max

/**
 * Renders the age of a memory as a coarse human-readable label for the recall prompt — a port of the
 * Claude-Code `memoryAge.ts` helper (issue #210 §6).
 *
 * Whole-day delta between [referenceMs] (the memory's `updatedAt`) and [nowMs], clamped at >= 0 so a
 * clock skew that puts a memory in the "future" renders "today" rather than a negative count. [nowMs]
 * is injected (never read from `System.currentTimeMillis` inside) so the function is deterministic
 * and unit-testable (property P7).
 *
 * English literals match `buildMemoryPrompt`'s existing English; i18n is out of scope for this slice.
 */
private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

fun memoryAgeDays(referenceMs: Long, nowMs: Long): Long =
    max(0L, (nowMs - referenceMs) / MS_PER_DAY)

fun memoryAgeLabel(referenceMs: Long, nowMs: Long): String =
    when (val days = memoryAgeDays(referenceMs, nowMs)) {
        0L -> "today"
        1L -> "yesterday"
        else -> "$days days ago"
    }
