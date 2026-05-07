package me.rerere.rikkahub.ui.pages.setting.doctor

import android.content.Intent

/**
 * One diagnostic line. The Doctor screen renders these in a flat scrollable list grouped
 * by [category]. Severity decides the colour chip; [fix], when present, surfaces a tap
 * target on the row.
 *
 * Pattern is a fusion of two upstream "doctors" we audited:
 *   - hermes-agent's `hermes doctor` (NousResearch): linear category-by-category print
 *     with "ok / warn / fail / info" markers, plus a `--fix` flag for auto-repairable items.
 *   - openclaw/openclaw's `doctor`: per-mutation repair pipeline where each check returns
 *     `{ changes, warnings, mutated_config }` and the orchestrator applies them in sequence.
 *
 * For RikkaHub-as-Android-app the right shape is hermes' surface (categorized list) with
 * openclaw's per-row "apply this fix" action, since most repairs require user awareness
 * (granting permissions, restarting services) rather than silent config rewrites.
 */
data class DoctorCheck(
    /** Stable id used as the Compose `key` and for state lookup. */
    val id: String,
    val category: DoctorCategory,
    val label: String,
    val detail: String,
    val severity: Severity,
    val fix: FixAction? = null,
)

enum class Severity { OK, INFO, WARN, FAIL }

enum class DoctorCategory(val displayName: String) {
    Permissions("Permissions"),
    Services("Background services"),
    Database("Database"),
    Network("Network & providers"),
    Termux("Termux integration"),
    Maintenance("Maintenance"),
    Diagnostics("Diagnostics"),
}

/**
 * What the row's "Fix" button does. Each variant carries everything needed to apply the
 * remedy without having to re-run the check function. AutoFix runs in-process; the Open*
 * variants hand off to system / in-app settings.
 */
sealed interface FixAction {
    /** Tap this to fire an auto-repair (clear cache, run integrity check, register watcher). */
    data class AutoFix(
        val label: String,
        val run: suspend () -> AutoFixResult,
    ) : FixAction

    /** Open a system Settings activity (battery, notification listener, accessibility, etc.). */
    data class OpenIntent(
        val label: String,
        val intent: Intent,
    ) : FixAction

    /**
     * Navigate to a screen inside the app — e.g. "Open assistants" if a config setting needs
     * tweaking. Stored as a string route key the screen passes to the parent NavController.
     */
    data class OpenAppRoute(
        val label: String,
        val routeKey: AppRouteKey,
    ) : FixAction
}

enum class AppRouteKey {
    SettingTelegram,
    SettingScheduledJobs,
    SettingWorkflows,
    SettingPermissions,
    SettingProvider,
    Assistant,
}

data class AutoFixResult(val ok: Boolean, val message: String)
