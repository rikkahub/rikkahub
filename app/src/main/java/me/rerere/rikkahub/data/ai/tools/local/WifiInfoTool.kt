package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.Formatter
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.net.Inet4Address

fun wifiInfoTool(context: Context): Tool = Tool(
    name = "get_wifi_info",
    description = """
        Get the device's current Wi-Fi connection info including SSID, BSSID, IP address,
        link speed, and signal strength (RSSI). Returns connected=false if not connected.
        On Android 11+ the SSID and BSSID may be redacted by the OS for privacy; the
        response sets ssid_redacted=true in that case while still reporting other fields.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val payload = if (!PermissionHelper.hasRuntime(
                context,
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        ) {
            buildJsonObject { put("error", "permission ACCESS_FINE_LOCATION not granted") }
        } else {
            buildWifiInfoPayload(context.applicationContext)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * Use [ConnectivityManager] as the source of truth for "are we on Wi-Fi" — `WifiManager`
 * is the wrong place to ask on Android 11+ because the OS now returns a redacted
 * `WifiInfo` (networkId=-1, ssid="<unknown ssid>") for privacy reasons even while the
 * device is genuinely connected. The pre-fix code treated networkId=-1 as
 * "disconnected" and the LLM kept telling users their Wi-Fi was off when it wasn't.
 *
 * Strategy:
 *   1. Ask ConnectivityManager whether the active network has TRANSPORT_WIFI. That's
 *      the canonical "is wifi the active connection" check, gated only on
 *      ACCESS_NETWORK_STATE (already in the manifest, granted at install time).
 *   2. If yes, enrich with WifiManager.connectionInfo (deprecated but still the only
 *      way to get RSSI / linkSpeed / frequency). Mark `ssid_redacted=true` when the
 *      OS scrubbed the SSID.
 *   3. Get IP from `ConnectivityManager.getLinkProperties` when WifiManager's
 *      `ipAddress` field is the all-zeros placeholder Android also returns under
 *      privacy redaction.
 */
private fun buildWifiInfoPayload(appContext: Context): kotlinx.serialization.json.JsonObject {
    val cm = appContext.getSystemService(ConnectivityManager::class.java)
        ?: return buildJsonObject { put("error", "connectivity service unavailable") }
    val activeNet = cm.activeNetwork
    val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
    val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    if (!onWifi) {
        return buildJsonObject { put("connected", false) }
    }
    val wm = appContext.getSystemService(WifiManager::class.java)
    return buildJsonObject {
        put("connected", true)
        if (wm == null) {
            put("note", "wifi service unavailable; details omitted")
            return@buildJsonObject
        }
        try {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info == null) {
                put("note", "WifiManager returned no info; details omitted")
                return@buildJsonObject
            }
            putWifiInfoFields(this, info)
        } catch (_: SecurityException) {
            put("note", "WifiManager refused with SecurityException; details omitted")
        }
        // IP from ConnectivityManager.LinkProperties is the reliable path on modern
        // Android — WifiManager.ipAddress returns 0 under redaction.
        val link = activeNet.let { cm.getLinkProperties(it) }
        val ipv4 = link?.linkAddresses
            ?.firstOrNull { (it.address as? Inet4Address) != null && !it.address.isLoopbackAddress }
        if (ipv4 != null) {
            put("ip", ipv4.address.hostAddress ?: "")
        }
    }
}

private fun putWifiInfoFields(builder: JsonObjectBuilder, info: android.net.wifi.WifiInfo) {
    val rawSsid = info.ssid ?: ""
    val ssid = if (rawSsid.startsWith("\"") && rawSsid.endsWith("\"") && rawSsid.length >= 2) {
        rawSsid.substring(1, rawSsid.length - 1)
    } else {
        rawSsid
    }
    // Android exposes literal "<unknown ssid>" (note the angle brackets) when the SSID
    // is redacted under modern privacy rules; surface that distinctly.
    val ssidRedacted = ssid.isBlank() || ssid == "<unknown ssid>" || ssid == "0x"
    if (ssidRedacted) {
        builder.put("ssid_redacted", true)
    } else {
        builder.put("ssid", ssid)
    }
    val rawBssid = info.bssid ?: ""
    // 02:00:00:00:00:00 is Android's "this is redacted, not a real address" sentinel.
    val bssidRedacted = rawBssid.isBlank() || rawBssid == "02:00:00:00:00:00"
    if (!bssidRedacted) {
        builder.put("bssid", rawBssid)
    }
    @Suppress("DEPRECATION")
    val legacyIp = Formatter.formatIpAddress(info.ipAddress) ?: ""
    if (legacyIp.isNotBlank() && legacyIp != "0.0.0.0") {
        // Will be overwritten by the LinkProperties IP later if available; keep as a
        // fallback for older Android where LinkProperties is empty.
        builder.put("ip", legacyIp)
    }
    builder.put("link_speed_mbps", info.linkSpeed)
    builder.put("rssi", info.rssi)
    builder.put("frequency_mhz", info.frequency)
}
