package me.rerere.rikkahub.automation

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Phase 13 — External Automation config tools.
 *
 * Why these are LLM-callable tools instead of a Settings page in v1:
 *  * The project's existing pattern for security-critical config (Telegram bot setup,
 *    SSH host management, MCP control) is chat-driven via approval-gated tools — no new
 *    Settings page per phase.
 *  * Building a polished Compose Settings screen for the External Automation toggle adds
 *    significant scope without functional payoff: the data model is already done in
 *    [ExternalAutomationConfig], a chat tool surface is enough to flip the bits.
 *  * A future phase can add the Settings UI as polish; the data model, exported activity,
 *    receiver, and trust gate are all designed to outlive that addition.
 *
 * All four mutating tools require approval — flipping any of them changes who's allowed
 * to fire the assistant from outside the app, which is squarely a privilege-escalation
 * surface. None are eligible for "Always Allow" via [ToolApprovalDefaults.NO_ALWAYS_ALLOW]
 * in the current build, but they ARE in [ToolApprovalDefaults.ALWAYS_ASK].
 */

private fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

private fun okEnv(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<UIMessagePart> {
    return listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))
}

fun externalAutomationStatusTool(config: ExternalAutomationConfig): Tool = Tool(
    name = "external_automation_status",
    description = """
        Read-only status of the External Automation feature: whether the master toggle is
        on, which caller packages are trusted, and the most recent invocations (last 20).
        Use this before any mutation to confirm the current state to the user.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    execute = {
        val enabled = config.enabledFlow.first()
        val trusted = config.trustedPackagesFlow.first().toList().sorted()
        val recent = config.recentInvocationsFlow.first().takeLast(20)
        okEnv {
            put("enabled", enabled)
            putJsonArray("trusted_packages") { trusted.forEach { add(it) } }
            putJsonArray("recent_invocations") {
                recent.forEach { entry ->
                    addJsonObject {
                        put("timestamp_ms", entry.timestampMs)
                        put("caller_package", entry.callerPackage)
                        put("action", entry.action)
                        put("status", entry.status)
                        if (!entry.requestId.isNullOrBlank()) put("request_id", entry.requestId)
                    }
                }
            }
        }
    },
)

fun externalAutomationSetEnabledTool(config: ExternalAutomationConfig): Tool = Tool(
    name = "external_automation_set_enabled",
    description = """
        Enable or disable the External Automation Intent API. When OFF (default), the
        exported activity and receiver reject every incoming intent, regardless of caller.
        Turning ON also requires at least one trusted caller package to actually run
        anything end-to-end — set those via external_automation_add_trusted_package.

        Approval-required: flipping this changes the device's outside-callable surface.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("enabled", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("enabled"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val enabled = args.jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return@Tool errEnv("invalid_enabled", "enabled is required (true or false)")
        config.setEnabled(enabled)
        okEnv {
            put("enabled", enabled)
        }
    },
)

fun externalAutomationAddTrustedPackageTool(config: ExternalAutomationConfig): Tool = Tool(
    name = "external_automation_add_trusted_package",
    description = """
        Add a caller package name (e.g. "net.dinglisch.android.taskerm" for Tasker, or
        "com.arlosoft.macrodroid" for MacroDroid) to the trusted list. Trusted callers
        can fire RUN_TASK intents without a per-call dialog when the master toggle is on.
        Untrusted callers are rejected outright in v1 (the per-call approval dialog is
        deferred). Approval-required.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject { put("type", "string") })
            },
            required = listOf("package_name"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val pkg = args.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return@Tool errEnv("invalid_package_name", "package_name is required")
        if (pkg.isEmpty()) {
            return@Tool errEnv("invalid_package_name", "package_name may not be blank")
        }
        // Cheap sanity check on caller package format. Caller package is a Java identifier
        // path: alphanumeric + dots + underscores only. Reject anything else as malformed.
        if (!pkg.matches(Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)*$"""))) {
            return@Tool errEnv(
                "invalid_package_name",
                "package_name '$pkg' is not a valid Android package identifier"
            )
        }
        config.addTrustedPackage(pkg)
        val current = config.trustedPackagesFlow.first()
        okEnv {
            put("added", pkg)
            putJsonArray("trusted_packages") { current.sorted().forEach { add(it) } }
        }
    },
)

fun externalAutomationRemoveTrustedPackageTool(config: ExternalAutomationConfig): Tool = Tool(
    name = "external_automation_remove_trusted_package",
    description = """
        Remove a caller package name from the trusted list. After removal, that caller will
        be rejected on subsequent intent fires. Approval-required.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject { put("type", "string") })
            },
            required = listOf("package_name"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val pkg = args.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return@Tool errEnv("invalid_package_name", "package_name is required")
        if (pkg.isEmpty()) {
            return@Tool errEnv("invalid_package_name", "package_name may not be blank")
        }
        config.removeTrustedPackage(pkg)
        val current = config.trustedPackagesFlow.first()
        okEnv {
            put("removed", pkg)
            putJsonArray("trusted_packages") { current.sorted().forEach { add(it) } }
        }
    },
)
