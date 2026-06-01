package com.gradle.idetour.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun call(name: String, args: JsonObject): JsonObject {
        val tool = byName[name] ?: return toolError("Unknown tool: $name")
        return tool.call(args)
    }

    fun toolList(): JsonObject = JsonObject().apply {
        val arr = JsonArray()
        for (tool in byName.values) {
            arr.add(JsonObject().apply {
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                add("inputSchema", tool.inputSchema)
            })
        }
        add("tools", arr)
    }
}
