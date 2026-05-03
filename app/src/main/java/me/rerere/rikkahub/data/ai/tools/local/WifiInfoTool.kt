package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun wifiInfoTool(context: Context): Tool = Tool(
    name = "get_wifi_info",
    description = """
        Get the device's current Wi-Fi connection info including SSID, BSSID, IP address,
        link speed, and signal strength (RSSI). Returns connected=false if not connected.
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
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            if (wm == null) {
                buildJsonObject { put("error", "wifi service unavailable") }
            } else {
                try {
                    @Suppress("DEPRECATION")
                    val info = wm.connectionInfo
                    if (info == null || info.networkId == -1 || info.bssid == null) {
                        buildJsonObject { put("connected", false) }
                    } else {
                        val rawSsid = info.ssid ?: ""
                        val ssid = if (rawSsid.startsWith("\"") && rawSsid.endsWith("\"") && rawSsid.length >= 2) {
                            rawSsid.substring(1, rawSsid.length - 1)
                        } else {
                            rawSsid
                        }
                        @Suppress("DEPRECATION")
                        val ip = Formatter.formatIpAddress(info.ipAddress)
                        buildJsonObject {
                            put("connected", true)
                            put("ssid", ssid)
                            put("bssid", info.bssid ?: "")
                            put("ip", ip ?: "")
                            put("link_speed_mbps", info.linkSpeed)
                            put("rssi", info.rssi)
                            put("frequency_mhz", info.frequency)
                        }
                    }
                } catch (_: SecurityException) {
                    buildJsonObject { put("error", "permission ACCESS_FINE_LOCATION not granted") }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
