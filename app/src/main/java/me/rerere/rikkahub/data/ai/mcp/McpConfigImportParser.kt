package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object McpConfigImportParser {
    fun parseMcpServersFromJson(json: String): List<McpServerConfig> {
        val root = Json.parseToJsonElement(json).jsonObject
        val mcpServers = root["mcpServers"]?.jsonObject ?: return emptyList()
        return mcpServers.entries.mapNotNull { (name, element) ->
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val headers = obj["headers"]?.jsonObject?.entries?.map { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: "")
            } ?: emptyList()
            val commonOptions = McpCommonOptions(name = name, headers = headers)

            val command = obj["command"]?.jsonPrimitive?.contentOrNull
            if (!command.isNullOrBlank() || type == "stdio") {
                if (command.isNullOrBlank()) return@mapNotNull null
                val parsedArgs = obj["args"]?.let { argsElement ->
                    runCatching {
                        argsElement.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                    }.getOrDefault(emptyList())
                } ?: emptyList()
                val env = obj["env"]?.jsonObject?.entries?.map { (k, v) ->
                    k to (v.jsonPrimitive.contentOrNull ?: "")
                } ?: emptyList()
                val workdir = obj["cwd"]?.jsonPrimitive?.contentOrNull
                    ?: obj["workdir"]?.jsonPrimitive?.contentOrNull
                    ?: "/data/data/com.termux/files/home"
                return@mapNotNull McpServerConfig.StdioServer(
                    commonOptions = commonOptions,
                    command = command.orEmpty(),
                    args = parsedArgs,
                    env = env,
                    workdir = workdir,
                )
            }

            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            when (type) {
                "sse" -> McpServerConfig.SseTransportServer(commonOptions = commonOptions, url = url)
                else -> McpServerConfig.StreamableHTTPServer(commonOptions = commonOptions, url = url)
            }
        }
    }
}
