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
import com.intellij.openapi.project.ProjectManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class StartTourTool : Tool {
    override val name = "start_tour"
    override val description = "Allocate a new tour bound to the given project. Optionally populate it with initial items in the same call — strongly preferred over a follow-up add_items, since it saves a round-trip and the user sees the tour appear all at once. Returns the tourId other tools take."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("projectPath", Schema.stringProp("Absolute path of the open project (the project base dir)."))
            add("title", Schema.stringProp("Optional human-readable title shown in the tool window."))
            add("items", JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "Optional initial items. When non-empty, the tour's first item is auto-activated (opens its file and scrolls to anchor).")
                add("items", tourItemSchema())
            })
        },
        required = listOf("projectPath")
    )

    override fun call(args: JsonObject): JsonObject {
        val projectPath = args.get("projectPath")?.asString ?: return toolError("Missing projectPath")
        val title = args.get("title")?.takeIf { !it.isJsonNull }?.asString
        val itemsArr = args.get("items")?.takeIf { it.isJsonArray }?.asJsonArray

        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == projectPath }
            ?: return toolError("No open project at $projectPath")

        val specs = if (itemsArr != null && itemsArr.size() > 0) {
            try {
                itemsArr.map { parseTourItem(it.asJsonObject) }
            } catch (e: Exception) {
                return toolError("Failed to parse items: ${e.message}")
            }
        } else {
            emptyList()
        }

        // Listener cascade from onTourAdded can touch editor models, so dispatch to EDT.
        val future = CompletableFuture<JsonObject>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val service = project.service<ToursService>()
                val tourId = service.start(title)
                if (specs.isEmpty()) {
                    future.complete(toolOk { addProperty("tourId", tourId) })
                } else {
                    val tour = service.get(tourId) ?: error("Tour $tourId missing right after start")
                    val ids = appendItemsAndMaterialize(project, tour, specs)
                    future.complete(toolOk {
                        addProperty("tourId", tourId)
                        add("itemIds", JsonArray().apply { ids.forEach { add(it) } })
                        addProperty("currentIndex", tour.currentIndex)
                    })
                }
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "start_tour failed"))
            }
        }
        return future.get(20, TimeUnit.SECONDS)
    }
}
