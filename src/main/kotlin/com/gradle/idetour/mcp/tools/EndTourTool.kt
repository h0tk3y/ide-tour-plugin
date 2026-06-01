package com.gradle.idetour.mcp.tools

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

class EndTourTool : Tool {
    override val name = "end_tour"
    override val description = "Dispose a tour and release its resources."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("tourId", Schema.stringProp("The id returned by start_tour."))
        },
        required = listOf("tourId")
    )

    override fun call(args: JsonObject): JsonObject {
        val tourId = args.get("tourId")?.asString ?: return toolError("Missing tourId")

        val future = CompletableFuture<JsonObject>()
        // Tour disposal touches editor Inlay objects; those require EDT.
        ApplicationManager.getApplication().invokeLater {
            try {
                var found = false
                for (project in ProjectManager.getInstance().openProjects) {
                    if (project.service<ToursService>().end(tourId)) {
                        found = true
                        break
                    }
                }
                future.complete(if (found) toolOk() else toolError("No tour with id $tourId"))
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "end_tour failed"))
            }
        }
        return future.get(10, TimeUnit.SECONDS)
    }
}
