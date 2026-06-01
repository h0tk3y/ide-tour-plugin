package com.gradle.idetour.mcp.tools

import com.google.gson.JsonObject
import com.gradle.idetour.mcp.Schema
import com.gradle.idetour.mcp.Tool
import com.gradle.idetour.mcp.toolError
import com.gradle.idetour.mcp.toolOk
import com.gradle.idetour.tours.ToursService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class UpdateItemTool : Tool {
    override val name = "update_item"
    override val description = "Replace an item's spec in place. Same id and position; new title/file/inlays/highlights/anchor."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("tourId", Schema.stringProp())
            add("itemId", Schema.stringProp())
            add("item", JsonObject().apply { addProperty("type", "object") })
        },
        required = listOf("tourId", "itemId", "item")
    )

    override fun call(args: JsonObject): JsonObject {
        val tourId = args.get("tourId")?.asString ?: return toolError("Missing tourId")
        val itemId = args.get("itemId")?.asString ?: return toolError("Missing itemId")
        val itemJson = args.getAsJsonObject("item") ?: return toolError("Missing item")

        val (project, tour) = findTourWithProject(tourId) ?: return toolError("No tour with id $tourId")

        val spec = try {
            parseTourItem(itemJson)
        } catch (e: Exception) {
            return toolError("Failed to parse item: ${e.message}")
        }

        val future = CompletableFuture<JsonObject>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val ok = tour.updateItem(itemId, spec)
                if (!ok) {
                    future.complete(toolError("No item with id $itemId in tour"))
                    return@invokeLater
                }
                project.service<ToursService>().materializeForOpenEditors(tour)
                future.complete(toolOk())
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "update_item failed"))
            }
        }
        return future.get(10, TimeUnit.SECONDS)
    }
}
