package com.gradle.idetour.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.gradle.idetour.mcp.Schema
import com.gradle.idetour.tours.TourState
import com.gradle.idetour.tours.ToursService
import com.gradle.idetour.tours.model.HighlightSpec
import com.gradle.idetour.tours.model.InlayPosition
import com.gradle.idetour.tours.model.InlaySpec
import com.gradle.idetour.tours.model.Position
import com.gradle.idetour.tours.model.TourItem
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

internal fun findTourWithProject(tourId: String): Pair<Project, TourState>? {
    for (project in ProjectManager.getInstance().openProjects) {
        val tour = project.service<ToursService>().get(tourId) ?: continue
        return project to tour
    }
    return null
}

internal fun openCurrentItem(project: Project, tour: TourState) {
    com.gradle.idetour.tours.openCurrentItem(project, tour)
}

internal fun parseTourItem(json: JsonObject): TourItem {
    val title = json.get("title")?.asString ?: error("Item missing 'title'")
    val file = json.get("file")?.asString ?: error("Item missing 'file'")
    val id = json.get("id")?.takeIf { !it.isJsonNull }?.asString
    val location = json.get("location")?.takeIf { !it.isJsonNull }?.asString
    val anchor = json.getAsJsonObject("anchor")?.let {
        Position(it.get("line").asInt, it.get("col")?.asInt ?: 1)
    }
    val inlays = json.getAsJsonArray("inlays")?.map { parseInlaySpec(it.asJsonObject) }
        ?: error("Item missing 'inlays'")
    val highlights = json.getAsJsonArray("highlights")?.map { parseHighlightSpec(it.asJsonObject) }
        ?: emptyList()
    return TourItem(id, title, file, location, anchor, inlays, highlights)
}

private fun parseInlaySpec(json: JsonObject): InlaySpec {
    val line = json.get("line").asInt
    val text = json.get("text").asString
    val position = parseInlayPosition(json.get("position")?.takeIf { !it.isJsonNull }?.asString)
    return InlaySpec(line, text, position)
}

private fun parseHighlightSpec(json: JsonObject): HighlightSpec = HighlightSpec(
    json.get("startLine").asInt,
    json.get("startCol").asInt,
    json.get("endLine").asInt,
    json.get("endCol").asInt
)

private fun parseInlayPosition(s: String?): InlayPosition = when (s) {
    null, "aboveLine", "above" -> InlayPosition.AboveLine
    "belowLine", "below" -> InlayPosition.BelowLine
    "endOfLine", "after", "inline" -> InlayPosition.EndOfLine
    else -> InlayPosition.AboveLine
}

/** Shared JSON schema for a single tour item — used by both `start_tour` (optional initial items) and `add_items`. */
internal fun tourItemSchema(): JsonObject = JsonObject().apply {
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

/**
 * Append [specs] to [tour] within a project, materialize them on currently open editors,
 * and auto-activate the first item if the tour was empty before.
 *
 * Must be called on the EDT.
 */
internal fun appendItemsAndMaterialize(
    project: Project,
    tour: TourState,
    specs: List<TourItem>
): List<String> {
    val wasEmpty = tour.currentIndex < 0
    val ids = tour.addItems(specs)
    project.service<ToursService>().materializeForOpenEditors(tour)
    if (wasEmpty && tour.currentIndex >= 0) {
        openCurrentItem(project, tour)
    }
    return ids
}
