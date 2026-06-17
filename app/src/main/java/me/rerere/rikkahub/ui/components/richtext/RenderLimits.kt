package me.rerere.rikkahub.ui.components.richtext

internal object RenderLimits {
    const val MAX_TABLE_ROWS = 200
    const val MAX_TABLE_COLS = 32
    const val MAX_CELL_CHARS = 4000
    const val MAX_DIFF_LINES = 500
    const val MAX_DIFF_LINE_CHARS = 2000
    const val MAX_HTML_DEPTH = 64
}

internal fun clampTableDimensions(rawRows: Int, rawCols: Int): Pair<Int, Int> {
    val clampedRows = rawRows.coerceAtLeast(0).coerceAtMost(RenderLimits.MAX_TABLE_ROWS)
    val clampedCols = rawCols.coerceAtLeast(0).coerceAtMost(RenderLimits.MAX_TABLE_COLS)
    return clampedRows to clampedCols
}
