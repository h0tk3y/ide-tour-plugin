package com.gradle.idetour.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

class McpProtocol(private val tools: ToolRegistry) {
    fun handle(method: String, params: JsonObject): JsonObject = when (method) {
        "initialize" -> initialize()
        "tools/list" -> tools.toolList()
        "tools/call" -> toolCall(params)
        else -> error("Unknown method: $method")
    }

    private fun initialize(): JsonObject = JsonObject().apply {
        addProperty("protocolVersion", PROTOCOL_VERSION)
        add("capabilities", JsonObject().apply {
            add("tools", JsonObject())
        })
        add("serverInfo", JsonObject().apply {
            addProperty("name", SERVER_NAME)
            addProperty("version", SERVER_VERSION)
        })
    }

    private fun toolCall(params: JsonObject): JsonObject {
        val name = params.get("name")?.asString ?: error("Missing tool name")
        val arguments = params.getAsJsonObject("arguments") ?: JsonObject()
        val toolResult = tools.call(name, arguments)
        val isError = toolResult.get("ok")?.asBoolean == false

        return JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", toolResult.toString())
                })
            })
            addProperty("isError", isError)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val SERVER_NAME = "intellij-tour"

        /** Reported in the MCP `initialize` handshake; sourced from the plugin descriptor so it tracks the build version. */
        val SERVER_VERSION: String =
            PluginManagerCore.getPlugin(PluginId.getId("com.gradle.idetour"))?.version ?: "unknown"
    }
}
