package com.gradle.idetour.tours

import com.gradle.idetour.tours.model.Position
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project

/**
 * Open the tour's current item in the editor and scroll to its anchor.
 *
 * The open is scheduled via [ApplicationManager.invokeLater] rather than executed inline:
 * MCP tool handlers run on the HTTP-server thread and must not block on EDT work.
 *
 * @param focusEditor when true (the default, used by MCP tools), focus transfers to the editor.
 *                    When false (used by the tool window's preview navigation), focus stays on
 *                    the caller so keyboard navigation in the list keeps working.
 */
fun openCurrentItem(project: Project, tour: TourState, focusEditor: Boolean = true) {
    val entry = tour.currentEntry() ?: return
    val vf = entry.pointer.file ?: return
    val anchor = entry.spec.anchor
        ?: entry.spec.inlays.firstOrNull()?.let { Position(it.line) }
        ?: Position(1)
    val descriptor = OpenFileDescriptor(project, vf, anchor.line - 1, (anchor.col - 1).coerceAtLeast(0))
    ApplicationManager.getApplication().invokeLater {
        FileEditorManager.getInstance(project).openTextEditor(descriptor, focusEditor)
    }
}
