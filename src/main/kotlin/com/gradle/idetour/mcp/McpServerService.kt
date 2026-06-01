package com.gradle.idetour.mcp

import com.gradle.idetour.mcp.tools.AddItemsTool
import com.gradle.idetour.mcp.tools.EndTourTool
import com.gradle.idetour.mcp.tools.GetCurrentItemTool
import com.gradle.idetour.mcp.tools.GotoItemTool
import com.gradle.idetour.mcp.tools.ListItemsTool
import com.gradle.idetour.mcp.tools.NavigateTool
import com.gradle.idetour.mcp.tools.NextItemTool
import com.gradle.idetour.mcp.tools.PrevItemTool
import com.gradle.idetour.mcp.tools.RemoveItemTool
import com.gradle.idetour.mcp.tools.SetItemsTool
import com.gradle.idetour.mcp.tools.StartTourTool
import com.gradle.idetour.mcp.tools.UpdateItemTool
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class McpServerService : Disposable {
    private var server: HttpServer? = null

    init {
        start()
    }

    private fun start() {
        try {
            val tools = ToolRegistry(listOf(
                NavigateTool(),
                StartTourTool(),
                EndTourTool(),
                AddItemsTool(),
                SetItemsTool(),
                UpdateItemTool(),
                RemoveItemTool(),
                ListItemsTool(),
                GotoItemTool(),
                GetCurrentItemTool(),
                NextItemTool(),
                PrevItemTool()
            ))
            val protocol = McpProtocol(tools)
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", DEFAULT_PORT), 0)
            httpServer.createContext("/mcp", McpHttpHandler(protocol))
            httpServer.executor = Executors.newCachedThreadPool { r ->
                Thread(r, "intellij-tour-mcp").apply { isDaemon = true }
            }
            httpServer.start()
            server = httpServer
            thisLogger().info("[ide-tour-plugin] MCP server listening on http://127.0.0.1:$DEFAULT_PORT/mcp")
        } catch (e: Exception) {
            thisLogger().error("[ide-tour-plugin] Failed to start MCP server", e)
        }
    }

    override fun dispose() {
        shutdown()
    }

    /**
     * Stop the HTTP server and shut down its executor synchronously.
     *
     * Idempotent. Called both from [dispose] (app shutdown) and from [PluginUnloadListener]
     * (plugin reload / uninstall) to release the thread pool before the IDE tries to unload
     * our classloader — without this, the cached daemon threads pin our plugin classes.
     */
    fun shutdown() {
        val srv = server ?: return
        server = null
        try {
            srv.stop(1) // wait up to 1s for in-flight requests
        } catch (e: Exception) {
            thisLogger().warn("[ide-tour-plugin] Error stopping HTTP server", e)
        }
        (srv.executor as? ExecutorService)?.let { exec ->
            exec.shutdownNow()
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thisLogger().info("[ide-tour-plugin] MCP server stopped")
        // Hint the JVM to GC promptly so the plugin classloader can be released
        // within the 5s window the IDE allows before declaring "not unload-safe".
        // Not guaranteed to run synchronously (modern GCs may ignore), but harmless.
        System.gc()
    }

    companion object {
        const val DEFAULT_PORT = 64343
    }
}
