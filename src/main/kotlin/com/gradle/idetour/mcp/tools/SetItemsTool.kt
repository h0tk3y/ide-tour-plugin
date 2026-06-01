package com.gradle.idetour.mcp.tools

import com.google.gson.JsonArray
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

class SetItemsTool : Tool {
    override val name = "set_items"
    override val description = "Replace the entire item list. Disposes prior annotations and resets currentIndex to 0 (auto-activates the first item)."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("tourId", Schema.stringProp())
            add("items", JsonObject().apply {
                addProperty("type", "array")
                add("items", JsonObject().apply { addProperty("type", "object") })
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
                val ids = tour.setItems(specs)
                project.service<ToursService>().materializeForOpenEditors(tour)
                if (tour.currentIndex >= 0) {
                    openCurrentItem(project, tour)
                }
                future.complete(toolOk {
                    add("itemIds", JsonArray().apply { ids.forEach { add(it) } })
                    addProperty("currentIndex", tour.currentIndex)
                })
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "set_items failed"))
            }
        }
        return future.get(20, TimeUnit.SECONDS)
    }
}
