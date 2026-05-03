package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun networkTypeName(type: Int): String = when (type) {
    1 -> "GPRS"
    2 -> "EDGE"
    3 -> "UMTS"
    4 -> "CDMA"
    8 -> "HSDPA"
    9 -> "HSUPA"
    10 -> "HSPA"
    13 -> "LTE"
    15 -> "HSPAP"
    18 -> "IWLAN"
    20 -> "NR"
    else -> "unknown"
}

private fun phoneTypeName(type: Int): String = when (type) {
    0 -> "none"
    1 -> "gsm"
    2 -> "cdma"
    3 -> "sip"
    else -> "unknown"
}

fun telephonyInfoTool(context: Context): Tool = Tool(
    name = "get_telephony_info",
    description = """
        Get telephony / SIM information including carrier, network operator, network type,
        phone type, and whether a SIM is present.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val payload = if (!PermissionHelper.hasRuntime(
                context,
                listOf(Manifest.permission.READ_PHONE_STATE)
            )
        ) {
            buildJsonObject { put("error", "permission READ_PHONE_STATE not granted") }
        } else {
            val tm = context.getSystemService(TelephonyManager::class.java)
            if (tm == null) {
                buildJsonObject { put("error", "telephony service unavailable") }
            } else {
                try {
                    val hasSim = tm.simState == TelephonyManager.SIM_STATE_READY
                    val networkType = try {
                        tm.dataNetworkType
                    } catch (_: SecurityException) {
                        @Suppress("DEPRECATION")
                        tm.networkType
                    }
                    buildJsonObject {
                        put("has_sim", hasSim)
                        put("sim_operator", tm.simOperator ?: "")
                        put("sim_country", tm.simCountryIso ?: "")
                        put("network_operator", tm.networkOperator ?: "")
                        put("network_country", tm.networkCountryIso ?: "")
                        put("network_type", networkTypeName(networkType))
                        put("phone_type", phoneTypeName(tm.phoneType))
                    }
                } catch (_: SecurityException) {
                    buildJsonObject { put("error", "permission READ_PHONE_STATE not granted") }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
