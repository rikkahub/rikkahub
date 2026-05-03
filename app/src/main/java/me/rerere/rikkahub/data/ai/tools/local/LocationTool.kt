package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun errorPayload(message: String): JsonObject =
    buildJsonObject { put("error", message) }

private fun JsonObjectBuilder.putLocation(loc: Location, providerName: String) {
    put("latitude", loc.latitude)
    put("longitude", loc.longitude)
    put("accuracy_m", loc.accuracy)
    if (loc.hasAltitude()) put("altitude", loc.altitude)
    if (loc.hasSpeed()) put("speed", loc.speed)
    if (loc.hasBearing()) put("bearing", loc.bearing)
    put("provider", providerName)
    put("timestamp_ms", loc.time)
}

fun locationTool(context: Context): Tool = Tool(
    name = "get_location",
    description = """
        Get the device's current location (latitude, longitude, accuracy).
        Requires fine location permission and location services to be enabled.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("accuracy", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Location accuracy preference: high, balanced (default), or low"
                    )
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Timeout in milliseconds (default 30000, min 1000, max 60000). After timeout, the tool falls back to the most recent cached fix instead of failing."
                    )
                })
            }
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val accuracyStr = params["accuracy"]?.jsonPrimitive?.contentOrNull ?: "balanced"
        val priority = when (accuracyStr) {
            "high" -> Priority.PRIORITY_HIGH_ACCURACY
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            "low" -> Priority.PRIORITY_LOW_POWER
            else -> null
        }
        val timeoutMs = (params["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 30000)
            .coerceIn(1000, 60000)
            .coerceIn(1000, 30000)

        val payload: JsonObject = when {
            priority == null -> errorPayload("unknown accuracy: $accuracyStr")

            !PermissionHelper.hasRuntime(
                context,
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            ) -> errorPayload("permission ACCESS_FINE_LOCATION not granted")

            else -> {
                val lm = context.getSystemService(LocationManager::class.java)
                if (lm == null) {
                    errorPayload("location services disabled")
                } else {
                    val gpsEnabled = try {
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    } catch (_: Throwable) {
                        false
                    }
                    val networkEnabled = try {
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!gpsEnabled && !networkEnabled) {
                        errorPayload("location services disabled")
                    } else {
                        val gmsAvailable = GoogleApiAvailability.getInstance()
                            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
                        if (gmsAvailable) {
                            try {
                                val client = LocationServices
                                    .getFusedLocationProviderClient(context)
                                val loc: Location? = withTimeoutOrNull(timeoutMs.toLong()) {
                                    client.getCurrentLocation(priority, null).await()
                                }
                                when {
                                    loc != null -> buildJsonObject { putLocation(loc, "fused") }
                                    else -> {
                                        // Fresh fix timed out — fall back to whatever cached
                                        // fix we have (from any provider). A 10-minute-old
                                        // location is still vastly more useful than "no idea".
                                        val cached: Location? = try {
                                            client.lastLocation.await()
                                        } catch (_: Throwable) { null }
                                            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                        if (cached != null) {
                                            buildJsonObject {
                                                putLocation(cached, "fused_cached")
                                                put("cached", true)
                                                put("age_ms", System.currentTimeMillis() - cached.time)
                                            }
                                        } else {
                                            errorPayload("no fix yet — try near a window or outdoors")
                                        }
                                    }
                                }
                            } catch (_: SecurityException) {
                                errorPayload("permission ACCESS_FINE_LOCATION not granted")
                            }
                        } else {
                            try {
                                val loc: Location? =
                                    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                when {
                                    loc == null -> errorPayload("no fix available")
                                    else -> buildJsonObject {
                                        putLocation(loc, loc.provider ?: "unknown")
                                    }
                                }
                            } catch (_: SecurityException) {
                                errorPayload("permission ACCESS_FINE_LOCATION not granted")
                            }
                        }
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
