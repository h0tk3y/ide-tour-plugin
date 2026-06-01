package com.gradle.idetour.mcp.tools

import com.google.gson.JsonObject
import com.gradle.idetour.mcp.Schema
import com.gradle.idetour.mcp.Tool
import com.gradle.idetour.mcp.toolError
import com.gradle.idetour.mcp.toolOk
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NextItemTool : Tool {
    override val name = "next_item"
    override val description = "Advance the tour by one item. No-op at the last item."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply { add("tourId", Schema.stringProp()) },
        required = listOf("tourId")
    )

    override fun call(args: JsonObject): JsonObject {
        val tourId = args.get("tourId")?.asString ?: return toolError("Missing tourId")
        val (project, tour) = findTourWithProject(tourId) ?: return toolError("No tour with id $tourId")

        val future = CompletableFuture<JsonObject>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val target = tour.currentIndex + 1
                val advanced = target in tour.items.indices && tour.gotoIndex(target)
                if (advanced) openCurrentItem(project, tour)
                val entry = tour.currentEntry()
                future.complete(toolOk {
                    addProperty("currentItemId", entry?.id ?: "")
                    addProperty("index", tour.currentIndex)
                    addProperty("advanced", advanced)
                })
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "next_item failed"))
            }
        }
        return future.get(10, TimeUnit.SECONDS)
    }
}
