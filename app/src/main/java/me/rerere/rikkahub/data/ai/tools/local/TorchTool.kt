package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun torchTool(context: Context): Tool = Tool(
    name = "set_torch",
    description = "Turn the camera flashlight (torch) on or off.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("on", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true to turn the torch on, false to turn it off")
                })
            },
            required = listOf("on")
        )
    },
    execute = {
        val params = it.jsonObject
        val on = params["on"]?.jsonPrimitive?.booleanOrNull
            ?: error("on is required")
        val cm = context.getSystemService(CameraManager::class.java)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "camera service unavailable") }.toString()
                )
            )
        val flashId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return@Tool listOf(
            UIMessagePart.Text(
                buildJsonObject { put("error", "no flash unit available") }.toString()
            )
        )
        val payload = try {
            cm.setTorchMode(flashId, on)
            buildJsonObject {
                put("success", true)
                put("on", on)
            }
        } catch (e: CameraAccessException) {
            buildJsonObject {
                put("error", "torch unavailable: ${e.message ?: "camera access error"}")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
