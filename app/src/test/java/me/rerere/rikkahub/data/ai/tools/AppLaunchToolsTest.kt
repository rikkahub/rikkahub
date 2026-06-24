package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PA1 (no-throw): open_app on a non-launchable/invisible package returns a {success:false,error:...}
 * payload, never throws. The launch lookup is behind the [AppLauncher] seam so it is exercisable on
 * the pure JVM without Android - a fake launcher reports the package as non-launchable.
 */
class AppLaunchToolsTest {

    private fun payloadOf(parts: List<UIMessagePart>): JsonObject {
        val text = (parts.single() as UIMessagePart.Text).text
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `open_app on a non-launchable package returns error result and never throws`() = runBlocking {
        val launcher = object : AppLauncher {
            override fun launch(packageName: String): Boolean = false
            override fun listApps(): List<AppInfo> = emptyList()
        }
        val tool = openAppTool(launcher)

        val args = buildJsonObject { put("package", JsonPrimitive("com.example.absent")) }
        val payload = payloadOf(tool.execute(args))

        assertFalse(payload["success"]!!.jsonPrimitive.content.toBoolean())
        assertNotNull("error must explain the failure to the model", payload["error"])
    }

    @Test
    fun `open_app returns error result when the launch itself throws and never propagates`() = runBlocking {
        // A non-null launch intent does NOT guarantee startActivity succeeds: the app can be
        // uninstalled between resolve and launch (ActivityNotFoundException) or its activity may not be
        // exported (SecurityException). The launcher surfaces that as a thrown exception; open_app's
        // spec contract is to return {success:false,error}, NEVER to throw (the runtime would otherwise
        // convert the throw into a generic stacktrace error, not the structured payload).
        val launcher = object : AppLauncher {
            override fun launch(packageName: String): Boolean =
                throw SecurityException("activity not exported: $packageName")
            override fun listApps(): List<AppInfo> = emptyList()
        }
        val tool = openAppTool(launcher)

        val args = buildJsonObject { put("package", JsonPrimitive("com.example.guarded")) }
        val payload = payloadOf(tool.execute(args))

        assertFalse(payload["success"]!!.jsonPrimitive.content.toBoolean())
        assertNotNull("error must explain the launch failure to the model", payload["error"])
    }

    @Test
    fun `open_app on a launchable package returns success and echoes package`() = runBlocking {
        val launched = mutableListOf<String>()
        val launcher = object : AppLauncher {
            override fun launch(packageName: String): Boolean {
                launched.add(packageName)
                return true
            }
            override fun listApps(): List<AppInfo> = emptyList()
        }
        val tool = openAppTool(launcher)

        val args = buildJsonObject { put("package", JsonPrimitive("com.android.settings")) }
        val payload = payloadOf(tool.execute(args))

        assertTrue(payload["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("com.android.settings", payload["package"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.android.settings"), launched)
    }

    @Test
    fun `list_app returns package and label for each launchable app`() = runBlocking {
        val launcher = object : AppLauncher {
            override fun launch(packageName: String): Boolean = true
            override fun listApps(): List<AppInfo> = listOf(
                AppInfo("com.android.settings", "Settings"),
                AppInfo("com.example.notes", "Notes"),
            )
        }
        val tool = listAppTool(launcher)

        val payload = payloadOf(tool.execute(buildJsonObject { }))
        val apps = payload["apps"]!!.jsonArray
        assertEquals(2, apps.size)
        assertEquals("com.android.settings", apps[0].jsonObject["package"]!!.jsonPrimitive.content)
        assertEquals("Settings", apps[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("com.example.notes", apps[1].jsonObject["package"]!!.jsonPrimitive.content)
        assertEquals("Notes", apps[1].jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun `neither open_app nor list_app requires approval`() {
        val launcher = object : AppLauncher {
            override fun launch(packageName: String): Boolean = true
            override fun listApps(): List<AppInfo> = emptyList()
        }
        assertFalse(openAppTool(launcher).needsApproval)
        assertFalse(listAppTool(launcher).needsApproval)
    }

    @Test
    fun `app launch tool descriptions distinguish launching from UI automation`() {
        val launcher = object : AppLauncher {
            override fun launch(packageName: String): Boolean = true
            override fun listApps(): List<AppInfo> = emptyList()
        }

        assertTrue(openAppTool(launcher).description.contains("does not inspect or control"))
        assertTrue(openAppTool(launcher).description.contains("UI automation tools"))
        assertTrue(listAppTool(launcher).description.contains("does not open, inspect, or control"))
    }

    @Test
    fun `getTools dispatches OpenApp and ListApp options`() {
        val launcher = object : AppLauncher {
            override fun launch(packageName: String): Boolean = true
            override fun listApps(): List<AppInfo> = emptyList()
        }
        val tools = LocalTools.toolsFor(
            options = listOf(LocalToolOption.OpenApp, LocalToolOption.ListApp),
            appLauncher = launcher,
        )
        val names = tools.map { it.name }.toSet()
        assertTrue(names.contains("open_app"))
        assertTrue(names.contains("list_app"))
    }
}
