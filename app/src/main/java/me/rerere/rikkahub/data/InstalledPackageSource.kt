package me.rerere.rikkahub.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One installed package the user can pick as an automation scope target. UI-only: this is consumed by
 * the scope picker, never by the agent-facing `list_app` tool (which stays launcher-only).
 */
data class InstalledPackageInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * Enumerates installed packages for the automation scope PICKER — a user-facing, UI-only surface. It
 * is deliberately NOT wired into the agent-facing `list_app` tool (which stays launcher-only via the
 * `<queries>` filter); the model never sees this list.
 *
 * The breadth depends on the FLAVOR manifest, not on code: the sideload build declares
 * `QUERY_ALL_PACKAGES`, so the enumeration returns every package incl. system ones; the play build has
 * no such permission, so Android's package-visibility filtering limits it to the launcher-visible set
 * (own package + the `<queries>` MAIN/LAUNCHER matches). It degrades gracefully — fewer entries, never
 * a crash — so the picker is still useful on play (the user can pick from launchable apps).
 */
interface InstalledPackageSource {
    /** All visible installed packages, system ones included iff [includeSystem]. Sorted by label. */
    suspend fun list(includeSystem: Boolean): List<InstalledPackageInfo>
}

/**
 * Production [InstalledPackageSource] over a real Android [PackageManager]. The heavy enumeration +
 * per-package label load runs on [Dispatchers.IO]; the pure shaping is [filterInstalledPackages].
 */
class AndroidInstalledPackageSource(private val context: Context) : InstalledPackageSource {
    override suspend fun list(includeSystem: Boolean): List<InstalledPackageInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            // getInstalledApplications(Int) — NOT the API-33 ApplicationInfoFlags overload, which would
            // be a NewApi error at minSdk 26. The deprecation warning is expected and correct here.
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                .asSequence()
                .map { it.toInstalledPackageInfo(pm) }
                .filterInstalledPackages(includeSystem)
        }
}

/** Project a raw [ApplicationInfo] to the picker model: human label + system-app flag. */
private fun ApplicationInfo.toInstalledPackageInfo(pm: PackageManager): InstalledPackageInfo =
    InstalledPackageInfo(
        packageName = packageName,
        label = loadLabel(pm).toString(),
        isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
    )

/**
 * Pure filter + sort over enumerated packages (JVM-unit-testable without Android): optionally drop
 * system packages, de-dup by package name, and sort case-insensitively by label then package. Split
 * out of [AndroidInstalledPackageSource] so the list shape the picker renders is testable.
 */
internal fun Sequence<InstalledPackageInfo>.filterInstalledPackages(
    includeSystem: Boolean,
): List<InstalledPackageInfo> =
    filter { includeSystem || !it.isSystem }
        .distinctBy { it.packageName }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
        .toList()
