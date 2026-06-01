package com.gradle.idetour.mcp.tools

import com.google.gson.JsonObject
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
    val anchor = json.getAsJsonObject("anchor")?.let {
        Position(it.get("line").asInt, it.get("col")?.asInt ?: 1)
    }
    val inlays = json.getAsJsonArray("inlays")?.map { parseInlaySpec(it.asJsonObject) }
        ?: error("Item missing 'inlays'")
    val highlights = json.getAsJsonArray("highlights")?.map { parseHighlightSpec(it.asJsonObject) }
        ?: emptyList()
    return TourItem(id, title, file, anchor, inlays, highlights)
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
