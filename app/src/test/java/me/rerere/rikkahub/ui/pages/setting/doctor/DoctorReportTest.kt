package me.rerere.rikkahub.ui.pages.setting.doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-formatter coverage for [DoctorReport.format] - the plain-text report shared by the
 * Doctor screen's "Copy report" button and the Telegram /doctor command. The `Generated:`
 * line is wall-clock dependent and intentionally not asserted; everything else (header,
 * severity summary, category grouping, severity marks, empty-category skipping) is.
 */
class DoctorReportTest {

    private fun check(
        id: String,
        category: DoctorCategory,
        severity: Severity,
        label: String = "label-$id",
        detail: String = "detail-$id",
    ) = DoctorCheck(id = id, category = category, label = label, detail = detail, severity = severity)

    @Test fun `header line is the supplied header`() {
        val out = DoctorReport.format(emptyList(), header = "custom header")
        assertTrue(out.startsWith("custom header\n"))
    }

    @Test fun `default header is used when none supplied`() {
        val out = DoctorReport.format(emptyList())
        assertTrue(out.startsWith("RikkaHub-agent — diagnostic report\n"))
    }

    @Test fun `summary counts every severity`() {
        val out = DoctorReport.format(
            listOf(
                check("a", DoctorCategory.Permissions, Severity.FAIL),
                check("b", DoctorCategory.Permissions, Severity.WARN),
                check("c", DoctorCategory.Permissions, Severity.WARN),
                check("d", DoctorCategory.Services, Severity.OK),
                check("e", DoctorCategory.Services, Severity.INFO),
            ),
        )
        assertTrue(out.contains("Summary: fail=1  warn=2  ok=1  info=1"))
    }

    @Test fun `summary shows zero for absent severities`() {
        val out = DoctorReport.format(listOf(check("a", DoctorCategory.Database, Severity.OK)))
        assertTrue(out.contains("Summary: fail=0  warn=0  ok=1  info=0"))
    }

    @Test fun `rows are grouped under their category display name`() {
        val out = DoctorReport.format(
            listOf(
                check("a", DoctorCategory.Permissions, Severity.OK),
                check("b", DoctorCategory.Database, Severity.FAIL),
            ),
        )
        assertTrue(out.contains("## ${DoctorCategory.Permissions.displayName}"))
        assertTrue(out.contains("## ${DoctorCategory.Database.displayName}"))
    }

    @Test fun `empty categories are skipped`() {
        val out = DoctorReport.format(listOf(check("a", DoctorCategory.Network, Severity.OK)))
        assertTrue(out.contains("## ${DoctorCategory.Network.displayName}"))
        assertTrue(!out.contains("## ${DoctorCategory.Termux.displayName}"))
    }

    @Test fun `each severity gets its mark`() {
        val out = DoctorReport.format(
            listOf(
                check("a", DoctorCategory.Diagnostics, Severity.OK, label = "ok-row"),
                check("b", DoctorCategory.Diagnostics, Severity.INFO, label = "info-row"),
                check("c", DoctorCategory.Diagnostics, Severity.WARN, label = "warn-row"),
                check("d", DoctorCategory.Diagnostics, Severity.FAIL, label = "fail-row"),
            ),
        )
        assertTrue(out.contains("[ok]    ok-row — detail-a"))
        assertTrue(out.contains("[info]  info-row — detail-b"))
        assertTrue(out.contains("[warn]  warn-row — detail-c"))
        assertTrue(out.contains("[fail]  fail-row — detail-d"))
    }

    @Test fun `categories appear in enum declaration order`() {
        val out = DoctorReport.format(
            listOf(
                check("a", DoctorCategory.Maintenance, Severity.OK),
                check("b", DoctorCategory.Permissions, Severity.OK),
            ),
        )
        val permIdx = out.indexOf("## ${DoctorCategory.Permissions.displayName}")
        val maintIdx = out.indexOf("## ${DoctorCategory.Maintenance.displayName}")
        assertTrue(permIdx in 0 until maintIdx)
    }

    @Test fun `output has no trailing whitespace`() {
        val out = DoctorReport.format(listOf(check("a", DoctorCategory.Database, Severity.OK)))
        assertEquals(out, out.trimEnd())
    }
}
