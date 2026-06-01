package com.gradle.idetour.mcp.tools

import com.google.gson.JsonObject
import com.gradle.idetour.mcp.Schema
import com.gradle.idetour.mcp.Tool
import com.gradle.idetour.mcp.toolError
import com.gradle.idetour.mcp.toolOk

class GetCurrentItemTool : Tool {
    override val name = "get_current_item"
    override val description = "Return the tour's current item id, index, and total count. Used by polling clients to track IDE-driven navigation."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("tourId", Schema.stringProp())
        },
        required = listOf("tourId")
    )

    override fun call(args: JsonObject): JsonObject {
        val tourId = args.get("tourId")?.asString ?: return toolError("Missing tourId")
        val (_, tour) = findTourWithProject(tourId) ?: return toolError("No tour with id $tourId")
        val entry = tour.currentEntry()
        return toolOk {
            addProperty("currentItemId", entry?.id ?: "")
            addProperty("index", tour.currentIndex)
            addProperty("totalItems", tour.items.size)
        }
    }
}
