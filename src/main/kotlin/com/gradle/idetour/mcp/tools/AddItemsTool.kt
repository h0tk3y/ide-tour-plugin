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

class AddItemsTool : Tool {
    override val name = "add_items"
    override val description = "Append items to a tour. First call auto-activates item 0 (opens its file and scrolls to anchor)."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("tourId", Schema.stringProp())
            add("items", JsonObject().apply {
                addProperty("type", "array")
                add("items", itemSchema())
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
                val wasEmpty = tour.currentIndex < 0
                val ids = tour.addItems(specs)
                project.service<ToursService>().materializeForOpenEditors(tour)
                if (wasEmpty && tour.currentIndex >= 0) {
                    openCurrentItem(project, tour)
                }
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

    private fun itemSchema(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("id", Schema.stringProp("Optional stable id. Server assigns one if absent."))
            add("title", Schema.stringProp("Short title shown in the tool window."))
            add("file", Schema.stringProp("Project-relative or absolute path."))
            add("location", Schema.stringProp("Optional user-friendly location label shown as a subtitle, e.g. 'TourState.gotoIndex' or 'build.gradle.kts'. Defaults to file basename + first inlay's line."))
            add("anchor", JsonObject().apply {
                addProperty("type", "object")
                addProperty("description", "Scroll target on activation. Defaults to first inlay's line.")
                add("properties", JsonObject().apply {
                    add("line", Schema.intProp())
                    add("col", Schema.intProp())
                })
            })
            add("inlays", JsonObject().apply {
                addProperty("type", "array")
                add("items", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("line", Schema.intProp("1-based line for the inlay anchor."))
                        add("text", Schema.stringProp("Inlay text (state prefix is added automatically)."))
                        add("position", Schema.stringProp("aboveLine | belowLine | endOfLine (default aboveLine)."))
                    })
                    add("required", JsonArray().apply { add("line"); add("text") })
                })
            })
            add("highlights", JsonObject().apply {
                addProperty("type", "array")
                add("items", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("startLine", Schema.intProp())
                        add("startCol", Schema.intProp())
                        add("endLine", Schema.intProp())
                        add("endCol", Schema.intProp())
                    })
                    add("required", JsonArray().apply { add("startLine"); add("startCol"); add("endLine"); add("endCol") })
                })
            })
        })
        add("required", JsonArray().apply { add("title"); add("file"); add("inlays") })
    }
}
