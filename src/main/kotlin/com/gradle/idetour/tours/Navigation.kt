package com.gradle.idetour.tours

import com.gradle.idetour.tours.model.Position
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project

/** Open the tour's current item in the editor and scroll to its anchor. Must be called on EDT. */
fun openCurrentItem(project: Project, tour: TourState) {
    val entry = tour.currentEntry() ?: return
    val vf = entry.pointer.file ?: return
    val anchor = entry.spec.anchor
        ?: entry.spec.inlays.firstOrNull()?.let { Position(it.line) }
        ?: Position(1)
    val descriptor = OpenFileDescriptor(project, vf, anchor.line - 1, (anchor.col - 1).coerceAtLeast(0))
    FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
}
