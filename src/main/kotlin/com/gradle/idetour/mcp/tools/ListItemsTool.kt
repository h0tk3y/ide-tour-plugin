package com.gradle.idetour.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.gradle.idetour.mcp.Schema
import com.gradle.idetour.mcp.Tool
import com.gradle.idetour.mcp.toolError
import com.gradle.idetour.mcp.toolOk

class ListItemsTool : Tool {
    override val name = "list_items"
    override val description = "Return the tour's items and currentIndex."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply { add("tourId", Schema.stringProp()) },
        required = listOf("tourId")
    )

    override fun call(args: JsonObject): JsonObject {
        val tourId = args.get("tourId")?.asString ?: return toolError("Missing tourId")
        val (_, tour) = findTourWithProject(tourId) ?: return toolError("No tour with id $tourId")

        val itemsArr = JsonArray()
        for (entry in tour.items) {
            itemsArr.add(JsonObject().apply {
                addProperty("id", entry.id)
                addProperty("title", entry.spec.title)
                addProperty("file", entry.spec.file)
                addProperty("inlayCount", entry.spec.inlays.size)
                addProperty("highlightCount", entry.spec.highlights.size)
            })
        }
        return toolOk {
            add("items", itemsArr)
            addProperty("currentIndex", tour.currentIndex)
            addProperty("title", tour.title ?: "")
        }
    }
}
