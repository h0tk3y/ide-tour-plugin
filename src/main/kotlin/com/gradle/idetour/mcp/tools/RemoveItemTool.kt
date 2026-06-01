package com.gradle.idetour.mcp.tools

import com.google.gson.JsonObject
import com.gradle.idetour.mcp.Schema
import com.gradle.idetour.mcp.Tool
import com.gradle.idetour.mcp.toolError
import com.gradle.idetour.mcp.toolOk
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RemoveItemTool : Tool {
    override val name = "remove_item"
    override val description = "Remove an item from the tour. Disposes its annotations and adjusts currentIndex if needed."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("tourId", Schema.stringProp())
            add("itemId", Schema.stringProp())
        },
        required = listOf("tourId", "itemId")
    )

    override fun call(args: JsonObject): JsonObject {
        val tourId = args.get("tourId")?.asString ?: return toolError("Missing tourId")
        val itemId = args.get("itemId")?.asString ?: return toolError("Missing itemId")

        val (_, tour) = findTourWithProject(tourId) ?: return toolError("No tour with id $tourId")

        val future = CompletableFuture<JsonObject>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val ok = tour.removeItem(itemId)
                future.complete(if (ok) toolOk { addProperty("currentIndex", tour.currentIndex) }
                                else toolError("No item with id $itemId in tour"))
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "remove_item failed"))
            }
        }
        return future.get(10, TimeUnit.SECONDS)
    }
}
