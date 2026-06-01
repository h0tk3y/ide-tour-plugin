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

class StartTourTool : Tool {
    override val name = "start_tour"
    override val description = "Allocate a new tour bound to the given project. Returns the tourId other tools take."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("projectPath", Schema.stringProp("Absolute path of the open project (the project base dir)."))
            add("title", Schema.stringProp("Optional human-readable title shown in the tool window."))
        },
        required = listOf("projectPath")
    )

    override fun call(args: JsonObject): JsonObject {
        val projectPath = args.get("projectPath")?.asString ?: return toolError("Missing projectPath")
        val title = args.get("title")?.takeIf { !it.isJsonNull }?.asString

        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == projectPath }
            ?: return toolError("No open project at $projectPath")

        // Listener cascade from onTourAdded can touch editor models, so dispatch to EDT.
        val future = CompletableFuture<JsonObject>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val tourId = project.service<ToursService>().start(title)
                future.complete(toolOk { addProperty("tourId", tourId) })
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "start_tour failed"))
            }
        }
        return future.get(10, TimeUnit.SECONDS)
    }
}
