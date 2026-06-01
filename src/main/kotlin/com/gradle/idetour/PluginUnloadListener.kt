package com.gradle.idetour

import com.gradle.idetour.mcp.McpServerService
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager

/**
 * Releases plugin-owned resources (the HTTP server's thread pool) before the IDE tries
 * to unload our classloader. Without this, the cached daemon threads keep references
 * to plugin classes, blocking dynamic unload.
 */
class PluginUnloadListener : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        ApplicationManager.getApplication()
            .getServiceIfCreated(McpServerService::class.java)
            ?.shutdown()
    }

    private companion object {
        const val PLUGIN_ID = "com.gradle.idetour"
    }
}
