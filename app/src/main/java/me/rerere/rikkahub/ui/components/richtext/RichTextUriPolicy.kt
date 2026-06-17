package me.rerere.rikkahub.ui.components.richtext

import java.net.URI

// Model-output (untrusted) links/images are rendered by the richtext renderers. A model can emit a
// clickable javascript:/intent:/file:/content:/data: link or an auto-loading image pointing at a
// local/tracking URI. Gate every richtext link/image sink through these helpers so only safe schemes
// reach LinkAnnotation.Url / Intent.ACTION_VIEW / the image loader. Control characters are rejected on
// the raw input (before trimming) so a leading/trailing/embedded tab/newline/NUL cannot smuggle a
// scheme past the check.

private val LINK_SCHEMES = setOf("http", "https", "mailto")
private val IMAGE_SCHEMES = setOf("http", "https")

private fun parseAllowedUri(raw: String?, allowedSchemes: Set<String>): String? {
    if (raw == null) return null
    if (raw.any { it.isISOControl() }) return null
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in allowedSchemes) return null
    // http/https must be an absolute URL with a real authority; mailto is opaque (no host).
    if ((scheme == "http" || scheme == "https") && uri.host.isNullOrBlank()) return null
    return trimmed
}

/** Returns the trimmed URL only if its scheme is an allowed link scheme (http/https/mailto); else null. */
internal fun sanitizeLinkUri(raw: String?): String? = parseAllowedUri(raw, LINK_SCHEMES)

/** True only for an http/https image URL with a non-blank host; rejects all other/non-http schemes. */
internal fun isAllowedImageUri(raw: String?): Boolean = parseAllowedUri(raw, IMAGE_SCHEMES) != null
