package com.gradle.idetour.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.gradle.idetour.mcp.Schema
import com.gradle.idetour.mcp.Tool
import com.gradle.idetour.mcp.toolError
import com.gradle.idetour.mcp.toolOk
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AddItemsTool : Tool {
    override val name = "add_items"
    override val description = "Append items to an existing tour. Prefer passing items directly to start_tour for a single round-trip; use this only when adding more items to a tour that's already running."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("tourId", Schema.stringProp())
            add("items", JsonObject().apply {
                addProperty("type", "array")
                add("items", tourItemSchema())
            })
        },
        required = listOf("tourId", "items")
    )

    override fun call(args: JsonObject): JsonObject {
        val tourId = args.get("tourId")?.asString ?: return toolError("Missing tourId")
        val itemsArr = args.get("items")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return toolError("Missing items")

        val (project, tour) = findTourWithProject(tourId) ?: return toolError("No tour with id $tourId")

        val specs = try {
            itemsArr.map { parseTourItem(it.asJsonObject) }
        } catch (e: Exception) {
            return toolError("Failed to parse items: ${e.message}")
        }

        val future = CompletableFuture<JsonObject>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val ids = appendItemsAndMaterialize(project, tour, specs)
                future.complete(toolOk {
                    add("itemIds", JsonArray().apply { ids.forEach { add(it) } })
                    addProperty("currentIndex", tour.currentIndex)
                })
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "add_items failed"))
            }
        }
        return future.get(20, TimeUnit.SECONDS)
    }
}
