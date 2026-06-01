package com.gradle.idetour

import com.gradle.idetour.mcp.McpServerService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class PluginStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        thisLogger().info("[ide-tour-plugin] started for project: ${project.name}")
        // Force the application-scope MCP server to initialize on first project open.
        ApplicationManager.getApplication().service<McpServerService>()
    }
}
