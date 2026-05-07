package me.rerere.rikkahub.reliability

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "GHReleaseChecker"

/**
 * Checks GitHub Releases for the latest tag of `ExTV/rikkahub-agent` and compares against
 * the locally-installed [BuildConfig.VERSION_NAME]. Pure HTTP — no caching, no scheduler,
 * no UI. Surfaces are responsible for invoking when the user / scheduler asks.
 *
 * Tag schema: every release on this fork ships as `vX.Y.Z-agent.N` where `vX.Y.Z` is the
 * upstream RikkaHub version this fork is built on top of and `N` is the agent revision.
 * The newer-than comparator is lexicographic by (X, Y, Z, N) — works for the current
 * `2.1.15-agent.0` schema. Pre-release suffixes outside `-agent.N` (e.g. `-rc1`) are not
 * supported; if the user ever ships those, this comparator needs revisiting.
 */
class GitHubReleaseChecker(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Release(
        val tag_name: String = "",
        val name: String = "",
        val html_url: String = "",
        val published_at: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val body: String = "",
    )

    sealed class CheckResult {
        data class Available(val current: String, val latest: Release) : CheckResult()
        data class UpToDate(val current: String, val latest: Release) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_URL)
            .get()
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "rikkahub-agent/${BuildConfig.VERSION_NAME}")
            .build()
        val response = try {
            client.newCall(req).execute()
        } catch (t: Throwable) {
            Log.w(TAG, "GitHub release fetch failed", t)
            return@withContext CheckResult.Failed("network error: ${t.message ?: t.javaClass.simpleName}")
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext CheckResult.Failed("github responded ${resp.code}")
            }
            val body = resp.body.string()
            val release = try {
                json.decodeFromString<Release>(body)
            } catch (t: Throwable) {
                return@withContext CheckResult.Failed("could not parse github response: ${t.message ?: t.javaClass.simpleName}")
            }
            val current = BuildConfig.VERSION_NAME
            val latest = release.tag_name.removePrefix("v")
            return@withContext if (isNewer(latest, current)) {
                CheckResult.Available(current, release)
            } else {
                CheckResult.UpToDate(current, release)
            }
        }
    }

    /**
     * True if [latestRaw] is strictly newer than [currentRaw]. Both expected in the
     * `X.Y.Z-agent.N` shape; missing components default to 0 so partial / older formats
     * still compare. Returns false on parse error to fail safe (no spurious update prompt).
     */
    fun isNewer(latestRaw: String, currentRaw: String): Boolean {
        val latest = parse(latestRaw) ?: return false
        val current = parse(currentRaw) ?: return false
        return compareLists(latest, current) > 0
    }

    private fun parse(raw: String): IntArray? {
        // Accepts `2.1.15-agent.0`, `v2.1.15-agent.0`, or just `2.1.15`.
        val cleaned = raw.removePrefix("v").trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('-')
        val core = parts[0].split('.').mapNotNull { it.toIntOrNull() }
        if (core.size !in 1..3) return null
        val agent = if (parts.size > 1) {
            // `agent.N` → just the integer portion
            val tail = parts.drop(1).joinToString("-")
            tail.split('.').lastOrNull()?.toIntOrNull() ?: 0
        } else 0
        return IntArray(4).also {
            it[0] = core.getOrNull(0) ?: 0
            it[1] = core.getOrNull(1) ?: 0
            it[2] = core.getOrNull(2) ?: 0
            it[3] = agent
        }
    }

    private fun compareLists(a: IntArray, b: IntArray): Int {
        for (i in 0..3) {
            val cmp = a[i].compareTo(b[i])
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/ExTV/rikkahub-agent/releases/latest"
    }
}
