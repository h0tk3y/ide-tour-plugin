package com.gradle.idetour.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

object JsonRpc {
    fun successResponse(id: JsonElement?, result: JsonElement): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: JsonNull.INSTANCE)
        add("result", result)
    }

    fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: JsonNull.INSTANCE)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }
}
