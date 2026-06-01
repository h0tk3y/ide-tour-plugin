package com.gradle.idetour.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler

class McpHttpHandler(private val protocol: McpProtocol) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                sendPlain(exchange, 405, "Method not allowed")
                return
            }
            if (exchange.requestURI.path != "/mcp") {
                sendPlain(exchange, 404, "Not found")
                return
            }

            val body = exchange.requestBody.bufferedReader().readText()
            val request = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                ?: run {
                    sendJson(exchange, 400, JsonRpc.errorResponse(null, -32700, "Parse error"))
                    return
                }

            val id = request.get("id")
            val method = request.get("method")?.asString
            if (method == null) {
                sendJson(exchange, 400, JsonRpc.errorResponse(id, -32600, "Missing method"))
                return
            }

            if (method.startsWith("notifications/")) {
                sendPlain(exchange, 204, "")
                return
            }

            val params = request.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val response = try {
                val result = protocol.handle(method, params)
                JsonRpc.successResponse(id, result)
            } catch (e: Exception) {
                thisLogger().warn("Error handling MCP method $method", e)
                JsonRpc.errorResponse(id, -32603, e.message ?: "Internal error")
            }
            sendJson(exchange, 200, response)
        } catch (e: Exception) {
            thisLogger().error("Unexpected error in /mcp", e)
            runCatching { sendPlain(exchange, 500, e.message ?: "Internal error") }
        } finally {
            exchange.close()
        }
    }

    private fun sendPlain(exchange: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendJson(exchange: HttpExchange, code: Int, json: JsonObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
