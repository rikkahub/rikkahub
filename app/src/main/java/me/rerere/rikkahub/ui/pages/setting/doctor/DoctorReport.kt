package me.rerere.rikkahub.ui.pages.setting.doctor

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text formatter shared by the in-app Doctor screen ("Copy report" button) and the
 * Telegram /doctor command. Categorised, grouped, with a consistent severity prefix so the
 * output is greppable for support flows.
 */
object DoctorReport {
    fun format(checks: List<DoctorCheck>, header: String = "RikkaHub-agent — diagnostic report"): String =
        buildString {
            appendLine(header)
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            val counts = mutableMapOf<Severity, Int>()
            checks.forEach { counts[it.severity] = (counts[it.severity] ?: 0) + 1 }
            val summary = listOf(Severity.FAIL, Severity.WARN, Severity.OK, Severity.INFO)
                .joinToString("  ") { "${it.name.lowercase()}=${counts[it] ?: 0}" }
            appendLine("Summary: $summary")
            appendLine()
            DoctorCategory.entries.forEach { cat ->
                val rows = checks.filter { it.category == cat }
                if (rows.isEmpty()) return@forEach
                appendLine("## ${cat.displayName}")
                rows.forEach { r ->
                    val mark = when (r.severity) {
                        Severity.OK -> "[ok]   "
                        Severity.INFO -> "[info] "
                        Severity.WARN -> "[warn] "
                        Severity.FAIL -> "[fail] "
                    }
                    appendLine("  $mark ${r.label} — ${r.detail}")
                }
                appendLine()
            }
        }.trimEnd()
}
