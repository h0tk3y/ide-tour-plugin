package com.gradle.idetour.mcp.tools

import com.google.gson.JsonObject
import com.gradle.idetour.mcp.Schema
import com.gradle.idetour.mcp.Tool
import com.gradle.idetour.mcp.toolError
import com.gradle.idetour.mcp.toolOk
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GotoItemTool : Tool {
    override val name = "goto_item"
    override val description = "Activate an item by id or index. Opens its file, scrolls to anchor, repaints inlay prefixes."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("tourId", Schema.stringProp())
            add("itemId", Schema.stringProp("Item id (mutually exclusive with index)."))
            add("index", Schema.intProp("0-based index into the tour's item list."))
        },
        required = listOf("tourId")
    )

    override fun call(args: JsonObject): JsonObject {
        val tourId = args.get("tourId")?.asString ?: return toolError("Missing tourId")
        val itemId = args.get("itemId")?.takeIf { !it.isJsonNull }?.asString
        val index = args.get("index")?.takeIf { !it.isJsonNull }?.asInt

        if (itemId == null && index == null) return toolError("Provide itemId or index")

        val (project, tour) = findTourWithProject(tourId) ?: return toolError("No tour with id $tourId")

        val future = CompletableFuture<JsonObject>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val ok = if (itemId != null) tour.gotoItem(itemId) >= 0 else tour.gotoIndex(index!!)
                if (!ok) {
                    future.complete(toolError("No matching item"))
                    return@invokeLater
                }
                openCurrentItem(project, tour)
                val entry = tour.currentEntry()
                future.complete(toolOk {
                    addProperty("currentItemId", entry?.id ?: "")
                    addProperty("index", tour.currentIndex)
                })
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "goto_item failed"))
            }
        }
        return future.get(10, TimeUnit.SECONDS)
    }
}
