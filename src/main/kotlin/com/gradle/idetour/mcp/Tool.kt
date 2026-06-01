package com.gradle.idetour.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

interface Tool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    fun call(args: JsonObject): JsonObject
}

internal fun toolOk(extras: JsonObject.() -> Unit = {}): JsonObject = JsonObject().apply {
    addProperty("ok", true)
    extras()
}

internal fun toolError(msg: String): JsonObject = JsonObject().apply {
    addProperty("ok", false)
    addProperty("error", msg)
}

internal object Schema {
    fun stringProp(desc: String? = null): JsonObject = JsonObject().apply {
        addProperty("type", "string")
        if (desc != null) addProperty("description", desc)
    }

    fun intProp(desc: String? = null): JsonObject = JsonObject().apply {
        addProperty("type", "integer")
        if (desc != null) addProperty("description", desc)
    }

    fun obj(properties: JsonObject, required: List<String> = emptyList()): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", properties)
        if (required.isNotEmpty()) {
            add("required", JsonArray().apply { required.forEach { add(it) } })
        }
    }
}
