package me.rerere.rikkahub.ui.pages.setting.providerdetail

import me.rerere.ai.provider.Model

/**
 * Pure selection logic for the model browser, kept free of Compose/Android so the rules the UX
 * depends on are JVM-unit-testable: keyword filtering, and the guard that stops "Select all" from
 * enabling a whole large catalog by accident.
 */

/**
 * Filter [all] by [query]: split into space-separated keywords; a model matches when EVERY keyword
 * is a case-insensitive substring of its modelId or displayName. A blank query matches everything.
 */
fun filterModels(all: List<Model>, query: String): List<Model> {
    val keywords = query.split(" ").filter { it.isNotBlank() }
    if (keywords.isEmpty()) return all
    return all.filter { model ->
        keywords.all { keyword ->
            model.modelId.contains(keyword, ignoreCase = true) ||
                model.displayName.contains(keyword, ignoreCase = true)
        }
    }
}

/**
 * Whether "Enable all" may bulk-enable the [filtered] set. Gated behind an ACTIVE [query]
 * (maintainer decision #3): a provider can list hundreds of models, so enabling them all must take
 * narrowing the list first — never one accidental tap on an unfiltered catalog. Also requires at
 * least one not-yet-enabled model (identified by modelId via [enabledIds]) in the filtered set.
 */
fun canBulkEnable(query: String, filtered: List<Model>, enabledIds: Set<String>): Boolean {
    if (query.isBlank()) return false
    return filtered.any { it.modelId !in enabledIds }
}

/**
 * Whether "Disable all" may bulk-disable the [filtered] set: an active [query] (same guard) and
 * every filtered model already enabled — i.e. there is nothing left to enable, so the bulk action
 * flips to clearing the current filter's selection.
 */
fun canBulkDisable(query: String, filtered: List<Model>, enabledIds: Set<String>): Boolean {
    if (query.isBlank() || filtered.isEmpty()) return false
    return filtered.all { it.modelId in enabledIds }
}
