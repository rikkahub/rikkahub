package me.rerere.rikkahub.skills

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 19D — bundled "featured skills" catalog.
 *
 * The catalog is shipped as `assets/skill-catalog.json` and loaded once when the user
 * opens the catalog sheet. No remote fetch in v1 (deferred to follow-up phase). Parse
 * errors are non-fatal: the catalog returns empty so the UI still renders cleanly with a
 * "no featured skills available" empty state.
 */
@Serializable
data class SkillCatalog(
    val version: Int = 0,
    @SerialName("updated_at")
    val updatedAt: String = "",
    val skills: List<CatalogEntry> = emptyList(),
)

/**
 * One entry in the featured catalog. [sourceUrl] is null for bundled-with-the-APK skills
 * (the four already in `assets/default-skills/`); the install action is a no-op for those
 * since they're already on disk. Non-null `sourceUrl` triggers a fetch through
 * [SkillUrlImporter.importFromUrl] when the user taps Install.
 */
@Serializable
data class CatalogEntry(
    val name: String,
    val title: String,
    val description: String,
    val category: String = "uncategorised",
    val license: String = "",
    @SerialName("size_kb")
    val sizeKb: Int = 0,
    val compatibility: String = "any",
    @SerialName("source_url")
    val sourceUrl: String? = null,
    @SerialName("is_bundled")
    val isBundled: Boolean = false,
)

/**
 * Read + parse `assets/skill-catalog.json`. Empty + warn-logged on any failure.
 */
fun loadCatalogFromAssets(context: Context): SkillCatalog {
    val raw = runCatching {
        context.assets.open("skill-catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull() ?: run {
        Log.w("SkillCatalog", "skill-catalog.json missing from assets — returning empty catalog")
        return SkillCatalog()
    }
    return parseSkillCatalogJson(raw)
}

/**
 * Pure-string parser. Public for JVM-test coverage that doesn't need an Android Context.
 * Returns an empty catalog on any malformed-JSON / unknown-shape input.
 */
fun parseSkillCatalogJson(raw: String): SkillCatalog {
    return runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString(SkillCatalog.serializer(), raw)
    }.getOrElse { t ->
        runCatching { Log.w("SkillCatalog", "skill-catalog.json failed to parse", t) }
        SkillCatalog()
    }
}
