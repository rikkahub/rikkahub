package me.rerere.rikkahub.web

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Device-unique mDNS naming (A2A discovery, Option A). Two devices — even the SAME model (e.g. two
 * RMX3636) — must not collide on one `.local` host, so the mDNS HOST label carries a per-device-INSTANCE
 * tag: a model slug + a short, STABLE hash of ANDROID_ID (hashed, never raw — ANDROID_ID is privacy-
 * sensitive). The human-facing SERVICE-INSTANCE name (shown in a Bonjour/mDNS browser) is kept SEPARATE
 * and uses the user's device name. All derivation is pure (the Build/Settings reads are isolated to
 * [deviceMdnsIdentity]) so the slug/label/instance/TXT logic is JVM-unit-testable.
 */

data class MdnsIdentity(
    /** Slugged device model, e.g. `rmx3636` — the human-readable, TRUNCATABLE part of the host label. */
    val modelSlug: String,
    /** Short stable per-device hash — the uniqueness-bearing part, ALWAYS preserved in the host label. */
    val idHash: String,
    /** Human device name for the service-instance label, e.g. `Sebastian's RMX3636`. */
    val displayName: String,
)

/** Slug a free-form string to an mDNS-safe fragment: lowercase, only [a-z0-9-], collapse + trim hyphens. */
internal fun modelSlug(input: String): String =
    input.lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .ifEmpty { "device" }

/** A short, STABLE hex tag from a raw device id (SHA-256, first 12 hex); blank/null id -> "unknown". */
internal fun deviceIdHash(rawId: String?): String {
    val id = rawId?.takeIf { it.isNotBlank() } ?: return "unknown"
    return MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)
}

/**
 * The mDNS HOST label `<prefix>-<model>-<idHash>`, sanitized to [a-z0-9-] and bounded to the 63-char
 * single-label limit. The [idHash] is the uniqueness-bearing part, so it is ALWAYS preserved: only the
 * human-readable [model] slug is truncated to fit (a 60-char Build.MODEL must not chop the hash off the
 * end and make two devices collide). Deterministic + unique per device, so it never relies on JmDNS'
 * conflict auto-rename ("which device is this?").
 */
internal fun mdnsHostLabel(prefix: String, model: String, idHash: String): String {
    val h = modelSlug(idHash)
    if (h.isEmpty()) return modelSlug("$prefix-$model").take(63).trim('-').ifEmpty { "poci" }
    // The hash is the uniqueness-bearing SUFFIX and must survive the 63-char single-label cap for ANY
    // input: reserve it + one separator and fit the (human-readable) prefix+model into whatever remains,
    // truncating ONLY that head. (A hash longer than the label limit would itself be capped — not
    // reachable from deviceIdHash, which is 12 hex.)
    val headBudget = (63 - h.length - 1).coerceAtLeast(0)
    val head = modelSlug("$prefix-$model").take(headBudget).trim('-')
    val label = if (head.isEmpty()) h else "$head-$h"
    return label.take(63).trim('-').ifEmpty { "poci" }
}

/** The human service-instance name shown in mDNS/Bonjour browsers, e.g. `Poci A2A (Sebastian's RMX3636)`. */
internal fun serviceInstanceName(kind: String, displayName: String): String =
    "Poci $kind (${displayName.ifBlank { "device" }})"

/**
 * A2A discovery TXT hints (DNS-SD): REACHABILITY hints only — the bearer token is NEVER advertised.
 * mDNS only tells a peer where the agent is; the route-level bearer gate still decides access.
 */
internal fun a2aTxtRecord(): Map<String, String> = mapOf(
    "path" to "/.well-known/agent-card.json",
    "a2a_path" to "/a2a",
    "poci_kind" to "a2a",
    "auth" to "bearer",
)

/**
 * Read the device identity — the ONLY Android-coupled seam. ANDROID_ID is hashed (privacy); the display
 * name prefers the user-set device name, falling back to the model. Failures degrade to sensible defaults
 * so naming never crashes server startup.
 *
 * SuppressLint(HardwareIds): ANDROID_ID is read ONLY to derive a stable, device-unique mDNS host label
 * (two same-model devices must not collide on one `.local`). It is hashed+truncated, never exposed raw,
 * never sent off-device, and never used for advertising/tracking — exactly the legitimate use HardwareIds
 * warns is the exception to.
 */
@SuppressLint("HardwareIds")
fun deviceMdnsIdentity(resolver: ContentResolver): MdnsIdentity {
    val androidId = runCatching {
        Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()
    val deviceName = runCatching {
        Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
    return MdnsIdentity(
        modelSlug = modelSlug(Build.MODEL),
        idHash = deviceIdHash(androidId),
        displayName = deviceName,
    )
}
