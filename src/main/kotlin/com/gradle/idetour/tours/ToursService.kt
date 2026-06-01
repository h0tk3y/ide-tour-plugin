package com.gradle.idetour.tours

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface ToursServiceListener {
    fun onTourAdded(tour: TourState) {}
    fun onTourRemoved(tourId: String) {}
}

@Service(Service.Level.PROJECT)
class ToursService(private val project: Project) : Disposable {
    private val tours = ConcurrentHashMap<String, TourState>()
    private val listeners = mutableListOf<ToursServiceListener>()

    fun addListener(listener: ToursServiceListener) { listeners.add(listener) }
    fun removeListener(listener: ToursServiceListener) { listeners.remove(listener) }
    private fun fireTourAdded(t: TourState) { listeners.toList().forEach { it.onTourAdded(t) } }
    private fun fireTourRemoved(id: String) { listeners.toList().forEach { it.onTourRemoved(id) } }

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    for (fileEditor in source.getAllEditors(file)) {
                        val editor = (fileEditor as? TextEditor)?.editor ?: continue
                        for (tour in tours.values) {
                            tour.materializeForEditor(editor)
                        }
                    }
                }
            }
        )
    }

    fun start(title: String?): String {
        val id = UUID.randomUUID().toString()
        val tour = TourState(id, project, title)
        // Register with the Disposer so children (VirtualFilePointers, etc.)
        // are released automatically when the tour or this service is disposed.
        Disposer.register(this, tour)
        tours[id] = tour
        fireTourAdded(tour)
        return id
    }

    fun end(tourId: String): Boolean {
        val tour = tours.remove(tourId) ?: return false
        Disposer.dispose(tour)
        fireTourRemoved(tourId)
        return true
    }

    fun get(tourId: String): TourState? = tours[tourId]

    fun all(): Collection<TourState> = tours.values

    /** Materialize a given tour's items into all currently open editors of the project. */
    fun materializeForOpenEditors(tour: TourState) {
        val manager = FileEditorManager.getInstance(project)
        for (file in manager.openFiles) {
            for (fileEditor in manager.getAllEditors(file)) {
                val editor = (fileEditor as? TextEditor)?.editor ?: continue
                tour.materializeForEditor(editor)
            }
        }
    }

    override fun dispose() {
        // Children registered via Disposer.register(this, tour) are disposed before this
        // method runs. We just clear our own map references.
        tours.clear()
    }
}
