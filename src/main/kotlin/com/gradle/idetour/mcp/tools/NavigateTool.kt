package com.gradle.idetour.mcp.tools

import com.google.gson.JsonObject
import com.gradle.idetour.mcp.Schema
import com.gradle.idetour.mcp.Tool
import com.gradle.idetour.mcp.toolError
import com.gradle.idetour.mcp.toolOk
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NavigateTool : Tool {
    override val name = "navigate"
    override val description = "Navigate the editor to a file:line[:col] location. Stateless; not bound to a tour."
    override val inputSchema = Schema.obj(
        properties = JsonObject().apply {
            add("projectPath", Schema.stringProp("Absolute path of the open project (the project base dir)."))
            add("file", Schema.stringProp("Project-relative or absolute path. Leading '/' means absolute."))
            add("line", Schema.intProp("1-based line number."))
            add("col", Schema.intProp("1-based column number; defaults to 1."))
        },
        required = listOf("projectPath", "file", "line")
    )

    override fun call(args: JsonObject): JsonObject {
        val projectPath = args.get("projectPath")?.asString ?: return toolError("Missing projectPath")
        val file = args.get("file")?.asString ?: return toolError("Missing file")
        val line = args.get("line")?.asInt ?: return toolError("Missing line")
        val col = args.get("col")?.takeIf { !it.isJsonNull }?.asInt ?: 1

        val future = CompletableFuture<JsonObject>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == projectPath }
                    ?: throw IllegalStateException("No open project at $projectPath")
                val absolutePath = if (file.startsWith("/")) file else "${project.basePath}/$file"
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
                    ?: throw IllegalStateException("File not found: $absolutePath")
                val descriptor = OpenFileDescriptor(project, virtualFile, line - 1, col - 1)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                future.complete(toolOk())
            } catch (e: Exception) {
                future.complete(toolError(e.message ?: "Navigation failed"))
            }
        }
        return future.get(10, TimeUnit.SECONDS)
    }
}
